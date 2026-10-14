// Package lockstest is the locks contract's test suite (locks contract section 6): every driver's
// tests call Run with a harness, and the suite checks every guarantee of section 3 and the With
// rules of section 4, one subtest per case.
package lockstest

import (
	"context"
	"errors"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
)

// Harness supplies the driver under test.
type Harness struct {
	// New returns a lock over a fresh store for one test, with its hooks.
	New func(t *testing.T) Env
}

// Env is one test's driver.
type Env struct {
	// Lock is the lock API over the store under test.
	Lock *locks.Lock
	// Expire ends the key's current lease at once in the store itself (move the clock, set
	// expires_at in the past, DEL the key), so loss is tested without waiting.
	Expire func(t *testing.T, key locks.Key)
	// Pass lets d pass on the store's clock: moves the clock for memory, sleeps for real stores.
	Pass func(t *testing.T, d time.Duration)
}

// OffsetClock is a clock for the memory driver's tests: real time plus an offset that Pass moves,
// so leases expire when a test says and refreshers using real timers still work.
type OffsetClock struct {
	mu     sync.Mutex
	offset time.Duration
}

// Now returns the system time plus the offset.
func (c *OffsetClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return time.Now().Add(c.offset) //nolint:forbidigo // a test clock built on the system clock
}

// Advance moves the clock forward.
func (c *OffsetClock) Advance(d time.Duration) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.offset += d
}

var _ fw.Clock = (*OffsetClock)(nil)

func key(t *testing.T, name string) locks.Key {
	t.Helper()
	k, err := locks.ServiceKey(name)
	if err != nil {
		t.Fatal(err)
	}
	return k
}

func mustAcquire(t *testing.T, l *locks.Lock, k locks.Key, lease time.Duration) locks.Lease {
	t.Helper()
	res, err := l.TryAcquire(t.Context(), k, lease)
	if err != nil {
		t.Fatalf("TryAcquire(%s): %v", k, err)
	}
	a, ok := res.(locks.Acquired)
	if !ok {
		t.Fatalf("TryAcquire(%s): got %T, want Acquired", k, res)
	}
	return a.Lease
}

func mustBeBusy(t *testing.T, l *locks.Lock, k locks.Key) {
	t.Helper()
	res, err := l.TryAcquire(t.Context(), k, time.Second)
	if err != nil {
		t.Fatalf("TryAcquire(%s): %v", k, err)
	}
	if _, ok := res.(locks.Busy); !ok {
		t.Fatalf("TryAcquire(%s): got %T, want Busy", k, res)
	}
}

// Run runs every case of the suite against the harness.
func Run(t *testing.T, h Harness) {
	t.Helper()
	cases := []struct {
		name string
		run  func(t *testing.T, e Env)
	}{
		{"one of 50 racing goroutines acquires", oneOfManyAcquires},
		{"fencing tokens increase over release cycles", tokensIncreaseOverReleases},
		{"fencing tokens increase over expiry cycles", tokensIncreaseOverExpiry},
		{"refresh extends the lease", refreshExtends},
		{"refresh and release after expiry are lost even when nobody took the key", lostAfterExpiry},
		{"a stale lease cannot release a newer holder's lock", staleCannotRelease},
		{"not reentrant", notReentrant},
		{"release is immediate", releaseIsImmediate},
		{"acquire waits for the holder's release", acquireWaitsForRelease},
		{"acquire returns Busy when the wait runs out", acquireWaitRunsOut},
		{"service and client keys do not collide, nor do two clients", keysDoNotCollide},
		{"With keeps the lock beyond its lease by refreshing", withRefreshes},
		{"With reports Lost and cancels the action when the lease is taken", withLost},
		{"With releases after an error and returns it", withError},
		{"With does not run the action when the lock is held", withNotAcquired},
		{"argument limits", argumentLimits},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			c.run(t, h.New(t))
		})
	}
}

func oneOfManyAcquires(t *testing.T, e Env) {
	k := key(t, "race")
	var acquired atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for range 50 {
		wg.Go(func() {
			<-start
			res, err := e.Lock.TryAcquire(t.Context(), k, 10*time.Second)
			if err != nil {
				t.Error(err)
				return
			}
			if _, ok := res.(locks.Acquired); ok {
				acquired.Add(1)
			}
		})
	}
	close(start)
	wg.Wait()
	if n := acquired.Load(); n != 1 {
		t.Fatalf("%d of 50 acquired, want exactly 1", n)
	}
}

func tokensIncreaseOverReleases(t *testing.T, e Env) {
	k := key(t, "cycles")
	var last int64 = -1 << 62
	for i := range 20 {
		l := mustAcquire(t, e.Lock, k, 10*time.Second)
		if l.Fence() <= last {
			t.Fatalf("cycle %d: token %d not above %d", i, l.Fence(), last)
		}
		last = l.Fence()
		if ok, err := e.Lock.Release(t.Context(), l); err != nil || !ok {
			t.Fatalf("cycle %d: release: %v %v", i, ok, err)
		}
	}
}

func tokensIncreaseOverExpiry(t *testing.T, e Env) {
	k := key(t, "expiry-cycles")
	var last int64 = -1 << 62
	for i := range 20 {
		l := mustAcquire(t, e.Lock, k, 10*time.Second) // after expiry: acquired at once
		if l.Fence() <= last {
			t.Fatalf("cycle %d: token %d not above %d", i, l.Fence(), last)
		}
		last = l.Fence()
		e.Expire(t, k)
	}
}

func refreshExtends(t *testing.T, e Env) {
	k := key(t, "refresh")
	l := mustAcquire(t, e.Lock, k, 300*time.Millisecond)
	e.Pass(t, 200*time.Millisecond)
	l2, ok, err := e.Lock.Refresh(t.Context(), l, 300*time.Millisecond)
	if err != nil || !ok {
		t.Fatalf("refresh: %v %v", ok, err)
	}
	if l2.Fence() != l.Fence() {
		t.Errorf("refresh changed the token: %d -> %d", l.Fence(), l2.Fence())
	}
	if !l2.Deadline().After(l.Deadline()) {
		t.Errorf("refresh did not move the local deadline")
	}
	e.Pass(t, 200*time.Millisecond) // past the first lease, within the refreshed one
	mustBeBusy(t, e.Lock, k)
}

func lostAfterExpiry(t *testing.T, e Env) {
	k := key(t, "expired")
	l := mustAcquire(t, e.Lock, k, 10*time.Second)
	e.Expire(t, k)
	if _, ok, err := e.Lock.Refresh(t.Context(), l, time.Second); err != nil || ok {
		t.Errorf("refresh after expiry: ok=%v err=%v, want lost", ok, err)
	}
	if ok, err := e.Lock.Release(t.Context(), l); err != nil || ok {
		t.Errorf("release after expiry: ok=%v err=%v, want not held", ok, err)
	}
}

func staleCannotRelease(t *testing.T, e Env) {
	k := key(t, "stale")
	old := mustAcquire(t, e.Lock, k, 10*time.Second)
	e.Expire(t, k)
	newer := mustAcquire(t, e.Lock, k, 10*time.Second)
	if newer.Fence() <= old.Fence() {
		t.Fatalf("new token %d not above %d", newer.Fence(), old.Fence())
	}
	if ok, err := e.Lock.Release(t.Context(), old); err != nil || ok {
		t.Fatalf("stale release: ok=%v err=%v, want not held", ok, err)
	}
	mustBeBusy(t, e.Lock, k)
}

func notReentrant(t *testing.T, e Env) {
	k := key(t, "reentrant")
	mustAcquire(t, e.Lock, k, 10*time.Second)
	mustBeBusy(t, e.Lock, k)
}

func releaseIsImmediate(t *testing.T, e Env) {
	k := key(t, "release")
	l := mustAcquire(t, e.Lock, k, 10*time.Second)
	if ok, err := e.Lock.Release(t.Context(), l); err != nil || !ok {
		t.Fatalf("release: %v %v", ok, err)
	}
	mustAcquire(t, e.Lock, k, 10*time.Second)
}

func acquireWaitsForRelease(t *testing.T, e Env) {
	k := key(t, "wait")
	l := mustAcquire(t, e.Lock, k, 10*time.Second)
	go func() {
		time.Sleep(100 * time.Millisecond)
		_, _ = e.Lock.Release(context.WithoutCancel(t.Context()), l)
	}()
	res, err := e.Lock.Acquire(t.Context(), k, 10*time.Second, 5*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := res.(locks.Acquired); !ok {
		t.Fatalf("got %T, want Acquired after the release", res)
	}
}

func acquireWaitRunsOut(t *testing.T, e Env) {
	k := key(t, "wait-out")
	mustAcquire(t, e.Lock, k, 10*time.Second)
	begin := time.Now() //nolint:forbidigo // measuring the real wait
	res, err := e.Lock.Acquire(t.Context(), k, 10*time.Second, 150*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := res.(locks.Busy); !ok {
		t.Fatalf("got %T, want Busy", res)
	}
	if waited := time.Since(begin); waited < 140*time.Millisecond { //nolint:forbidigo // as above
		t.Errorf("returned Busy after %v, before the wait ran out", waited)
	}
}

func keysDoNotCollide(t *testing.T, e Env) {
	svc := key(t, "shared-name")
	a, err := locks.ClientKey(fw.MustClientID("client-a"), "shared-name")
	if err != nil {
		t.Fatal(err)
	}
	b, err := locks.ClientKey(fw.MustClientID("client-b"), "shared-name")
	if err != nil {
		t.Fatal(err)
	}
	mustAcquire(t, e.Lock, svc, 10*time.Second)
	mustAcquire(t, e.Lock, a, 10*time.Second)
	mustAcquire(t, e.Lock, b, 10*time.Second)
	if svc.Stored() != "svc:shared-name" || a.Stored() != "client:client-a:shared-name" {
		t.Errorf("stored forms: %s, %s", svc.Stored(), a.Stored())
	}
}

func withRefreshes(t *testing.T, e Env) {
	k := key(t, "with-refresh")
	var busyDuring atomic.Bool
	res, err := locks.With(t.Context(), e.Lock, k, 300*time.Millisecond, 0, func(ctx context.Context, lease locks.Lease) (int64, error) {
		time.Sleep(900 * time.Millisecond) // three lease lengths
		r, err := e.Lock.TryAcquire(ctx, k, time.Second)
		if err == nil {
			_, busy := r.(locks.Busy)
			busyDuring.Store(busy)
		}
		return lease.Fence(), err
	})
	if err != nil {
		t.Fatal(err)
	}
	if res.Outcome != locks.Completed || res.Value <= 0 {
		t.Fatalf("result %+v, want Completed with the token", res)
	}
	if !busyDuring.Load() {
		t.Error("the lock was not held after three lease lengths: it was not refreshed")
	}
	mustAcquire(t, e.Lock, k, time.Second) // released afterwards
}

func withLost(t *testing.T, e Env) {
	k := key(t, "with-lost")
	started := make(chan struct{})
	var cancelled atomic.Bool
	done := make(chan struct{})
	var res locks.WithResult[string]
	var err error
	go func() {
		defer close(done)
		res, err = locks.With(t.Context(), e.Lock, k, 300*time.Millisecond, 0, func(ctx context.Context, _ locks.Lease) (string, error) {
			close(started)
			select {
			case <-ctx.Done():
				cancelled.Store(true)
				return "", ctx.Err()
			case <-time.After(5 * time.Second):
				return "ran to the end", nil
			}
		})
	}()
	<-started
	e.Expire(t, k)
	mustAcquire(t, e.Lock, k, 10*time.Second) // another holder takes it
	<-done
	if err != nil {
		t.Fatalf("err %v, want none (the cancellation caused by the loss is not an error)", err)
	}
	if res.Outcome != locks.Lost {
		t.Fatalf("outcome %v, want Lost", res.Outcome)
	}
	if !cancelled.Load() {
		t.Error("the action's context was not cancelled")
	}
}

func withError(t *testing.T, e Env) {
	k := key(t, "with-error")
	boom := errors.New("boom")
	res, err := locks.With(t.Context(), e.Lock, k, 10*time.Second, 0, func(context.Context, locks.Lease) (int, error) {
		return 0, boom
	})
	if !errors.Is(err, boom) {
		t.Fatalf("err %v, want the action's error", err)
	}
	if res.Outcome != locks.Completed {
		t.Errorf("outcome %v, want Completed (held throughout)", res.Outcome)
	}
	mustAcquire(t, e.Lock, k, time.Second) // released after the error
}

func withNotAcquired(t *testing.T, e Env) {
	k := key(t, "with-held")
	mustAcquire(t, e.Lock, k, 10*time.Second)
	ran := false
	res, err := locks.With(t.Context(), e.Lock, k, time.Second, 0, func(context.Context, locks.Lease) (int, error) {
		ran = true
		return 1, nil
	})
	if err != nil || res.Outcome != locks.NotAcquired || ran {
		t.Fatalf("result %+v err %v ran %v, want NotAcquired without running", res, err, ran)
	}
}

func argumentLimits(t *testing.T, e Env) {
	k := key(t, "limits")
	ctx := t.Context()
	for _, lease := range []time.Duration{99 * time.Millisecond, 24*time.Hour + time.Millisecond, 0, -time.Second} {
		if _, err := e.Lock.TryAcquire(ctx, k, lease); !errors.Is(err, locks.ErrInvalidArgument) {
			t.Errorf("lease %v: err %v, want ErrInvalidArgument", lease, err)
		}
	}
	for _, wait := range []time.Duration{-time.Millisecond, 24*time.Hour + time.Millisecond} {
		if _, err := e.Lock.Acquire(ctx, k, time.Second, wait); !errors.Is(err, locks.ErrInvalidArgument) {
			t.Errorf("maxWait %v: err %v, want ErrInvalidArgument", wait, err)
		}
	}
	if _, err := e.Lock.TryAcquire(ctx, locks.Key{}, time.Second); !errors.Is(err, locks.ErrInvalidArgument) {
		t.Errorf("zero key: err %v", err)
	}
	for _, name := range []string{"", "has space", string(make([]byte, 201))} {
		if _, err := locks.ServiceKey(name); !errors.Is(err, locks.ErrInvalidArgument) {
			t.Errorf("name %q: err %v", name, err)
		}
	}
	other := locks.New(locks.NewMemoryStore(fw.SystemClock()))
	foreign := mustAcquire(t, other, k, time.Second)
	if _, err := e.Lock.Release(ctx, foreign); !errors.Is(err, locks.ErrInvalidArgument) {
		t.Errorf("a lease from another lock: err %v", err)
	}
	if _, _, err := e.Lock.Refresh(ctx, foreign, time.Second); !errors.Is(err, locks.ErrInvalidArgument) {
		t.Errorf("refresh of a lease from another lock: err %v", err)
	}
}
