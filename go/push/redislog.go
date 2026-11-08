package push

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"math"
	"strconv"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// The keys and the channel of A2, under the log's key prefix.
const (
	redisStreamKey  = "fw:push"     // the stream: one entry per push
	redisEndChannel = "fw:push:end" // end the streams of a holder; the message is its JSON
)

// seqBits is how many low bits of a log id hold the Redis entry id's sequence: id = ms << 20 | seq.
const (
	seqBits = 20
	seqMask = 1<<seqBits - 1
)

// followBlock is how long one XREAD BLOCK waits: the longest a stopped Follow takes to return,
// because go-redis does not interrupt a blocked read when its context ends.
const followBlock = time.Second

// pruneBatch is how many entries Prune deletes per XDEL.
const pruneBatch = 1000

// RedisStreamLog is the A2 log driver (push contract section 4): the Redis stream <prefix>fw:push on one
// standalone Redis or Valkey server (6.2 or later: exclusive range ids), ids the entry id ms-seq
// sent as ms * 2^20 + seq, followers woken by XREAD BLOCK, ends of streams sent with PUBLISH on
// <prefix>fw:push:end. It appends outside the relay's transaction, so a crash between the append and
// the commit logs a row twice: each entry keeps its outbox id for the hub to drop the second.
type RedisStreamLog struct {
	client  *redis.Client
	stream  string
	channel string
}

var _ Log = (*RedisStreamLog)(nil)

// NewRedisStreamLog returns the A2 driver on client; prefix is put before the key and the channel ("" in
// production; tests give each test its own).
func NewRedisStreamLog(client *redis.Client, prefix string) *RedisStreamLog {
	return &RedisStreamLog{client: client, stream: prefix + redisStreamKey, channel: prefix + redisEndChannel}
}

// RedisClients selects the A2 driver for push.driver=redis on the Redis instance push.redis-instance
// of clients (the default instance when it is not set); prefix goes before the stream's key.
func RedisClients(clients *redisfw.Clients, prefix string) Option {
	return RedisLog(func(_ context.Context, _ *fw.Boot, instance string) (Log, error) {
		var c *redisfw.Client
		var err error
		if instance == "" {
			def, ok := clients.Default()
			if !ok {
				return nil, errors.New("push.driver is redis, but no Redis instance is configured")
			}
			c = def
		} else if c, err = clients.Get(instance); err != nil {
			return nil, fmt.Errorf("push.redis-instance: %w", err)
		}
		return NewRedisStreamLog(c.Redis(), prefix), nil
	})
}

// idToInt converts a Redis entry id "ms-seq" to the log id ms * 2^20 + seq.
func idToInt(id string) (int64, error) {
	msText, seqText, ok := strings.Cut(id, "-")
	if !ok {
		return 0, fmt.Errorf("push: the Redis entry id %q is not ms-seq", id)
	}
	ms, err := strconv.ParseUint(msText, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("push: the Redis entry id %q is not ms-seq", id)
	}
	seq, err := strconv.ParseUint(seqText, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("push: the Redis entry id %q is not ms-seq", id)
	}
	if seq > seqMask {
		return 0, fmt.Errorf("push: the Redis entry id %q has a sequence of 2^%d or more, which a log id cannot hold", id, seqBits)
	}
	if ms > math.MaxInt64>>seqBits {
		return 0, fmt.Errorf("push: the Redis entry id %q is too far in the future for a log id", id)
	}
	return int64(ms)<<seqBits | int64(seq), nil
}

// intToID converts a log id back to the Redis entry id "ms-seq".
func intToID(n int64) string {
	return strconv.FormatInt(n>>seqBits, 10) + "-" + strconv.FormatInt(n&seqMask, 10)
}

// Append implements Log: one XADD per row in a MULTI/EXEC, so the rows get consecutive ids in the
// rows' order. tx is not used: the relay deletes the rows from the outbox after this returns.
func (l *RedisStreamLog) Append(ctx context.Context, _ pgx.Tx, rows []OutboxRow, at time.Time) error {
	if len(rows) == 0 {
		return nil
	}
	loggedAt := strconv.FormatInt(at.UnixMicro(), 10)
	cmds, err := l.client.TxPipelined(ctx, func(p redis.Pipeliner) error {
		for _, r := range rows {
			p.XAdd(ctx, &redis.XAddArgs{Stream: l.stream, ID: "*", Values: []any{
				"topic", r.Topic, "type", r.Type, "client", r.Client, "data", r.Data,
				"outbox", strconv.FormatInt(r.ID, 10), "logged_at", loggedAt,
			}})
		}
		return nil
	})
	if err != nil {
		return fmt.Errorf("push: append to the log: %w", err)
	}
	for _, c := range cmds {
		if id, ok := c.(*redis.StringCmd); ok {
			if _, err := idToInt(id.Val()); err != nil {
				return err
			}
		}
	}
	return nil
}

// ReadAfter implements Log: XRANGE from the id after after.
func (l *RedisStreamLog) ReadAfter(ctx context.Context, after int64, limit int) ([]Entry, error) {
	start := "-"
	if after > 0 {
		start = "(" + intToID(after)
	}
	msgs, err := l.client.XRangeN(ctx, l.stream, start, "+", int64(limit)).Result()
	if err != nil {
		return nil, fmt.Errorf("push: read the log: %w", err)
	}
	out := make([]Entry, 0, len(msgs))
	for _, m := range msgs {
		e, err := entryOf(m)
		if err != nil {
			return nil, err
		}
		out = append(out, e)
	}
	return out, nil
}

// entryOf reads a stream entry as the log's Entry.
func entryOf(m redis.XMessage) (Entry, error) {
	id, err := idToInt(m.ID)
	if err != nil {
		return Entry{}, err
	}
	field := func(name string) string {
		s, _ := m.Values[name].(string)
		return s
	}
	e := Entry{ID: id, Topic: field("topic"), Type: field("type"), Client: field("client"), Data: field("data")}
	if e.OutboxID, err = strconv.ParseInt(field("outbox"), 10, 64); err != nil {
		return Entry{}, fmt.Errorf("push: the log entry %s has no outbox id", m.ID)
	}
	micros, err := strconv.ParseInt(field("logged_at"), 10, 64)
	if err != nil {
		return Entry{}, fmt.Errorf("push: the log entry %s has no logged_at", m.ID)
	}
	e.LoggedAt = time.UnixMicro(micros).UTC()
	return e, nil
}

// info reads XINFO STREAM; the stream does not exist (nothing was ever logged) is nil, nil.
func (l *RedisStreamLog) info(ctx context.Context) (*redis.XInfoStream, error) {
	info, err := l.client.XInfoStream(ctx, l.stream).Result()
	if err != nil {
		if strings.Contains(err.Error(), "no such key") {
			return nil, nil
		}
		return nil, err
	}
	return info, nil
}

// Latest implements Log: the stream's last-generated-id, which stays right when the stream is
// empty or trimmed.
func (l *RedisStreamLog) Latest(ctx context.Context) (int64, error) {
	info, err := l.info(ctx)
	if err != nil {
		return 0, fmt.Errorf("push: read the log's last id: %w", err)
	}
	if info == nil {
		return 0, nil
	}
	return idToInt(info.LastGeneratedID)
}

// Trimmed implements Log: the stream's max-deleted-entry-id. Prune deletes with XDEL because XTRIM
// does not move it.
func (l *RedisStreamLog) Trimmed(ctx context.Context) (int64, error) {
	info, err := l.info(ctx)
	if err != nil {
		return 0, fmt.Errorf("push: read the log's deleted id: %w", err)
	}
	if info == nil {
		return 0, nil
	}
	return idToInt(info.MaxDeletedEntryID)
}

// Prune implements Log: it deletes (XDEL, in batches) the entries whose id is below the Redis
// server's millisecond of before, except the newest entry, so ids keep increasing. The ids are the
// server's clock: retention is minutes to hours, so a skew of seconds against the application's
// clock does not matter.
func (l *RedisStreamLog) Prune(ctx context.Context, before time.Time) (int64, error) {
	newest, err := l.client.XRevRangeN(ctx, l.stream, "+", "-", 1).Result()
	if err != nil {
		return 0, fmt.Errorf("push: prune the log: %w", err)
	}
	if len(newest) == 0 {
		return 0, nil
	}
	top, err := idToInt(newest[0].ID)
	if err != nil {
		return 0, err
	}
	// delete ids below the edge, and below the newest entry
	upto := min(max(before.UnixMilli(), 0)<<seqBits, top)
	var deleted int64
	for {
		msgs, err := l.client.XRangeN(ctx, l.stream, "-", "("+intToID(upto), pruneBatch).Result()
		if err != nil {
			return deleted, fmt.Errorf("push: prune the log: %w", err)
		}
		if len(msgs) == 0 {
			return deleted, nil
		}
		ids := make([]string, len(msgs))
		for i, m := range msgs {
			ids[i] = m.ID
		}
		n, err := l.client.XDel(ctx, l.stream, ids...).Result()
		if err != nil {
			return deleted, fmt.Errorf("push: prune the log: %w", err)
		}
		deleted += n
		if len(msgs) < pruneBatch {
			return deleted, nil
		}
	}
}

// Follow implements Log: it subscribes to the end channel, calls f.Grown, then blocks in XREAD on
// the stream from its last id and calls f.Grown whenever an entry was added. It returns nil when ctx
// ends, and the error of the connection that failed otherwise (go-redis has retried by then): the
// hub calls Follow again after a pause and polls meanwhile, so the application stays ready and
// pushes are late by at most push.relay.interval. An end sent while the subscription is down is
// lost, as with LISTEN/NOTIFY.
func (l *RedisStreamLog) Follow(ctx context.Context, f Follower) error {
	sub := l.client.Subscribe(ctx, l.channel)
	defer func() { _ = sub.Close() }()
	if _, err := sub.Receive(ctx); err != nil {
		if ctx.Err() != nil {
			return nil
		}
		return fmt.Errorf("push: subscribe to the end channel: %w", err)
	}
	go func() {
		for msg := range sub.Channel() {
			var h Holder
			if json.Unmarshal([]byte(msg.Payload), &h) == nil && !h.IsZero() {
				f.End(h)
			}
		}
	}()
	cursor, err := l.cursor(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return nil
		}
		return err
	}
	f.Grown()
	for ctx.Err() == nil {
		res, err := l.client.XRead(ctx, &redis.XReadArgs{Streams: []string{l.stream, cursor}, Count: 1, Block: followBlock}).Result()
		switch {
		case errors.Is(err, redis.Nil): // nothing came within the block
			continue
		case err != nil:
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("push: wait for the log to grow: %w", err)
		}
		if len(res) == 0 || len(res[0].Messages) == 0 {
			continue
		}
		// move the cursor to the stream's end before waking the hub, so what is added after this
		// read is read next time and what was added before it is visible to the hub's read
		if cursor, err = l.cursor(ctx); err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		f.Grown()
	}
	return nil
}

// cursor is the id XREAD continues after: the stream's last-generated-id ("0-0" for a stream that
// does not exist yet).
func (l *RedisStreamLog) cursor(ctx context.Context) (string, error) {
	info, err := l.info(ctx)
	if err != nil {
		return "", fmt.Errorf("push: read the log's last id: %w", err)
	}
	if info == nil {
		return "0-0", nil
	}
	return info.LastGeneratedID, nil
}

// End implements Log: PUBLISH of the holder as JSON on the end channel; every instance's Follow
// receives it (this one included).
func (l *RedisStreamLog) End(ctx context.Context, h Holder) error {
	payload, err := json.Marshal(h)
	if err != nil {
		return err
	}
	if err := l.client.Publish(ctx, l.channel, string(payload)).Err(); err != nil {
		return fmt.Errorf("push: publish the end of streams: %w", err)
	}
	return nil
}

// Ping implements Log.
func (l *RedisStreamLog) Ping(ctx context.Context) error { return l.client.Ping(ctx).Err() }
