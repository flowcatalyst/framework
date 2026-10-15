package ratelimit_test

import (
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

var epoch = time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC)

func memory(t *testing.T, clock *fwtest.Clock, limit int, period time.Duration, burst int) *ratelimit.Memory {
	t.Helper()
	m, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(limit, period, burst), ratelimit.MemoryClock(clock))
	if err != nil {
		t.Fatal(err)
	}
	return m
}

func acquire(t *testing.T, l ratelimit.Limiter, key string, cost int) ratelimit.Decision {
	t.Helper()
	d, err := l.Acquire(t.Context(), key, cost)
	if err != nil {
		t.Fatalf("Acquire(%q, %d): %v", key, cost, err)
	}
	return d
}

func acquireWith(t *testing.T, l ratelimit.PolicyLimiter, key string, cost int, p ratelimit.Policy) ratelimit.Decision {
	t.Helper()
	d, err := l.AcquireWith(t.Context(), key, cost, p)
	if err != nil {
		t.Fatalf("AcquireWith(%q, %d): %v", key, cost, err)
	}
	return d
}

func isAllowed(d ratelimit.Decision) bool { _, ok := d.(ratelimit.Allowed); return ok }

func TestABurstIsAllowedThenLimitedUntilBudgetComesBack(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 10, 10*time.Second, 3) // one request per second, burst 3
	s := time.Second
	for i, want := range []ratelimit.Decision{
		ratelimit.Allowed{Remaining: 2, ResetAfter: s},
		ratelimit.Allowed{Remaining: 1, ResetAfter: 2 * s},
		ratelimit.Allowed{Remaining: 0, ResetAfter: 3 * s},
		ratelimit.Limited{RetryAfter: s, ResetAfter: 3 * s},
	} {
		if got := acquire(t, l, "k", 1); got != want {
			t.Fatalf("request %d: got %v, want %v", i+1, got, want)
		}
	}
	clock.Advance(999 * time.Millisecond)
	if isAllowed(acquire(t, l, "k", 1)) {
		t.Fatal("999ms later: allowed, want limited")
	}
	clock.Advance(time.Millisecond)
	if got, want := acquire(t, l, "k", 1), (ratelimit.Allowed{Remaining: 0, ResetAfter: 3 * s}); got != want {
		t.Fatalf("1s later: got %v, want %v", got, want)
	}
	clock.Advance(10 * s)
	if got, want := acquire(t, l, "k", 1), (ratelimit.Allowed{Remaining: 2, ResetAfter: s}); got != want {
		t.Fatalf("after a full period: got %v, want %v", got, want)
	}
	if !isAllowed(acquire(t, l, "other", 1)) {
		t.Fatal("another key shares the budget")
	}
}

func TestACostedRequestUsesSeveralUnitsAndAFailedOneNone(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 10, 10*time.Second, 5)
	if got, want := acquire(t, l, "k", 4), (ratelimit.Allowed{Remaining: 1, ResetAfter: 4 * time.Second}); got != want {
		t.Fatalf("cost 4: got %v, want %v", got, want)
	}
	if isAllowed(acquire(t, l, "k", 2)) {
		t.Fatal("cost 2 with 1 left: allowed")
	}
	if got, want := acquire(t, l, "k", 1), (ratelimit.Allowed{Remaining: 0, ResetAfter: 5 * time.Second}); got != want {
		t.Fatalf("a failed request used budget: got %v, want %v", got, want)
	}
	for _, cost := range []int{0, 6, -1} {
		if _, err := l.Acquire(t.Context(), "k", cost); err == nil {
			t.Errorf("cost %d accepted", cost)
		}
	}
}

func TestPoliciesAreChecked(t *testing.T) {
	bad := []struct {
		name          string
		limit         int
		period        time.Duration
		burst         int
		wantInMessage string
	}{
		{"zero limit", 0, time.Second, 1, "limit"},
		{"limit too large", 1_000_001, 24 * time.Hour, 1, "limit"},
		{"zero period", 10, 0, 10, "period"},
		{"period too long", 10, 48 * time.Hour, 10, "period"},
		{"interval under a microsecond", 10_000, time.Millisecond, 10_000, "1 microsecond"},
		{"zero burst", 10, time.Second, 0, "burst"},
		{"window beyond 73 years", 1, 24 * time.Hour, 1_000_000, "73 years"},
	}
	for _, c := range bad {
		_, err := ratelimit.NewPolicy(c.limit, c.period, c.burst)
		if err == nil || !strings.Contains(err.Error(), c.wantInMessage) {
			t.Errorf("%s: err = %v, want one naming %q", c.name, err, c.wantInMessage)
		}
	}
	p, err := ratelimit.PolicyOf(5, time.Second)
	if err != nil || p.Burst() != 5 || p.Limit() != 5 || p.Period() != time.Second {
		t.Fatalf("PolicyOf(5, 1s) = %+v, %v", p, err)
	}
	// the edges are accepted
	for _, c := range [][3]int64{{1, int64(time.Millisecond), 1}, {1_000_000, int64(24 * time.Hour), 1}, {1000, int64(time.Millisecond), 1}} {
		if _, err := ratelimit.NewPolicy(int(c[0]), time.Duration(c[1]), int(c[2])); err != nil {
			t.Errorf("edge %v refused: %v", c, err)
		}
	}
	if !(ratelimit.Policy{}).IsZero() {
		t.Error("the zero Policy is not zero")
	}
}

func TestAWindowBeyondSeventyThreeYearsIsRefusedNotOverflowed(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 1, 24*time.Hour, 26_000) // 71 years
	if got, want := acquire(t, l, "k", 1), (ratelimit.Allowed{Remaining: 25_999, ResetAfter: 24 * time.Hour}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestConcurrentRequestsOnOneKeyNeverExceedTheBurst(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 1, 24*time.Hour, 10)
	var allowed atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for range 200 {
		wg.Go(func() {
			<-start
			if isAllowed(acquire(t, l, "hot", 1)) {
				allowed.Add(1)
			}
		})
	}
	close(start)
	wg.Wait()
	if allowed.Load() != 10 {
		t.Fatalf("allowed %d of 200, want exactly the burst, 10", allowed.Load())
	}
}

func TestAFloodOfKeysNeitherGrowsTheMapNorResetsExistingKeys(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(1, time.Minute, 1), ratelimit.MemoryClock(clock), ratelimit.MaxKeys(3))
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range []string{"victim", "a", "b"} {
		if !isAllowed(acquire(t, l, k, 1)) {
			t.Fatalf("%s limited at first use", k)
		}
	}
	// full: new keys share one overflow bucket (one allowed between them), the victim stays limited
	if !isAllowed(acquire(t, l, "new-1", 1)) {
		t.Fatal("the first overflow key is limited")
	}
	for i := 2; i < 1000; i++ {
		if isAllowed(acquire(t, l, fmt.Sprintf("new-%d", i), 1)) {
			t.Fatalf("overflow key new-%d allowed: the flood got its own bucket", i)
		}
	}
	if isAllowed(acquire(t, l, "victim", 1)) {
		t.Fatal("the flood reset the victim's limit")
	}
	if l.Size() > 4 {
		t.Fatalf("map holds %d keys, want at most 4", l.Size())
	}
	// once buckets are full again they are purged and new keys get their own
	clock.Advance(2 * time.Minute)
	if !isAllowed(acquire(t, l, "fresh", 1)) || isAllowed(acquire(t, l, "fresh", 1)) {
		t.Fatal("after the purge a new key has its own one-request bucket")
	}
	if l.Size() > 4 {
		t.Fatalf("map holds %d keys after the purge, want at most 4", l.Size())
	}
}

func TestThePurgeRunsAtMostOncePerSecondAndSparesBucketsStillInUse(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(1, time.Hour, 1), ratelimit.MemoryClock(clock), ratelimit.MaxKeys(2))
	if err != nil {
		t.Fatal(err)
	}
	acquire(t, l, "a", 1)
	acquire(t, l, "b", 1)
	// both buckets are still empty (an hour to refill): neither may be purged, so c overflows
	clock.Advance(10 * time.Minute)
	acquire(t, l, "c", 1)
	if l.Size() != 3 { // a, b and the overflow bucket
		t.Fatalf("size %d, want 3: a bucket still in use was purged", l.Size())
	}
	if isAllowed(acquire(t, l, "a", 1)) {
		t.Fatal("a was reset by a purge")
	}
}

func TestNamesAndLimitsAreChecked(t *testing.T) {
	p := ratelimit.MustPolicy(5, time.Second, 5)
	for _, ok := range []string{"api", "login_v2-x", "a", strings.Repeat("a", 63)} {
		if _, err := ratelimit.NewMemory(ok, p); err != nil {
			t.Errorf("name %q refused: %v", ok, err)
		}
	}
	for _, bad := range []string{"", "Api", "a:b", "1api", strings.Repeat("a", 64)} {
		if _, err := ratelimit.NewMemory(bad, p); err == nil {
			t.Errorf("name %q accepted", bad)
		}
	}
	for _, n := range []int{0, 10_000_001} {
		if _, err := ratelimit.NewMemory("api", p, ratelimit.MaxKeys(n)); err == nil {
			t.Errorf("MaxKeys(%d) accepted", n)
		}
	}
	if _, err := ratelimit.NewMemory("api", ratelimit.Policy{}); err == nil {
		t.Error("the zero Policy accepted")
	}
}

// --- per-request policies (contract section 3a) -----------------------------------------------

func TestARaisedPolicyAppliesFromTheKeysNextRequest(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 10, 10*time.Second, 3) // T 1 s, window 3 s
	for range 3 {
		if !isAllowed(acquire(t, l, "k", 1)) {
			t.Fatal("burst limited")
		}
	}
	// the default policy would make the caller wait 1 s; 10 per second, burst 30 (T 100 ms, window 3 s)
	raised := ratelimit.MustPolicy(10, time.Second, 30)
	if got, want := acquireWith(t, l, "k", 1, raised), (ratelimit.Limited{RetryAfter: 100 * time.Millisecond, ResetAfter: 3 * time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	clock.Advance(100 * time.Millisecond)
	if got, want := acquireWith(t, l, "k", 1, raised), (ratelimit.Allowed{Remaining: 0, ResetAfter: 3 * time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	clock.Advance(time.Second)
	if got, want := acquireWith(t, l, "k", 1, raised), (ratelimit.Allowed{Remaining: 9, ResetAfter: 2100 * time.Millisecond}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	// back on the default policy, the same stored state decides
	if got, want := acquire(t, l, "k", 1), (ratelimit.Limited{RetryAfter: 100 * time.Millisecond, ResetAfter: 2100 * time.Millisecond}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestARaisedRateWithASmallerWindowCapsTheDebtAtAnEmptyBucket(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 10, 10*time.Second, 3)
	for range 3 {
		acquire(t, l, "k", 1)
	}
	// 10 per second, burst 10: window 1 s; the 3 s debt is capped, retry after one new interval
	// (without the cap: 2.1 s)
	faster := ratelimit.MustPolicy(10, time.Second, 10)
	if got, want := acquireWith(t, l, "k", 1, faster), (ratelimit.Limited{RetryAfter: 100 * time.Millisecond, ResetAfter: time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
}

func TestALoweredPolicyCarriesUsageOverInProportionAndCapsItAtAnEmptyBucket(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l := memory(t, clock, 100, 10*time.Second, 100)                                         // T 100 ms, window 10 s
	if !isAllowed(acquire(t, l, "within", 50)) || !isAllowed(acquire(t, l, "beyond", 50)) { // debt 5 s each
		t.Fatal("setup")
	}
	// 1 per second, burst 10 (window 10 s): half the window was used, so 5 of 10, 4 left after this
	if got, want := acquireWith(t, l, "within", 1, ratelimit.MustPolicy(1, time.Second, 10)), (ratelimit.Allowed{Remaining: 4, ResetAfter: 6 * time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	// 1 per second, burst 2 (window 2 s): the 5 s debt is beyond it, capped to empty; the wait is
	// one new interval, not the 4 s the old debt would impose
	lower := ratelimit.MustPolicy(1, time.Second, 2)
	if got, want := acquireWith(t, l, "beyond", 1, lower), (ratelimit.Limited{RetryAfter: time.Second, ResetAfter: 2 * time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	// the capped value was written back, so the reported retry holds
	clock.Advance(time.Second)
	if got, want := acquireWith(t, l, "beyond", 1, lower), (ratelimit.Allowed{Remaining: 0, ResetAfter: 2 * time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	// from here the new policy holds
	if got, want := acquireWith(t, l, "beyond", 1, lower), (ratelimit.Limited{RetryAfter: time.Second, ResetAfter: 2 * time.Second}); got != want {
		t.Fatalf("got %v, want %v", got, want)
	}
	if _, err := l.AcquireWith(t.Context(), "beyond", 3, lower); err == nil {
		t.Fatal("cost above the given policy's burst accepted")
	}
	if _, err := l.AcquireWith(t.Context(), "beyond", 1, ratelimit.Policy{}); err == nil {
		t.Fatal("the zero Policy accepted")
	}
}

func TestThePurgeKeepsABucketUntilItIsFullAgainAndRunsOncePerSecond(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(10, time.Second, 1), ratelimit.MemoryClock(clock), ratelimit.MaxKeys(2))
	if err != nil {
		t.Fatal(err)
	}
	acquire(t, l, "a", 1) // each bucket is empty for 100 ms
	acquire(t, l, "b", 1)
	acquire(t, l, "c", 1) // full: purge (nothing to purge), c shares the overflow bucket
	if l.Size() != 3 {
		t.Fatalf("size %d, want a, b and the overflow bucket", l.Size())
	}
	clock.Advance(200 * time.Millisecond) // a and b are full again, but the purge ran under a second ago
	acquire(t, l, "d", 1)
	if l.Size() != 3 {
		t.Fatalf("size %d: the purge ran twice within a second", l.Size())
	}
	clock.Advance(time.Second) // a second after the first purge
	acquire(t, l, "d", 1)
	if l.Size() != 2 { // d and the overflow bucket
		t.Fatalf("size %d, want 2 after the purge", l.Size())
	}
}

func TestABucketThatIsFullAgainExactlyNowIsPurged(t *testing.T) {
	clock := fwtest.NewClock(epoch)
	l, err := ratelimit.NewMemory("api", ratelimit.MustPolicy(1, time.Minute, 1), ratelimit.MemoryClock(clock), ratelimit.MaxKeys(2))
	if err != nil {
		t.Fatal(err)
	}
	acquire(t, l, "a", 1)
	acquire(t, l, "b", 1)
	clock.Advance(time.Minute) // the stored times equal now
	acquire(t, l, "c", 1)
	if l.Size() != 1 {
		t.Fatalf("size %d, want only c", l.Size())
	}
}
