// Package b64urltest holds what the spelling tests of the framework's verifiers share
// (docs/contracts/token-spelling.md): the other spellings of a value, the changes that certainly
// alter it, the property a fuzz target holds a verifier to, and a reader for the spelling vectors
// (conformance/auth/spelling.json, conformance/push/spelling.json). It mirrors the Java test helper
// Respellings, so both languages try the same cases.
package b64urltest

import (
	"bytes"
	"encoding/base64"
	"encoding/json/v2"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/internal/b64url"
)

// Alphabet is the base64url alphabet, in value order.
const Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

// Spelling is one changed value.
type Spelling struct {
	Name  string `json:"name"`
	Value string `json:"value"`
	// Expect is the answer the vector records (empty for a case built here).
	Expect string `json:"expect"`
}

// SpareBits is how many low bits of the last character of an unpadded base64url string of n
// characters carry nothing: 0, 4 (2 mod 4) or 2 (3 mod 4); -1 for 1 mod 4, which is not base64.
func SpareBits(n int) int {
	switch n % 4 {
	case 0:
		return 0
	case 2:
		return 4
	case 3:
		return 2
	default:
		return -1
	}
}

// LastCharacterSpellings returns every string that differs from a canonical part only in its last
// character and decodes, leniently, to the same bytes: 2^SpareBits - 1 of them.
func LastCharacterSpellings(part string) []string {
	spare := SpareBits(len(part))
	if part == "" || spare <= 0 {
		return []string{}
	}
	last := strings.IndexByte(Alphabet, part[len(part)-1])
	mask := 1<<spare - 1
	if last < 0 || last&mask != 0 {
		panic("not canonical: " + part)
	}
	out := make([]string, 0, mask)
	for low := 1; low <= mask; low++ {
		out = append(out, part[:len(part)-1]+string(Alphabet[last|low]))
	}
	return out
}

func with(parts []string, i int, replacement string) string {
	c := append([]string(nil), parts...)
	c[i] = replacement
	return strings.Join(c, ".")
}

func label(parts []string, target int) string {
	if len(parts) == 1 {
		return "the value"
	}
	return "part " + strconv.Itoa(target)
}

// Of returns every other spelling of value (canonical base64url parts separated by dots) that the
// contract names, around its part target, in the same order and with the same names as the Java
// helper.
func Of(t testing.TB, value string, target int) []Spelling {
	t.Helper()
	parts := strings.Split(value, ".")
	part := parts[target]
	raw, err := b64url.Decode(part)
	if err != nil {
		t.Fatalf("the issued value is not canonical: %q", value)
	}
	l := label(parts, target)
	var out []Spelling
	for _, other := range LastCharacterSpellings(part) {
		lenient, err := base64.RawURLEncoding.DecodeString(other)
		if err != nil || !bytes.Equal(lenient, raw) {
			t.Fatalf("test bug: %q is not another spelling of %q", other, part)
		}
		if _, err := b64url.Decode(other); err == nil {
			t.Fatalf("test bug: %q is canonical", other)
		}
		out = append(out, Spelling{Name: l + " ends in " + other[len(other)-1:], Value: with(parts, target, other)})
	}
	out = append(out,
		Spelling{Name: l + " padded with =", Value: with(parts, target, part+"=")},
		Spelling{Name: l + " padded with ==", Value: with(parts, target, part+"==")})
	if std := strings.NewReplacer("-", "+", "_", "/").Replace(part); std != part {
		out = append(out, Spelling{Name: l + " in the standard alphabet", Value: with(parts, target, std)})
	}
	mid := len(part) / 2
	out = append(out,
		Spelling{Name: l + " with a line feed inside", Value: with(parts, target, part[:mid]+"\n"+part[mid:])},
		Spelling{Name: l + " with a carriage return inside", Value: with(parts, target, part[:mid]+"\r"+part[mid:])},
		Spelling{Name: l + " with CRLF inside", Value: with(parts, target, part[:mid]+"\r\n"+part[mid:])})
	for i, p := range parts {
		pad := (4 - len(p)%4) % 4
		if _, err := b64url.Decode(p); i != target && pad > 0 && err == nil {
			out = append(out, Spelling{Name: "part " + strconv.Itoa(i) + " padded", Value: with(parts, i, p+strings.Repeat("=", pad))})
		}
	}
	out = append(out,
		Spelling{Name: "a space before", Value: " " + value},
		Spelling{Name: "a space after", Value: value + " "},
		Spelling{Name: "a tab after", Value: value + "\t"},
		Spelling{Name: "a line feed after", Value: value + "\n"},
		Spelling{Name: "CRLF after", Value: value + "\r\n"},
		Spelling{Name: "CRLF before", Value: "\r\n" + value})
	for _, s := range out {
		if s.Value == value {
			t.Fatalf("test bug: %s equals the issued value", s.Name)
		}
	}
	return out
}

// Resigned returns, for every part before the signature (the last part), each other last
// character, the correct '=' padding (when the part has spare bits) and a line feed, a carriage
// return and a CRLF inside it, each with the signature recomputed by sign over the new text (the
// parts before the signature, joined with dots), so that the signature is valid over what is sent:
// a verifier that decodes leniently accepts every one, and only the spelling rule refuses it. It
// mirrors the Java helper Respellings.resigned, with the same names.
func Resigned(t testing.TB, value string, sign func(signed string) string) []Spelling {
	t.Helper()
	parts := strings.Split(value, ".")
	var out []Spelling
	for target := range len(parts) - 1 {
		part := parts[target]
		label := "part " + strconv.Itoa(target)
		var variants []Spelling
		for _, other := range LastCharacterSpellings(part) {
			variants = append(variants, Spelling{Name: label + " ends in " + other[len(other)-1:], Value: other})
		}
		if pad := (4 - len(part)%4) % 4; pad > 0 {
			variants = append(variants, Spelling{Name: label + " padded with " + strings.Repeat("=", pad), Value: part + strings.Repeat("=", pad)})
		}
		mid := len(part) / 2
		variants = append(variants,
			Spelling{Name: label + " with a line feed inside", Value: part[:mid] + "\n" + part[mid:]},
			Spelling{Name: label + " with a carriage return inside", Value: part[:mid] + "\r" + part[mid:]},
			Spelling{Name: label + " with CRLF inside", Value: part[:mid] + "\r\n" + part[mid:]})
		for _, v := range variants {
			changed := append([]string(nil), parts...)
			changed[target] = v.Value
			changed[len(parts)-1] = sign(strings.Join(changed[:len(parts)-1], "."))
			out = append(out, Spelling{Name: v.Name + ", signed again", Value: strings.Join(changed, ".")})
		}
	}
	return out
}

// Tampered returns changes that certainly alter the bytes of part target or the value's shape (the
// same as the Java helper's): its first bit flipped, its last bit flipped, one byte shorter, the
// value one character shorter, the part emptied and the part removed.
func Tampered(t testing.TB, value string, target int) []Spelling {
	t.Helper()
	parts := strings.Split(value, ".")
	raw, err := b64url.Decode(parts[target])
	if err != nil || len(raw) < 2 {
		t.Fatalf("the part must be canonical and at least two bytes: %q", value)
	}
	l := label(parts, target)
	first := bytes.Clone(raw)
	first[0] ^= 0x80
	last := bytes.Clone(raw)
	last[len(last)-1] ^= 0x01
	out := []Spelling{
		{Name: l + ": first bit flipped", Value: with(parts, target, b64url.Encode(first))},
		{Name: l + ": last bit flipped", Value: with(parts, target, b64url.Encode(last))},
		{Name: l + ": one byte shorter", Value: with(parts, target, b64url.Encode(raw[:len(raw)-1]))},
		{Name: "one character shorter", Value: value[:len(value)-1]},
		{Name: l + " emptied", Value: with(parts, target, "")},
	}
	if len(parts) > 1 {
		fewer := append(append([]string(nil), parts[:target]...), parts[target+1:]...)
		out = append(out, Spelling{Name: l + " removed", Value: strings.Join(fewer, ".")})
	}
	return out
}

// SameAsReencoded reports whether every dot-separated part of value equals the standard encoder's
// output for the bytes a lenient decoder reads from it: true for a value spelled as issued, false
// for any other spelling of the same bytes. It is written independently of package b64url, so a
// fuzz target can hold a verifier to it: whatever a verifier accepts must satisfy it.
func SameAsReencoded(value string) bool {
	for p := range strings.SplitSeq(value, ".") {
		b, err := base64.RawURLEncoding.DecodeString(p)
		if err != nil || base64.RawURLEncoding.EncodeToString(b) != p {
			return false
		}
	}
	return true
}

// Cases is one value of a spelling vector: as issued, with its answer, and its changed spellings.
type Cases struct {
	Issued    string     `json:"issued"`
	Expect    string     `json:"expect"`
	Respelled []Spelling `json:"respelled"`
	Tampered  []Spelling `json:"tampered"`
	// Resigned are values whose signed text itself (a header or a payload) is re-spelled and then
	// signed again, so that a verifier that decodes leniently would accept them (absent where the
	// test cannot sign: a token the platform signed).
	Resigned []Spelling `json:"resigned"`
}

// Run checks a vector's value with verify: the issued value gives Expect, every re-spelling,
// re-signed and tampered value gives its own expect, and the issued value still gives Expect afterwards. It
// returns how many checks ran.
func (c Cases) Run(t *testing.T, verify func(string) string) int {
	t.Helper()
	if got := verify(c.Issued); got != c.Expect {
		t.Fatalf("as issued: got %q, want %q", got, c.Expect)
	}
	n := 1
	for _, list := range [][]Spelling{c.Respelled, c.Tampered, c.Resigned} {
		for _, s := range list {
			if got := verify(s.Value); got != s.Expect {
				t.Errorf("%s: got %q, want %q", s.Name, got, s.Expect)
			}
			n++
		}
	}
	if got := verify(c.Issued); got != c.Expect {
		t.Errorf("as issued, again: got %q, want %q", got, c.Expect)
	}
	return n
}

// Fresh checks a value issued by the test: valid is its answer; every spelling of Of and Tampered
// (of part target) must get another answer, and the value valid again afterwards.
func Fresh(t *testing.T, value string, target int, valid string, verify func(string) string) {
	t.Helper()
	if got := verify(value); got != valid {
		t.Fatalf("as issued: got %q, want %q (%q)", got, valid, value)
	}
	for _, s := range append(Of(t, value, target), Tampered(t, value, target)...) {
		if got := verify(s.Value); got == valid {
			t.Errorf("%s: accepted %q", s.Name, s.Value)
		}
	}
	if got := verify(value); got != valid {
		t.Errorf("as issued, again: got %q", got)
	}
}

// Read decodes a file under the repository's conformance/ directory into v.
func Read(t testing.TB, relative string, v any) {
	t.Helper()
	_, here, _, _ := runtime.Caller(0)
	path := filepath.Join(filepath.Dir(here), "..", "..", "..", "..", "conformance", relative)
	raw, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, v); err != nil {
		t.Fatalf("%s: %v", relative, err)
	}
}
