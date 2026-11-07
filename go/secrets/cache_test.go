package secrets_test

import (
	"context"
	"errors"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/secrets"
)

// scripted is a store whose answers the test sets; it knows the alias acme = clt_0ACME once it has
// answered for either (as the platform driver learns it from an answer).
type scripted struct {
	calls atomic.Int32
	gate  chan struct{} // when set, Find waits for it

	mu      sync.Mutex
	result  secrets.Result
	err     error
	learned bool
	silent  bool   // when set, an answer teaches no alias
	during  func() // when set, runs inside Find (time passing during the call)
}

func (s *scripted) set(r secrets.Result, err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.result, s.err = r, err
}

func (s *scripted) Find(_ context.Context, _ fw.ClientID, _ string) (secrets.Result, error) {
	s.calls.Add(1)
	if s.gate != nil {
		<-s.gate
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.during != nil {
		s.during()
	}
	if !s.silent {
		s.learned = true
	}
	return s.result, s.err
}

func (s *scripted) Canonical(c fw.ClientID) fw.ClientID {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.learned && c == acme {
		return fw.MustClientID("clt_0ACME")
	}
	return c
}

func (*scripted) Evict(fw.ClientID, string) {}
func (*scripted) EvictAll()                 {}

type scriptedFactory struct {
	s *scripted
	p secrets.CachePolicy
}

func (f scriptedFactory) Create(_ context.Context, _ string, b *fw.Boot) (secrets.Store, error) {
	return secrets.Cached(f.s, f.p, b), nil
}

func found(v string) secrets.Result { return secrets.Found{Secret: config.NewSecret(v)} }

func startScripted(t *testing.T, s *scripted, p secrets.CachePolicy) (*secrets.Resolver, *syncBuffer, *fwtest.Clock) {
	t.Helper()
	clock := fwtest.NewClock(time.Date(2026, 11, 7, 12, 0, 0, 0, time.UTC))
	res, logs, err := build(t, nil, clock, "", secrets.WithStore("scripted", scriptedFactory{s: s, p: p}))
	if err != nil {
		t.Fatal(err)
	}
	return res, logs, clock
}

var ref = secrets.MustParseRef("ftp-password")

func TestAFailingStoreServesTheLastValueForStaleIfErrorAndNeverAfterNotFound(t *testing.T) {
	t.Parallel()
	s := &scripted{}
	s.set(found(value), nil)
	res, logs, clock := startScripted(t, s, mustPolicy(t, time.Minute, 10).WithStaleIfError(5*time.Minute))
	ctx := t.Context()
	if got, err := res.Resolve(ctx, acme, ref); reveal(t, got, err) != value {
		t.Fatal("first")
	}
	s.set(nil, secrets.NewStoreError("secret store 'scripted': the platform could not be read"))
	clock.Advance(2 * time.Minute) // expired, within the stale window
	got, err := res.Resolve(ctx, acme, ref)
	if reveal(t, got, err) != value {
		t.Fatal("the last value was not served while the store fails")
	}
	var warning string
	for line := range strings.SplitSeq(logs.String(), "\n") {
		if strings.Contains(line, "level=WARN") {
			warning = line
		}
	}
	if !strings.Contains(warning, "ftp-password") || !strings.Contains(warning, "acme") || !strings.Contains(warning, "scripted") ||
		!strings.Contains(warning, "could not be read") || strings.Contains(logs.String(), value) {
		t.Errorf("the warning: %q", warning)
	}
	if n := s.calls.Load(); n != 2 {
		t.Errorf("the store was asked %d times; an expired entry is asked again before the stale value is served", n)
	}
	clock.Advance(5 * time.Minute) // past the stale window
	if _, err := res.Resolve(ctx, acme, ref); !errors.As(err, new(*secrets.StoreError)) {
		t.Fatalf("past the window: %v", err)
	}
	// a NotFound drops the entry: a later failure has nothing stale to serve
	s.set(found(value), nil)
	clock.Advance(time.Hour)
	_, _ = res.Resolve(ctx, acme, ref)
	clock.Advance(2 * time.Minute)
	s.set(secrets.NotFound{}, nil)
	if got, err := res.Resolve(ctx, acme, ref); err != nil || got != (secrets.NotFound{}) {
		t.Fatalf("not found: %v %v", got, err)
	}
	s.set(nil, secrets.NewStoreError("down"))
	if _, err := res.Resolve(ctx, acme, ref); err == nil {
		t.Fatal("a value was served stale after the store said it was not found")
	}
	// any other error is not a store failure and is never answered from the cache
	s.set(found(value), nil)
	clock.Advance(time.Hour)
	_, _ = res.Resolve(ctx, acme, ref)
	clock.Advance(2 * time.Minute)
	s.set(nil, context.Canceled)
	if _, err := res.Resolve(ctx, acme, ref); !errors.Is(err, context.Canceled) {
		t.Fatalf("a cancelled lookup: %v", err)
	}
}

func TestStaleIfErrorZeroServesNothingStale(t *testing.T) {
	t.Parallel()
	s := &scripted{}
	s.set(found(value), nil)
	res, _, clock := startScripted(t, s, mustPolicy(t, time.Minute, 10).WithStaleIfError(0))
	_, _ = res.Resolve(t.Context(), acme, ref)
	s.set(nil, secrets.NewStoreError("down"))
	clock.Advance(2 * time.Minute)
	if _, err := res.Resolve(t.Context(), acme, ref); err == nil {
		t.Fatal("served stale with the window off")
	}
}

func TestConcurrentMissesMakeOneCallToTheStore(t *testing.T) {
	t.Parallel()
	s := &scripted{gate: make(chan struct{})}
	s.set(found(value), nil)
	res, _, _ := startScripted(t, s, secrets.DefaultCache())
	var wg sync.WaitGroup
	results := make(chan string, 4)
	for range 4 {
		wg.Go(func() {
			got, err := res.Resolve(t.Context(), globex, ref)
			if err != nil {
				results <- err.Error()
				return
			}
			results <- reveal(t, got, nil)
		})
	}
	time.Sleep(200 * time.Millisecond) // every lookup is waiting
	close(s.gate)
	wg.Wait()
	close(results)
	for r := range results {
		if r != value {
			t.Errorf("a lookup got %q", r)
		}
	}
	if n := s.calls.Load(); n != 1 {
		t.Errorf("the store was asked %d times", n)
	}
}

func TestAnAliasAndItsCanonicalClientShareOneEntryAndEvictionWorksUnderEither(t *testing.T) {
	t.Parallel()
	s := &scripted{}
	s.set(found(value), nil)
	res, _, _ := startScripted(t, s, secrets.DefaultCache())
	ctx := t.Context()
	canonical := fw.MustClientID("clt_0ACME")
	for _, c := range []fw.ClientID{acme, canonical, acme} {
		if got, err := res.Resolve(ctx, c, ref); reveal(t, got, err) != value {
			t.Fatalf("%s", c)
		}
	}
	if n := s.calls.Load(); n != 1 {
		t.Fatalf("asked %d times for one secret under two names", n)
	}
	for _, evictAs := range []fw.ClientID{acme, canonical} {
		s.set(found("rotated-"+evictAs.String()), nil)
		if err := res.Evict(evictAs, ref); err != nil {
			t.Fatal(err)
		}
		for _, c := range []fw.ClientID{acme, canonical} {
			if got, err := res.Resolve(ctx, c, ref); reveal(t, got, err) != "rotated-"+evictAs.String() {
				t.Errorf("after evicting as %s, %s still reads the old value", evictAs, c)
			}
		}
	}
}

func TestCachePolicyStaleIfError(t *testing.T) {
	t.Parallel()
	if d := secrets.DefaultCache(); d.StaleIfError != 5*time.Minute {
		t.Errorf("default %+v", d)
	}
	if n := secrets.NoCache(); n.StaleIfError != 0 {
		t.Errorf("none %+v", n)
	}
	if p := mustPolicy(t, time.Minute, 1); p.StaleIfError != secrets.DefaultStaleIfError {
		t.Errorf("NewCachePolicy %+v", p)
	}
	for _, d := range []time.Duration{-1, 24*time.Hour + 1} {
		if err := mustPolicy(t, time.Minute, 1).WithStaleIfError(d).Validate(); err == nil {
			t.Errorf("%v accepted", d)
		}
	}
}

// An entry cached under a client's identifier before the store knew its stored id is still dropped
// when it is evicted under the stored id.
func TestAnEntryCachedBeforeTheAliasWasKnownIsEvictedUnderEitherName(t *testing.T) {
	t.Parallel()
	s := &scripted{silent: true}
	s.set(found(value), nil)
	res, _, _ := startScripted(t, s, secrets.DefaultCache())
	ctx := t.Context()
	_, _ = res.Resolve(ctx, acme, ref) // kept under "acme": no alias known yet
	s.mu.Lock()
	s.learned = true // learned since, from another answer
	s.mu.Unlock()
	s.set(found("rotated"), nil)
	if err := res.Evict(fw.MustClientID("clt_0ACME"), ref); err != nil {
		t.Fatal(err)
	}
	if got, err := res.Resolve(ctx, acme, ref); reveal(t, got, err) != "rotated" {
		t.Fatal("the entry kept under the identifier survived an eviction under the stored id")
	}
}

// The stale window is checked again after the store's call: time that passed during a failing call
// can end it.
func TestTheStaleWindowIsCheckedAfterTheFailingCall(t *testing.T) {
	t.Parallel()
	s := &scripted{}
	s.set(found(value), nil)
	res, _, clock := startScripted(t, s, mustPolicy(t, time.Minute, 10).WithStaleIfError(5*time.Minute))
	_, _ = res.Resolve(t.Context(), acme, ref)
	clock.Advance(5 * time.Minute) // expired, one minute of the window left
	s.set(nil, secrets.NewStoreError("down"))
	s.mu.Lock()
	s.during = func() { clock.Advance(2 * time.Minute) }
	s.mu.Unlock()
	if _, err := res.Resolve(t.Context(), acme, ref); err == nil {
		t.Fatal("served stale after the window ended during the call")
	}
}
