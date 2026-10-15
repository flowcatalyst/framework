package redisrl_test

import (
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/ratelimit"
	"github.com/flowcatalyst/framework/go/ratelimit/redisrl"
	"github.com/flowcatalyst/framework/go/redisfw"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

func client(t *testing.T, url string) *redisfw.Client {
	t.Helper()
	c, err := redisfw.Open(t.Context(), "default", url)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c
}

func limiter(t *testing.T, r fwtest.Redis, name string, p ratelimit.Policy) (*redisrl.Limiter, *redisfw.Client) {
	t.Helper()
	c := client(t, r.URL)
	l, err := redisrl.FromClient(t.Context(), name, c, p, redisrl.Prefix(r.Prefix))
	if err != nil {
		t.Fatal(err)
	}
	return l, c
}

func acq(t *testing.T, l ratelimit.PolicyLimiter, key string, cost int, p *ratelimit.Policy) ratelimit.Decision {
	t.Helper()
	var d ratelimit.Decision
	var err error
	if p == nil {
		d, err = l.Acquire(t.Context(), key, cost)
	} else {
		d, err = l.AcquireWith(t.Context(), key, cost, *p)
	}
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func remaining(t *testing.T, d ratelimit.Decision) int64 {
	t.Helper()
	a, ok := d.(ratelimit.Allowed)
	if !ok {
		t.Fatalf("decision %v, want Allowed", d)
	}
	return a.Remaining
}

func TestTheScriptIsTheJavaScriptByteForByte(t *testing.T) {
	src := conformance.ModuleDir(t) + "/../framework-ratelimit-redis/src/main/java/io/flowcatalyst/framework/http/ratelimit/redis/RedisRateLimiter.java"
	b, err := os.ReadFile(filepath.Clean(src))
	if err != nil {
		t.Fatal(err)
	}
	m := regexp.MustCompile(`(?s)SCRIPT =\s*"""\n(.*?)"""`).FindSubmatch(b)
	if m == nil {
		t.Fatal("the SCRIPT text block was not found in RedisRateLimiter.java")
	}
	lines := strings.Split(string(m[1]), "\n")
	indent := 1 << 30
	for _, l := range lines {
		if strings.TrimSpace(l) != "" {
			indent = min(indent, len(l)-len(strings.TrimLeft(l, " ")))
		}
	}
	for i, l := range lines {
		lines[i] = strings.TrimRight(strings.TrimPrefix(l, strings.Repeat(" ", indent)), " ")
	}
	conformance.EqualBytes(t, "ratelimit gcra.lua vs RedisRateLimiter.SCRIPT", []byte(strings.Join(lines, "\n")), []byte(redisrl.Script()))
}

func TestABurstIsAllowedThenLimitedWithTheServersArithmetic(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	l, c := limiter(t, r, "login", ratelimit.MustPolicy(3, time.Minute, 3))
	for want := int64(2); want >= 0; want-- {
		if got := remaining(t, acq(t, l, "ip:1.2.3.4", 1, nil)); got != want {
			t.Fatalf("remaining %d, want %d", got, want)
		}
	}
	lim, ok := acq(t, l, "ip:1.2.3.4", 1, nil).(ratelimit.Limited)
	if !ok || lim.RetryAfter <= 19*time.Second || lim.RetryAfter > 20*time.Second {
		t.Fatalf("limited = %+v, want a retry in (19s, 20s]", lim)
	}
	// the key lives under the prefix and the limiter name, and expires when the bucket is full
	ttl := c.Redis().PTTL(t.Context(), r.Prefix+"login:ip:1.2.3.4").Val()
	if ttl < 59*time.Second || ttl > 60*time.Second+2*time.Millisecond {
		t.Fatalf("ttl %v", ttl)
	}
	if _, ok := acq(t, l, "ip:5.6.7.8", 1, nil).(ratelimit.Allowed); !ok {
		t.Fatal("another key shares the budget")
	}
}

func TestReplicasShareOneBudget(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	p := ratelimit.MustPolicy(10, time.Hour, 10)
	a, _ := limiter(t, r, "login", p)
	b, _ := limiter(t, r, "login", p)
	var allowed atomic.Int32
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range 60 {
		l := a
		if i%2 == 1 {
			l = b
		}
		wg.Go(func() {
			<-start
			if _, ok := acq(t, l, "shared", 1, nil).(ratelimit.Allowed); ok {
				allowed.Add(1)
			}
		})
	}
	close(start)
	wg.Wait()
	if allowed.Load() != 10 {
		t.Fatalf("allowed %d across two replicas, want the shared budget, 10", allowed.Load())
	}
}

func TestAForgottenScriptIsLoadedAgain(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	l, c := limiter(t, r, "login", ratelimit.MustPolicy(5, time.Minute, 5))
	acq(t, l, "k", 1, nil)
	if err := c.Redis().ScriptFlush(t.Context()).Err(); err != nil {
		t.Fatal(err)
	}
	if _, ok := acq(t, l, "k", 1, nil).(ratelimit.Allowed); !ok {
		t.Fatal("after SCRIPT FLUSH the request was not allowed")
	}
}

func TestAStoreThatGoesAwayIsAStoreError(t *testing.T) {
	r := fwtest.NewRedis(t)
	c := client(t, r.URL)
	l, err := redisrl.FromClient(t.Context(), "login", c, ratelimit.MustPolicy(5, time.Minute, 5), redisrl.Prefix(r.Prefix))
	if err != nil {
		t.Fatal(err)
	}
	acq(t, l, "k", 1, nil)
	_ = c.Close() // the connection is gone
	if _, err := l.Acquire(t.Context(), "k", 1); err == nil || !errors.Is(err, ratelimit.ErrStore) {
		t.Fatalf("err = %v, want one wrapping ErrStore", err)
	}
}

func TestAKeysPolicyChangesFromItsNextRequestOnTheServersClock(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	// 3 per minute: T 20 s, window 60 s
	l, c := limiter(t, r, "ingest", ratelimit.MustPolicy(3, time.Minute, 3))
	for _, key := range []string{"raised", "lowered"} {
		for range 3 {
			remaining(t, acq(t, l, key, 1, nil))
		}
		if _, ok := acq(t, l, key, 1, nil).(ratelimit.Limited); !ok {
			t.Fatalf("%s: fourth request allowed", key)
		}
	}
	// raised to 60 per minute, burst 120 (T 1 s, window 120 s): the 60 s debt is kept, about 59 left
	raisedPolicy := ratelimit.MustPolicy(60, time.Minute, 120)
	if got := remaining(t, acq(t, l, "raised", 1, &raisedPolicy)); got < 58 || got > 59 {
		t.Fatalf("raised: remaining %d, want 58 or 59", got)
	}
	// lowered window, 1 per 10 s, burst 1 (window 10 s): the debt is capped at an empty bucket, so
	// the retry is one new interval (without the cap, about 60 s)
	lowered := ratelimit.MustPolicy(1, 10*time.Second, 1)
	lim, ok := acq(t, l, "lowered", 1, &lowered).(ratelimit.Limited)
	if !ok || lim.RetryAfter <= 9*time.Second || lim.RetryAfter > 10*time.Second {
		t.Fatalf("lowered: %+v, want a retry in (9s, 10s]", lim)
	}
	// the capped value was written back, with a TTL to match
	ttl := c.Redis().PTTL(t.Context(), r.Prefix+"ingest:lowered").Val()
	if ttl < 9*time.Second || ttl > 10*time.Second+2*time.Millisecond {
		t.Fatalf("ttl after the cap %v", ttl)
	}
	// a policy whose window is under a second: retry well under a second, from the same state
	fast := ratelimit.MustPolicy(3, time.Second, 3)
	lim, ok = acq(t, l, "lowered", 1, &fast).(ratelimit.Limited)
	if !ok || lim.RetryAfter > 334*time.Millisecond {
		t.Fatalf("fast: %+v", lim)
	}
}

func TestNamesAndCostsAreChecked(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	p := ratelimit.MustPolicy(1, time.Second, 1)
	c := client(t, r.URL)
	if l, err := redisrl.FromClient(t.Context(), "login", c, p); err != nil || l.Name() != "login" || l.Policy() != p {
		t.Fatalf("login: %v", err)
	}
	for _, bad := range []string{"", "Login", "a:b"} {
		if _, err := redisrl.FromClient(t.Context(), bad, c, p); err == nil {
			t.Errorf("name %q accepted", bad)
		}
	}
	l, _ := limiter(t, r, "costs", ratelimit.MustPolicy(5, time.Minute, 5))
	for _, cost := range []int{0, 6} {
		if _, err := l.Acquire(t.Context(), "k", cost); err == nil {
			t.Errorf("cost %d accepted", cost)
		}
	}
}

func TestDefaultPrefixIsTheJavaOne(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	c := client(t, r.URL)
	l, err := redisrl.FromClient(t.Context(), "pfx", c, ratelimit.MustPolicy(2, time.Minute, 2))
	if err != nil {
		t.Fatal(err)
	}
	acq(t, l, "k", 1, nil)
	defer c.Redis().Del(t.Context(), "fw:rl:pfx:k")
	if n := c.Redis().Exists(t.Context(), "fw:rl:pfx:k").Val(); n != 1 {
		t.Fatalf("key fw:rl:pfx:k not found: the default prefix is not fw:rl:")
	}
}
