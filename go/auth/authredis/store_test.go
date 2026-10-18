package authredis_test

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authredis"
	"github.com/flowcatalyst/framework/go/auth/storetest"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/redisfw"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

func open(t *testing.T) (*redisfw.Client, string) {
	t.Helper()
	r := fwtest.NewRedis(t)
	c, err := redisfw.Open(t.Context(), "default", r.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return c, r.Prefix
}

func TestRedisStoreMeetsTheContract(t *testing.T) {
	t.Parallel()
	storetest.Run(t, func(t *testing.T, clock *fwtest.Clock) auth.Store {
		c, prefix := open(t)
		return authredis.NewStore(c.Redis(), clock).WithPrefix(prefix + authredis.Prefix)
	})
}

func TestKeyAndValueFormat(t *testing.T) {
	t.Parallel()
	c, prefix := open(t)
	clock := fwtest.NewClock(storetest.Start)
	s := authredis.NewStore(c.Redis(), clock).WithPrefix(prefix + authredis.Prefix)
	exp := clock.Now().Add(10 * time.Minute)
	h, err := s.Create(t.Context(), auth.KindLogin, []byte(`{"v":1}`), exp)
	if err != nil {
		t.Fatal(err)
	}
	key := prefix + "fw:sess:login:" + auth.HandleHash(h)
	got, err := c.Redis().Get(t.Context(), key).Result()
	if err != nil {
		t.Fatalf("key %s: %v", key, err)
	}
	head, doc, _ := strings.Cut(got, "\n")
	if head != strconv.FormatInt(exp.UnixMilli(), 10) || doc != `{"v":1}` {
		t.Fatalf("value %q: the expiry in epoch milliseconds, a newline, the record", got)
	}
	if ttl := c.Redis().PTTL(t.Context(), key).Val(); ttl <= 0 || ttl > 10*time.Minute {
		t.Fatalf("PX ttl %v", ttl)
	}
	// the handle itself is nowhere in the key or value
	if strings.Contains(key, h) || strings.Contains(got, h) {
		t.Fatal("the handle must not be stored")
	}
}

func TestAStoreFailureIsAnError(t *testing.T) {
	t.Parallel()
	c, prefix := open(t)
	s := authredis.NewStore(c.Redis(), fwtest.NewClock(storetest.Start)).WithPrefix(prefix)
	_ = c.Close()
	if _, _, err := s.Read(t.Context(), auth.KindSession, auth.NewHandle()); err == nil {
		t.Fatal("a store that cannot answer is an error, not 'no session'")
	}
}
