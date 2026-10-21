// Package objectstoretest is the object stores contract's test suite (contract section 6): every
// driver's tests call Run with a harness, and the suite checks the operations, the key rules, the
// limit and concurrent puts, one subtest per case.
package objectstoretest

import (
	"bytes"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"strings"
	"sync"
	"testing"

	"github.com/flowcatalyst/framework/go/objectstore"
)

// Harness supplies the driver under test.
type Harness struct {
	// New returns a store whose size limit is Limit, empty or private to this test (the suite's
	// keys are unique per run, so one shared store may be returned every time).
	New func(t *testing.T) objectstore.Store
	// Limit is the size limit of the stores New returns, at least 1 KiB.
	Limit int
}

// key returns a key unique to this run.
func key(t *testing.T, tail string) string {
	t.Helper()
	var r [6]byte
	if _, err := rand.Read(r[:]); err != nil {
		t.Fatal(err)
	}
	return "contract-" + hex.EncodeToString(r[:]) + "/" + tail
}

// Run runs the whole suite against the harness's store.
func Run(t *testing.T, h Harness) {
	t.Helper()
	cases := []struct {
		name string
		run  func(t *testing.T, s objectstore.Store, limit int)
	}{
		{"put then get returns the bytes", putThenGet},
		{"a missing key is absent, not an error", missingIsAbsent},
		{"a second put replaces the first completely", secondPutReplaces},
		{"bytes are kept exactly, zeros and invalid UTF-8 included", bytesExact},
		{"empty content is a value", emptyIsAValue},
		{"delete removes and is idempotent", deleteIdempotent},
		{"nested keys are independent values", nestedKeys},
		{"a caller cannot change a stored value afterwards", noAliasing},
		{"keys that could escape or are malformed are refused", badKeys},
		{"content over the limit is refused and nothing is stored", overLimit},
		{"concurrent puts to one key leave one whole value", concurrentPuts},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			c.run(t, h.New(t), h.Limit)
		})
	}
}

func mustPut(t *testing.T, s objectstore.Store, k string, v []byte) {
	t.Helper()
	if err := s.Put(t.Context(), k, v); err != nil {
		t.Fatalf("put %q: %v", k, err)
	}
}

// mustGet returns the value, failing the test when it is absent.
func mustGet(t *testing.T, s objectstore.Store, k string) []byte {
	t.Helper()
	v, ok, err := s.Get(t.Context(), k)
	if err != nil {
		t.Fatalf("get %q: %v", k, err)
	}
	if !ok {
		t.Fatalf("get %q: absent", k)
	}
	return v
}

func wantAbsent(t *testing.T, s objectstore.Store, k string) {
	t.Helper()
	v, ok, err := s.Get(t.Context(), k)
	if err != nil || ok || v != nil {
		t.Fatalf("get %q: want absent and no error, got %v, %v, %v", k, v, ok, err)
	}
}

func putThenGet(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "a.json")
	mustPut(t, s, k, []byte(`{"x":1}`))
	if got := mustGet(t, s, k); string(got) != `{"x":1}` {
		t.Errorf("got %q", got)
	}
}

func missingIsAbsent(t *testing.T, s objectstore.Store, _ int) {
	wantAbsent(t, s, key(t, "nothing-here"))
}

func secondPutReplaces(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "r")
	mustPut(t, s, k, []byte{1, 2, 3, 4, 5})
	mustPut(t, s, k, []byte{9})
	if got := mustGet(t, s, k); !bytes.Equal(got, []byte{9}) {
		t.Errorf("got %v", got)
	}
}

func bytesExact(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "bin")
	all := make([]byte, 256)
	for i := range all {
		all[i] = byte(i)
	}
	mustPut(t, s, k, all)
	if got := mustGet(t, s, k); !bytes.Equal(got, all) {
		t.Errorf("bytes changed: %v", got)
	}
}

func emptyIsAValue(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "empty")
	mustPut(t, s, k, nil)
	if got := mustGet(t, s, k); len(got) != 0 {
		t.Errorf("got %v", got)
	}
	k2 := key(t, "empty2")
	mustPut(t, s, k2, []byte{})
	mustGet(t, s, k2)
}

func deleteIdempotent(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "d")
	mustPut(t, s, k, []byte{1})
	if err := s.Delete(t.Context(), k); err != nil {
		t.Fatal(err)
	}
	wantAbsent(t, s, k)
	if err := s.Delete(t.Context(), k); err != nil { // again: not an error
		t.Fatal(err)
	}
	if err := s.Delete(t.Context(), key(t, "never-existed")); err != nil {
		t.Fatal(err)
	}
}

func nestedKeys(t *testing.T, s objectstore.Store, _ int) {
	base := key(t, "n")
	mustPut(t, s, base+"/one", []byte{1})
	mustPut(t, s, base+"/two/three", []byte{2})
	if got := mustGet(t, s, base+"/one"); !bytes.Equal(got, []byte{1}) {
		t.Errorf("one: %v", got)
	}
	if got := mustGet(t, s, base+"/two/three"); !bytes.Equal(got, []byte{2}) {
		t.Errorf("three: %v", got)
	}
	if err := s.Delete(t.Context(), base+"/one"); err != nil {
		t.Fatal(err)
	}
	if got := mustGet(t, s, base+"/two/three"); !bytes.Equal(got, []byte{2}) {
		t.Errorf("three after deleting one: %v", got)
	}
}

func noAliasing(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "alias")
	in := []byte{1, 2, 3}
	mustPut(t, s, k, in)
	in[0] = 99
	out := mustGet(t, s, k)
	if len(out) != 3 || !bytes.Equal(out, []byte{1, 2, 3}) {
		t.Fatalf("a change to the put slice reached the store: %v", out)
	}
	out[1] = 99
	if again := mustGet(t, s, k); !bytes.Equal(again, []byte{1, 2, 3}) {
		t.Errorf("a change to the returned slice reached the store: %v", again)
	}
}

func badKeys(t *testing.T, s objectstore.Store, _ int) {
	bad := []string{
		"", "/abs", "a//b", "a/", "../x", "a/../b", "a/./b", ".hidden", "a/.hidden", "a b", `a\b`, "a\x00b", "é",
		strings.Repeat("a", objectstore.MaxKeyLength+1),
	}
	for _, k := range bad {
		if err := s.Put(t.Context(), k, []byte{1}); !errors.Is(err, objectstore.ErrInvalidArgument) {
			t.Errorf("put %q: want ErrInvalidArgument, got %v", k, err)
		}
		if _, _, err := s.Get(t.Context(), k); !errors.Is(err, objectstore.ErrInvalidArgument) {
			t.Errorf("get %q: want ErrInvalidArgument, got %v", k, err)
		}
		if err := s.Delete(t.Context(), k); !errors.Is(err, objectstore.ErrInvalidArgument) {
			t.Errorf("delete %q: want ErrInvalidArgument, got %v", k, err)
		}
	}
}

func overLimit(t *testing.T, s objectstore.Store, limit int) {
	k := key(t, "big")
	if err := s.Put(t.Context(), k, make([]byte, limit+1)); !errors.Is(err, objectstore.ErrInvalidArgument) {
		t.Fatalf("want ErrInvalidArgument, got %v", err)
	}
	wantAbsent(t, s, k)
	mustPut(t, s, k, make([]byte, limit))
	if got := mustGet(t, s, k); len(got) != limit {
		t.Errorf("got %d bytes", len(got))
	}
}

func concurrentPuts(t *testing.T, s objectstore.Store, _ int) {
	k := key(t, "race")
	const writers = 8
	var wg sync.WaitGroup
	for w := range writers {
		wg.Go(func() {
			v := bytes.Repeat([]byte{byte(w + 1)}, 512)
			for range 20 {
				if err := s.Put(t.Context(), k, v); err != nil {
					t.Errorf("put: %v", err)
					return
				}
			}
		})
	}
	wg.Wait()
	got := mustGet(t, s, k)
	if len(got) != 512 {
		t.Fatalf("got %d bytes", len(got))
	}
	if !bytes.Equal(got, bytes.Repeat(got[:1], 512)) {
		t.Errorf("a mixture of two writers' values")
	}
}
