// Package conformance reads the shared conformance vectors in the repository's conformance/
// directory (written by the Java framework's tests) and compares them with what the Go framework
// does, by the rules of conformance/README.md:
//
//   - JSON values are compared as parsed values ([EqualJSON]): key order and whitespace are free,
//     values exact; a JSON document carried inside a string is parsed and compared the same way;
//   - files under sql/ and lua/ are compared as exact bytes ([EqualBytes]);
//   - an allowed difference is recorded in the vector as "go": {...} with a reason ([Allowed]).
//
// The tests of this package are the Go conformance runner: go test ./internal/conformance/...
// Failure messages name the vector, the case and the field.
package conformance

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// modulePath is the core module's path; Dir looks for its go.mod.
const modulePath = "github.com/flowcatalyst/framework/go"

// Dir returns the absolute path of the repository's conformance/ directory: the sibling of the
// core module's directory (go/). It fails the test when it cannot be found.
func Dir(t testing.TB) string {
	t.Helper()
	dir, err := os.Getwd()
	if err != nil {
		t.Fatalf("conformance: working directory: %v", err)
	}
	for {
		mod, err := os.ReadFile(filepath.Join(dir, "go.mod"))
		if err == nil && strings.Contains(string(mod), "module "+modulePath+"\n") {
			c := filepath.Join(filepath.Dir(dir), "conformance")
			if st, err := os.Stat(c); err != nil || !st.IsDir() {
				t.Fatalf("conformance: %s is not a directory (is this a checkout of the framework repository?)", c)
			}
			return c
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatalf("conformance: no go.mod of module %s above the working directory", modulePath)
		}
		dir = parent
	}
}

// ModuleDir returns the absolute path of the core module (go/).
func ModuleDir(t testing.TB) string {
	t.Helper()
	return filepath.Join(filepath.Dir(Dir(t)), "go")
}

// ReadFile returns the bytes of a file under conformance/, for example "sql/locks/acquire.sql".
func ReadFile(t testing.TB, rel string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join(Dir(t), filepath.FromSlash(rel)))
	if err != nil {
		t.Fatalf("conformance: read %s: %v", rel, err)
	}
	return b
}

// ReadJSON decodes a vector file under conformance/ into v.
func ReadJSON(t testing.TB, rel string, v any) {
	t.Helper()
	if err := json.Unmarshal(ReadFile(t, rel), v); err != nil {
		t.Fatalf("conformance: decode %s: %v", rel, err)
	}
}

// EqualBytes fails the test unless got equals want exactly; where names the vector, case and
// field. The message shows the first differing byte.
func EqualBytes(t testing.TB, where string, want, got []byte) {
	t.Helper()
	if bytes.Equal(want, got) {
		return
	}
	i := 0
	for i < len(want) && i < len(got) && want[i] == got[i] {
		i++
	}
	t.Errorf("%s: bytes differ at offset %d (want %d bytes, got %d)\n want: %q\n  got: %q",
		where, i, len(want), len(got), excerpt(want, i), excerpt(got, i))
}

func excerpt(b []byte, at int) string {
	lo, hi := max(0, at-40), min(len(b), at+40)
	return string(b[lo:hi])
}

// EqualJSON fails the test unless want and got are the same JSON value: both are canonicalised
// (RFC 8785: sorted keys, no whitespace, numbers in one form) and compared.
func EqualJSON(t testing.TB, where string, want, got []byte) {
	t.Helper()
	w, err := canonical(want)
	if err != nil {
		t.Errorf("%s: expected value is not JSON: %v", where, err)
		return
	}
	g, err := canonical(got)
	if err != nil {
		t.Errorf("%s: value is not JSON: %v\n%s", where, err, got)
		return
	}
	if !bytes.Equal(w, g) {
		t.Errorf("%s: JSON values differ\n want: %s\n  got: %s", where, w, g)
	}
}

func canonical(b []byte) ([]byte, error) {
	v := jsontext.Value(bytes.Clone(b))
	if err := v.Canonicalize(); err != nil {
		return nil, err
	}
	return v, nil
}

// Allowed returns the reason recorded under "go" in a vector object for the allowed difference
// named key, or "" when there is none. A vector records one as "go": {"<key>": "<reason>"}.
func Allowed(obj map[string]jsontext.Value, key string) string {
	raw, ok := obj["go"]
	if !ok {
		return ""
	}
	var reasons map[string]string
	if err := json.Unmarshal(raw, &reasons); err != nil {
		return ""
	}
	return reasons[key]
}

// Where formats a location for failure messages: vector, case and field.
func Where(vector string, cas any, field string) string {
	return fmt.Sprintf("%s [%v] %s", vector, cas, field)
}
