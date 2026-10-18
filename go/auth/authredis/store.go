// Package authredis is the Redis session store (sessions-and-login contract section 6.3): key
// fw:sess:<kind>:<hash>, the record with its expiry in front so the application's clock decides,
// and PX so Redis drops it as well. Take is GETDEL (Redis 6.2 or later); Update is SET ... XX, so a
// deleted record is not brought back.
package authredis

import (
	"context"
	"errors"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// Prefix is the key prefix.
const Prefix = "fw:sess:"

// Store is the Redis session store.
type Store struct {
	client *redis.Client
	clock  fw.Clock
	prefix string
}

// NewStore returns the store on a go-redis client (from redisfw.Client.Redis); clock decides expiry.
func NewStore(client *redis.Client, clock fw.Clock) *Store {
	return &Store{client: client, clock: clock, prefix: Prefix}
}

// Factory is the auth.StoreFactory of the store on the default Redis instance.
func Factory(clients *redisfw.Clients) auth.StoreFactory {
	return func(_ context.Context, b *fw.Boot) (auth.Store, error) {
		c, ok := clients.Default()
		if !ok {
			return nil, errors.New("the Redis session store needs a default Redis instance, and none is configured")
		}
		return NewStore(c.Redis(), b.Clock()), nil
	}
}

// WithPrefix returns the store with another key prefix (tests share one server).
func (s *Store) WithPrefix(prefix string) *Store {
	c := *s
	c.prefix = prefix
	return &c
}

// Create implements auth.Store.
func (s *Store) Create(ctx context.Context, kind auth.Kind, data []byte, expiresAt time.Time) (string, error) {
	handle := auth.NewHandle()
	if err := s.client.Set(ctx, s.key(kind, handle), value(data, expiresAt), s.ttl(expiresAt)).Err(); err != nil {
		return "", auth.StoreError("the redis session store could not create", err)
	}
	return handle, nil
}

// Read implements auth.Store.
func (s *Store) Read(ctx context.Context, kind auth.Kind, handle string) ([]byte, bool, error) {
	if !auth.IsWellFormedHandle(handle) {
		return nil, false, nil
	}
	v, err := s.client.Get(ctx, s.key(kind, handle)).Result()
	return s.live(v, err, "read")
}

// Take implements auth.Store: GETDEL.
func (s *Store) Take(ctx context.Context, kind auth.Kind, handle string) ([]byte, bool, error) {
	if !auth.IsWellFormedHandle(handle) {
		return nil, false, nil
	}
	v, err := s.client.GetDel(ctx, s.key(kind, handle)).Result()
	return s.live(v, err, "take")
}

// Update implements auth.Store: SET ... XX.
func (s *Store) Update(ctx context.Context, kind auth.Kind, handle string, data []byte, expiresAt time.Time) (string, error) {
	if !auth.IsWellFormedHandle(handle) {
		return handle, nil
	}
	err := s.client.SetArgs(ctx, s.key(kind, handle), value(data, expiresAt), redis.SetArgs{Mode: "XX", TTL: s.ttl(expiresAt)}).Err()
	if err != nil && !errors.Is(err, redis.Nil) { // Nil: the record is gone, and stays gone
		return "", auth.StoreError("the redis session store could not update", err)
	}
	return handle, nil
}

// Delete implements auth.Store.
func (s *Store) Delete(ctx context.Context, kind auth.Kind, handle string) error {
	if !auth.IsWellFormedHandle(handle) {
		return nil
	}
	if err := s.client.Del(ctx, s.key(kind, handle)).Err(); err != nil {
		return auth.StoreError("the redis session store could not delete", err)
	}
	return nil
}

// Revocable implements auth.Store.
func (*Store) Revocable() bool { return true }

func (s *Store) live(v string, err error, what string) ([]byte, bool, error) {
	if errors.Is(err, redis.Nil) {
		return nil, false, nil
	}
	if err != nil {
		return nil, false, auth.StoreError("the redis session store could not "+what, err)
	}
	head, doc, found := strings.Cut(v, "\n")
	if !found {
		return nil, false, nil
	}
	expires, perr := strconv.ParseInt(head, 10, 64)
	if perr != nil || s.clock.Now().UnixMilli() >= expires {
		return nil, false, nil
	}
	return []byte(doc), true, nil
}

func value(data []byte, expiresAt time.Time) string {
	return strconv.FormatInt(expiresAt.UnixMilli(), 10) + "\n" + string(data)
}

// ttl is the time to expiry, at least one millisecond (a PX of zero or less is an error).
func (s *Store) ttl(expiresAt time.Time) time.Duration {
	return max(time.Millisecond, expiresAt.Sub(s.clock.Now()))
}

func (s *Store) key(kind auth.Kind, handle string) string {
	return s.prefix + string(kind) + ":" + auth.HandleHash(handle)
}
