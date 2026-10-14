package redislock_test

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/lockstest"
	"github.com/flowcatalyst/framework/go/locks/redislock"
	"github.com/flowcatalyst/framework/go/redisfw"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

func TestRedisDriverContract(t *testing.T) {
	t.Parallel()
	lockstest.Run(t, lockstest.Harness{New: func(t *testing.T) lockstest.Env {
		r := fwtest.NewRedis(t)
		c, err := redisfw.Open(t.Context(), "default", r.URL)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = c.Close() })
		store, err := redislock.NewStore(t.Context(), c.Redis(), r.Prefix)
		if err != nil {
			t.Fatal(err)
		}
		return lockstest.Env{
			Lock: locks.New(store),
			Expire: func(t *testing.T, k locks.Key) {
				if err := store.ExpireNow(t.Context(), k.Stored()); err != nil {
					t.Fatal(err)
				}
			},
			Pass: func(_ *testing.T, d time.Duration) { time.Sleep(d) },
		}
	}})
}

func TestKeysAndValueFormat(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	c, err := redisfw.Open(t.Context(), "default", r.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = c.Close() }()
	store, err := redislock.NewStore(t.Context(), c.Redis(), r.Prefix)
	if err != nil {
		t.Fatal(err)
	}
	fence, ok, err := store.TryAcquire(t.Context(), "svc:fmt", "owner1", 5000)
	if err != nil || !ok {
		t.Fatalf("acquire: %v %v", ok, err)
	}
	v, err := c.Redis().Get(t.Context(), r.Prefix+"svc:fmt").Result()
	if err != nil {
		t.Fatal(err)
	}
	if want := "owner1:" + itoa(fence); v != want {
		t.Errorf("lock value %q, want %q", v, want)
	}
	ttl, err := c.Redis().PTTL(t.Context(), r.Prefix+"svc:fmt").Result()
	if err != nil || ttl <= 0 || ttl > 5*time.Second {
		t.Errorf("PTTL %v %v", ttl, err)
	}
	counter, err := c.Redis().Get(t.Context(), r.Prefix+"__fence").Int64()
	if err != nil || counter != fence {
		t.Errorf("counter %d %v, want %d", counter, err, fence)
	}
	// NOSCRIPT: flush the script cache; the next call still works.
	if err := c.Redis().ScriptFlush(t.Context()).Err(); err != nil {
		t.Fatal(err)
	}
	if ok, err := store.Release(t.Context(), "svc:fmt", "owner1", fence); err != nil || !ok {
		t.Fatalf("release after SCRIPT FLUSH: %v %v", ok, err)
	}
	_ = c.Close()
	if _, _, err := store.TryAcquire(t.Context(), "svc:x", "o", 1000); !errors.Is(err, locks.ErrStoreUnavailable) {
		t.Errorf("closed client: err %v, want ErrStoreUnavailable", err)
	}
}

func TestStoreOptionsAreChecked(t *testing.T) {
	t.Parallel()
	for name, c := range map[string]struct {
		opts []redislock.Option
		want string
	}{
		"blank prefix":    {[]redislock.Option{redislock.Prefix("  ")}, "must not be blank"},
		"long prefix":     {[]redislock.Option{redislock.Prefix(strings.Repeat("x", 101))}, "at most 100 characters"},
		"default prefix":  {nil, "no redis instance is configured"},
		"max prefix":      {[]redislock.Option{redislock.Prefix(strings.Repeat("x", 100))}, "no redis instance is configured"},
		"unknown connect": {[]redislock.Option{redislock.Connection("nope")}, "no Redis instance named 'nope'"},
	} {
		_, err := redislock.Store(&redisfw.Clients{}, c.opts...)(t.Context(), nil)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: %v, want %q", name, err, c.want)
		}
	}
}
