package auth_test

import (
	"context"
	"encoding/base64"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/storetest"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMemoryStoreMeetsTheContract(t *testing.T) {
	storetest.Run(t, func(_ *testing.T, clock *fwtest.Clock) auth.Store { return auth.NewMemoryStore(clock) })
}

func TestMemoryStoreRemovesExpiredRecordsEvery1024Creations(t *testing.T) {
	clock := fwtest.NewClock(storetest.Start)
	s := auth.NewMemoryStore(clock)
	ctx := context.Background()
	for range 1000 {
		if _, err := s.Create(ctx, auth.KindSession, []byte(`{}`), clock.Now().Add(time.Minute)); err != nil {
			t.Fatal(err)
		}
	}
	clock.Advance(2 * time.Minute)
	for range 24 {
		_, _ = s.Create(ctx, auth.KindSession, []byte(`{}`), clock.Now().Add(time.Hour))
	}
	if s.Size() != 24 {
		t.Fatalf("size %d after the sweep, want 24", s.Size())
	}
}

func cookieStore(t *testing.T, clock *fwtest.Clock, keys string) *auth.CookieStore {
	t.Helper()
	k, err := auth.ParseCookieKeys(keys)
	if err != nil {
		t.Fatal(err)
	}
	return auth.NewCookieStore(k, clock)
}

func newKey(t *testing.T, kid string) string {
	t.Helper()
	k, err := auth.GenerateCookieKey(kid)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func TestCookieStoreSealedValueRules(t *testing.T) {
	ctx := context.Background()
	clock := fwtest.NewClock(storetest.Start)
	in := func(d time.Duration) time.Time { return clock.Now().Add(d) }
	s := cookieStore(t, clock, newKey(t, "k1"))
	sealed, err := s.Create(ctx, auth.KindSession, []byte(`{"a":1}`), in(5*time.Minute))
	if err != nil {
		t.Fatal(err)
	}
	if !strings.HasPrefix(sealed, "v1.k1.") {
		t.Fatalf("format %q", sealed)
	}
	if _, ok, _ := s.Read(ctx, auth.KindSession, sealed); !ok {
		t.Fatal("a value reads back")
	}
	// sealed with another key under the same kid
	if _, ok, _ := cookieStore(t, clock, newKey(t, "k1")).Read(ctx, auth.KindSession, sealed); ok {
		t.Fatal("a value sealed with another key must be none")
	}
	// every changed byte
	parts := strings.Split(sealed, ".")
	raw, err := base64.RawURLEncoding.DecodeString(parts[2])
	if err != nil || len(raw) < 13 {
		t.Fatal("sealed value is not base64url")
	}
	for _, i := range []int{0, 11, 12, len(raw) / 2, len(raw) - 1} {
		c := append([]byte(nil), raw...)
		c[i] ^= 1
		changed := parts[0] + "." + parts[1] + "." + base64.RawURLEncoding.EncodeToString(c)
		if _, ok, _ := s.Read(ctx, auth.KindSession, changed); ok {
			t.Errorf("a change at byte %d was accepted", i)
		}
	}
	for _, bad := range []string{"v2." + parts[1] + "." + parts[2], parts[0] + ".k9." + parts[2], parts[0] + "." + parts[1] + ".AAAA", "garbage"} {
		if _, ok, _ := s.Read(ctx, auth.KindSession, bad); ok {
			t.Errorf("%q was accepted", bad)
		}
	}
	// a login record is not a session
	login, _ := s.Create(ctx, auth.KindLogin, []byte(`{"a":1}`), in(5*time.Minute))
	if _, ok, _ := s.Read(ctx, auth.KindSession, login); ok {
		t.Fatal("a login record replayed as a session must be none")
	}
	if _, ok, _ := s.Read(ctx, auth.KindLogin, login); !ok {
		t.Fatal("the login record reads as a login")
	}
	// expiry
	clock.Advance(5 * time.Minute)
	if _, ok, _ := s.Read(ctx, auth.KindSession, sealed); ok {
		t.Fatal("an expired value is none")
	}
}

func TestCookieStoreKeyRotation(t *testing.T) {
	ctx := context.Background()
	clock := fwtest.NewClock(storetest.Start)
	old, fresh := newKey(t, "old"), newKey(t, "new")
	sealedByOld, _ := cookieStore(t, clock, old).Create(ctx, auth.KindSession, []byte(`{"a":1}`), clock.Now().Add(5*time.Minute))
	rotated := cookieStore(t, clock, fresh+","+old)
	if _, ok, _ := rotated.Read(ctx, auth.KindSession, sealedByOld); !ok {
		t.Fatal("every listed key opens")
	}
	sealedByNew, _ := rotated.Create(ctx, auth.KindSession, []byte(`{}`), clock.Now().Add(5*time.Minute))
	if !strings.HasPrefix(sealedByNew, "v1.new.") {
		t.Fatalf("the first key seals: %q", sealedByNew)
	}
	if _, ok, _ := cookieStore(t, clock, fresh).Read(ctx, auth.KindSession, sealedByOld); ok {
		t.Fatal("a removed key no longer opens")
	}
}

func TestCookieStoreIsNotRevocableAndUpdateReseals(t *testing.T) {
	ctx := context.Background()
	clock := fwtest.NewClock(storetest.Start)
	s := cookieStore(t, clock, newKey(t, "k1"))
	sealed, _ := s.Create(ctx, auth.KindSession, []byte(`{"n":1}`), clock.Now().Add(5*time.Minute))
	next, err := s.Update(ctx, auth.KindSession, sealed, []byte(`{"n":2}`), clock.Now().Add(5*time.Minute))
	if err != nil || next == sealed {
		t.Fatalf("update must give a new handle: %v", err)
	}
	data, ok, _ := s.Read(ctx, auth.KindSession, next)
	if !ok || string(data) != `{"n":2}` {
		t.Fatalf("read after update: %s %v", data, ok)
	}
	_ = s.Delete(ctx, auth.KindSession, next)
	if _, ok, _ := s.Read(ctx, auth.KindSession, next); !ok {
		t.Fatal("delete cannot revoke a copy of the cookie")
	}
	if s.Revocable() {
		t.Fatal("the cookie store is not revocable")
	}
}

func TestCookieStoreRefusesARecordTooLargeForACookie(t *testing.T) {
	clock := fwtest.NewClock(storetest.Start)
	s := cookieStore(t, clock, newKey(t, "k1"))
	big := `{"roles":["` + strings.Repeat("r", 3_000) + `"]}`
	_, err := s.Create(context.Background(), auth.KindSession, []byte(big), clock.Now().Add(time.Minute))
	if err == nil || !strings.Contains(err.Error(), "database or redis") {
		t.Fatalf("err = %v", err)
	}
}

func TestBadCookieKeyListsFailWithoutShowingKeyMaterial(t *testing.T) {
	key := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	short := base64.RawURLEncoding.EncodeToString(make([]byte, 16))
	cases := []struct{ name, list, contains, notContains string }{
		{"empty", "", "", key},
		{"no kid", key, "", key},
		{"short key", "k1:" + short, "32 bytes", short},
		{"duplicate kid", "k1:" + key + ",k1:" + key, "twice", key},
		{"bad kid", "bad kid:" + key, "", key},
		{"not base64", "k1:not base64!", "", "not base64!"},
	}
	for _, c := range cases {
		_, err := auth.ParseCookieKeys(c.list)
		if err == nil {
			t.Errorf("%s: accepted", c.name)
			continue
		}
		if !strings.Contains(err.Error(), c.contains) || strings.Contains(err.Error(), c.notContains) {
			t.Errorf("%s: %q", c.name, err)
		}
	}
	k, err := auth.ParseCookieKeys(" a:" + key + " , b:" + key)
	if err != nil || strings.Join(k.Kids(), ",") != "a,b" || strings.Contains(k.String(), key) {
		t.Fatalf("%v %v", k, err)
	}
}
