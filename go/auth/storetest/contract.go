// Package storetest is the contract every [auth.Store] must meet (sessions-and-login contract
// section 6.3), for the memory store and the drivers in authdb and authredis: [Run] builds a fresh
// store on a fake clock for each case.
package storetest

import (
	"context"
	"encoding/json/v2"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fwtest"
)

// Start is the time the fake clock starts at.
var Start = time.Date(2026, 10, 13, 8, 0, 0, 0, time.UTC)

// Factory builds a fresh, empty store reading time from the clock.
type Factory func(t *testing.T, clock *fwtest.Clock) auth.Store

// Run runs the contract against stores from newStore. It is for server stores: a record is gone
// after Delete or Take, and a handle is the 43-character random value.
func Run(t *testing.T, newStore Factory) {
	t.Helper()
	cases := map[string]func(*testing.T, *fwtest.Clock, auth.Store){
		"malformed handles are none without failing":        malformedHandles,
		"an unknown handle is none":                         unknownHandle,
		"a record is read back by its handle and kind only": readByHandleAndKind,
		"a record is gone at its expiry":                    goneAtExpiry,
		"take returns the record once":                      takeOnce,
		"concurrent takes give the record to exactly one":   concurrentTakes,
		"update replaces the record and its expiry":         updateReplaces,
		"update does not bring a deleted record back":       updateNotResurrecting,
		"delete revokes and deleting twice is fine":         deleteRevokes,
		"handles are unique":                                handlesUnique,
	}
	for name, f := range cases {
		t.Run(name, func(t *testing.T) {
			clock := fwtest.NewClock(Start)
			f(t, clock, newStore(t, clock))
		})
	}
}

var ctx = context.Background() //nolint:forbidigo // a package-level context for a test helper

func create(t *testing.T, s auth.Store, kind auth.Kind, doc string, expires time.Time) string {
	t.Helper()
	h, err := s.Create(ctx, kind, []byte(doc), expires)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	return h
}

func present(t *testing.T, s auth.Store, kind auth.Kind, h string) bool {
	t.Helper()
	_, ok, err := s.Read(ctx, kind, h)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	return ok
}

func sameJSON(t *testing.T, got []byte, want string) {
	t.Helper()
	var a, b any
	if json.Unmarshal(got, &a) != nil || json.Unmarshal([]byte(want), &b) != nil || fmt.Sprint(a) != fmt.Sprint(b) {
		t.Fatalf("record = %s, want %s", got, want)
	}
}

func malformedHandles(t *testing.T, _ *fwtest.Clock, s auth.Store) {
	for _, bad := range []string{"", "x", strings.Repeat("a", 42), strings.Repeat("a", 44), strings.Repeat("a", 42) + "!", "' OR 1=1 --", "v1.k1.abc"} {
		if present(t, s, auth.KindSession, bad) {
			t.Errorf("%q: read found a record", bad)
		}
		if _, ok, err := s.Take(ctx, auth.KindLogin, bad); ok || err != nil {
			t.Errorf("%q: take = %v %v", bad, ok, err)
		}
		if err := s.Delete(ctx, auth.KindSession, bad); err != nil {
			t.Errorf("%q: delete: %v", bad, err)
		}
	}
}

func unknownHandle(t *testing.T, _ *fwtest.Clock, s auth.Store) {
	if present(t, s, auth.KindSession, auth.NewHandle()) {
		t.Error("an unknown handle found a record")
	}
	if _, ok, _ := s.Take(ctx, auth.KindLogin, auth.NewHandle()); ok {
		t.Error("take of an unknown handle found a record")
	}
}

func readByHandleAndKind(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindSession, `{"v":1,"a":"b"}`, clock.Now().Add(5*time.Minute))
	if !auth.IsWellFormedHandle(h) {
		t.Fatalf("handle %q is not well-formed", h)
	}
	data, ok, err := s.Read(ctx, auth.KindSession, h)
	if err != nil || !ok {
		t.Fatalf("read: %v %v", ok, err)
	}
	sameJSON(t, data, `{"v":1,"a":"b"}`)
	if present(t, s, auth.KindLogin, h) {
		t.Error("another kind must not read the record")
	}
	if _, ok, _ := s.Take(ctx, auth.KindLogin, h); ok {
		t.Error("another kind must not take the record")
	}
	if !present(t, s, auth.KindSession, h) {
		t.Error("the record must survive a read of another kind")
	}
}

func goneAtExpiry(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindSession, `{}`, clock.Now().Add(5*time.Minute))
	clock.Advance(5*time.Minute - time.Millisecond)
	if !present(t, s, auth.KindSession, h) {
		t.Fatal("the record is there a millisecond before its expiry")
	}
	clock.Advance(time.Millisecond)
	if present(t, s, auth.KindSession, h) {
		t.Fatal("the record is gone at its expiry")
	}
	if _, ok, _ := s.Take(ctx, auth.KindSession, h); ok {
		t.Fatal("take of an expired record found it")
	}
}

func takeOnce(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindLogin, `{"s":1}`, clock.Now().Add(10*time.Minute))
	if _, ok, _ := s.Take(ctx, auth.KindLogin, h); !ok {
		t.Fatal("first take")
	}
	if _, ok, _ := s.Take(ctx, auth.KindLogin, h); ok {
		t.Fatal("second take found the record again")
	}
	if present(t, s, auth.KindLogin, h) {
		t.Fatal("a taken record can still be read")
	}
}

func concurrentTakes(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindLogin, `{}`, clock.Now().Add(10*time.Minute))
	var winners atomic.Int32
	var wg sync.WaitGroup
	gate := make(chan struct{})
	for range 16 {
		wg.Go(func() {
			<-gate
			if _, ok, err := s.Take(ctx, auth.KindLogin, h); ok && err == nil {
				winners.Add(1)
			}
		})
	}
	close(gate)
	wg.Wait()
	if winners.Load() != 1 {
		t.Fatalf("%d takers got the record, want exactly 1", winners.Load())
	}
}

func updateReplaces(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindSession, `{"n":1}`, clock.Now().Add(5*time.Minute))
	next, err := s.Update(ctx, auth.KindSession, h, []byte(`{"n":2}`), clock.Now().Add(30*time.Minute))
	if err != nil || next != h {
		t.Fatalf("update = %q %v, want the same handle", next, err)
	}
	clock.Advance(10 * time.Minute)
	data, ok, _ := s.Read(ctx, auth.KindSession, h)
	if !ok {
		t.Fatal("the updated expiry must apply")
	}
	sameJSON(t, data, `{"n":2}`)
}

func updateNotResurrecting(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindSession, `{"n":1}`, clock.Now().Add(5*time.Minute))
	_ = s.Delete(ctx, auth.KindSession, h)
	if _, err := s.Update(ctx, auth.KindSession, h, []byte(`{"n":2}`), clock.Now().Add(30*time.Minute)); err != nil {
		t.Fatal(err)
	}
	if present(t, s, auth.KindSession, h) {
		t.Fatal("update brought a deleted record back")
	}
}

func deleteRevokes(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	h := create(t, s, auth.KindSession, `{}`, clock.Now().Add(5*time.Minute))
	for range 2 {
		if err := s.Delete(ctx, auth.KindSession, h); err != nil {
			t.Fatal(err)
		}
	}
	if present(t, s, auth.KindSession, h) {
		t.Fatal("delete must revoke")
	}
	if !s.Revocable() {
		t.Fatal("a server store is revocable")
	}
}

func handlesUnique(t *testing.T, clock *fwtest.Clock, s auth.Store) {
	seen := map[string]bool{}
	for i := range 200 {
		h := create(t, s, auth.KindSession, `{}`, clock.Now().Add(time.Minute))
		if seen[h] {
			t.Fatalf("handle %q repeated at %d", h, i)
		}
		seen[h] = true
	}
}
