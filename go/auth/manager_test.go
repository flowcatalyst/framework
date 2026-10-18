package auth

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func newManagerFixture(t *testing.T, idle, absolute time.Duration) (*manager, *fwtest.Clock, *MemoryStore) {
	t.Helper()
	clock := fwtest.NewClock(start)
	store := NewMemoryStore(clock)
	m, err := newManager(store, clock, idle, absolute)
	if err != nil {
		t.Fatal(err)
	}
	return m, clock, store
}

func clerk() Principal {
	return Principal{
		ID: "prn_1", Kind: fw.PrincipalUser, Name: "Ada", Email: "ada@example.test", Tier: "CLIENT", HomeClient: "clt_1",
		Clients: []string{"clt_1:acme"}, Applications: []string{"app_1:orders"}, Roles: []string{"orders:clerk"},
		Scopes: []string{"must:not:be:stored"},
	}
}

func TestSessionRecordJSONIsTheContractsAndSurvivesTheRoundTrip(t *testing.T) {
	d := sessionData{
		principal: clerk(), authTime: start, createdAt: start.Add(3 * time.Second),
		lastSeenAt: start.Add(12 * time.Minute), absoluteUntil: start.Add(8 * time.Hour),
	}
	d.principal.Scopes = nil
	got := string(encodeSession(d))
	want := `{"v":1,"principal":{"id":"prn_1","kind":"USER","name":"Ada","email":"ada@example.test"},"tier":"CLIENT",` +
		`"homeClient":"clt_1","clients":["clt_1:acme"],"applications":["app_1:orders"],"roles":["orders:clerk"],` +
		`"authTime":"2026-10-13T08:00:00Z","createdAt":"2026-10-13T08:00:03Z","lastSeenAt":"2026-10-13T08:12:00Z",` +
		`"absoluteExpiresAt":"2026-10-13T16:00:00Z"}`
	if got != want {
		t.Fatalf("\n got %s\nwant %s", got, want)
	}
	back, ok := decodeSession([]byte(got))
	if !ok {
		t.Fatal("decode")
	}
	if id, _ := back.principal.HomeClientIdentifier(); id != "acme" || back.principal.Roles[0] != "orders:clerk" || !back.lastSeenAt.Equal(d.lastSeenAt) {
		t.Fatalf("%+v", back)
	}
}

func TestOptionalValuesAreOmittedNotNull(t *testing.T) {
	d := sessionData{principal: Principal{ID: "prn_2", Kind: fw.PrincipalService}, authTime: start, createdAt: start, lastSeenAt: start, absoluteUntil: start.Add(time.Hour)}
	got := string(encodeSession(d))
	for _, bad := range []string{"null", `"name"`, `"email"`, `"tier"`, `"homeClient"`} {
		if strings.Contains(got, bad) {
			t.Errorf("%s in %s", bad, got)
		}
	}
	if !strings.Contains(got, `"clients":[]`) || !strings.Contains(got, `"kind":"SERVICE"`) {
		t.Fatal(got)
	}
}

func TestADocumentFromTheJavaWriterIsRead(t *testing.T) {
	// fractional seconds, an unknown member and a non-text role, as the Java reader tolerates
	doc := `{"v":1,"extra":true,"principal":{"id":"prn_J","kind":"USER"},"clients":["*"],"applications":["*"],"roles":["a:b",7],` +
		`"authTime":"2026-10-13T08:00:00.500Z","createdAt":"2026-10-13T08:00:00.123456Z","lastSeenAt":"2026-10-13T08:00:00Z","absoluteExpiresAt":"2026-10-13T16:00:00Z"}`
	d, ok := decodeSession([]byte(doc))
	if !ok || d.principal.ID != "prn_J" || len(d.principal.Roles) != 1 || d.authTime.Nanosecond() != 500_000_000 {
		t.Fatalf("%+v %v", d, ok)
	}
	for _, bad := range []string{
		`{}`, `{"v":2}`, `not json`, `{"v":1,"principal":{"id":" "}}`,
		`{"v":1,"principal":{"id":"x"},"authTime":"yesterday"}`,
	} {
		if _, ok := decodeSession([]byte(bad)); ok {
			t.Errorf("%s was accepted", bad)
		}
	}
}

func TestLoginRecord(t *testing.T) {
	l := loginData{state: "S", nonce: "N", codeVerifier: "V", returnTo: "/orders?x=1", createdAt: start}
	got := string(encodeLogin(l))
	if got != `{"v":1,"state":"S","nonce":"N","codeVerifier":"V","returnTo":"/orders?x=1","createdAt":"2026-10-13T08:00:00Z"}` {
		t.Fatal(got)
	}
	back, ok := decodeLogin([]byte(got))
	if !ok || back != l {
		t.Fatalf("%+v %v", back, ok)
	}
	if _, ok := decodeLogin([]byte(strings.Replace(got, "/orders?x=1", "//evil.example", 1))); ok {
		t.Fatal("a stored returnTo is checked again")
	}
}

func TestIsSameOriginPath(t *testing.T) {
	good := []string{"/", "/orders", "/a?b=c#d", "/" + strings.Repeat("a", 2047)}
	bad := []string{"", "orders", "//evil.example", "/\\evil", "/a\nb", "/a\x7fb", "/a\x00", "https://evil.example/", "/" + strings.Repeat("a", 2048)}
	for _, g := range good {
		if !isSameOriginPath(g) {
			t.Errorf("%q should be accepted", g)
		}
	}
	for _, b := range bad {
		if isSameOriginPath(b) {
			t.Errorf("%q should be refused", b[:min(len(b), 20)])
		}
	}
}

func TestSessionLifetimes(t *testing.T) {
	ctx := context.Background()
	m, clock, _ := newManagerFixture(t, 30*time.Minute, 8*time.Hour)
	h, err := m.createSession(ctx, clerk(), start)
	if err != nil {
		t.Fatal(err)
	}
	// a read within the throttle window does not write
	clock.Advance(30 * time.Second)
	l, ok, err := m.session(ctx, h)
	if err != nil || !ok || !l.data.lastSeenAt.Equal(start) {
		t.Fatalf("no write-back under a minute: %+v %v %v", l.data.lastSeenAt, ok, err)
	}
	// at the minute lastSeenAt is written back
	clock.Advance(30 * time.Second)
	l, ok, _ = m.session(ctx, h)
	if !ok || !l.data.lastSeenAt.Equal(start.Add(time.Minute)) || !l.idleExpiresAt.Equal(start.Add(31*time.Minute)) {
		t.Fatalf("write-back at a minute: %+v", l)
	}
	// idle expiry deletes the record
	clock.Advance(30*time.Minute + time.Second)
	if _, ok, _ := m.session(ctx, h); ok {
		t.Fatal("idle expired")
	}
	if _, ok, _ := m.store.Read(ctx, KindSession, h); ok {
		t.Fatal("an expired session is deleted")
	}
}

func TestAbsoluteTimeoutIsNeverExtended(t *testing.T) {
	ctx := context.Background()
	m, clock, _ := newManagerFixture(t, 30*time.Minute, 2*time.Hour)
	h, _ := m.createSession(ctx, clerk(), start)
	for range 7 { // keep it busy: every 20 minutes for 140 minutes
		clock.Advance(20 * time.Minute)
		if clock.Now().Before(start.Add(2 * time.Hour)) {
			if _, ok, _ := m.session(ctx, h); !ok {
				t.Fatalf("a busy session is alive before its absolute expiry (%v)", clock.Now())
			}
		}
	}
	if _, ok, _ := m.session(ctx, h); ok {
		t.Fatal("past the absolute timeout the session is gone however busy")
	}
}

func TestTheStoreExpiryIsTheEarlierOfIdleAndAbsolute(t *testing.T) {
	ctx := context.Background()
	m, clock, store := newManagerFixture(t, 30*time.Minute, 10*time.Minute) // absolute shorter than idle
	if _, err := newManager(store, clock, time.Hour, 0); err == nil {
		t.Fatal("zero timeouts are refused")
	}
	h, _ := m.createSession(ctx, clerk(), start)
	clock.Advance(10 * time.Minute)
	if _, ok, _ := store.Read(ctx, KindSession, h); ok {
		t.Fatal("the store dropped it at the absolute expiry")
	}
}

func TestLoginRecordCompletesOnce(t *testing.T) {
	ctx := context.Background()
	m, clock, _ := newManagerFixture(t, time.Hour, 2*time.Hour)
	l := loginData{state: random(32), nonce: random(32), codeVerifier: random(32), returnTo: "/", createdAt: clock.Now()}
	h, err := m.createLogin(ctx, l)
	if err != nil {
		t.Fatal(err)
	}
	if got, ok, _ := m.takeLogin(ctx, h); !ok || got != l {
		t.Fatalf("take: %+v %v", got, ok)
	}
	if _, ok, _ := m.takeLogin(ctx, h); ok {
		t.Fatal("a callback can complete once")
	}
	h2, _ := m.createLogin(ctx, l)
	clock.Advance(10 * time.Minute)
	if _, ok, _ := m.takeLogin(ctx, h2); ok {
		t.Fatal("a login record lives ten minutes")
	}
}

func TestTouchAfterIsTheSmallerOfAMinuteAndHalfTheIdleTimeout(t *testing.T) {
	m, _, _ := newManagerFixture(t, 90*time.Second, time.Hour)
	if m.touchAfter != 45*time.Second {
		t.Fatalf("touchAfter %v", m.touchAfter)
	}
	m, _, _ = newManagerFixture(t, time.Hour, 2*time.Hour)
	if m.touchAfter != time.Minute {
		t.Fatalf("touchAfter %v", m.touchAfter)
	}
}
