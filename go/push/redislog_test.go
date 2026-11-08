package push

import (
	"fmt"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// The tests of A2 only; the contract tests that every driver passes are table-driven over
// [eachDriver] in delivery_test.go and vectors_test.go.

func newRedisLog(t *testing.T, r fwtest.Redis) *RedisStreamLog {
	t.Helper()
	c, err := redisfw.Open(t.Context(), "test", r.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	return NewRedisStreamLog(c.Redis(), r.Prefix)
}

func TestRedisIDMapping(t *testing.T) {
	t.Parallel()
	cases := []struct {
		id   string
		want int64
	}{
		{"0-0", 0},
		{"0-4", 4},
		{"1-0", 1 << 20},
		{"1794174533896-0", 1794174533896 << 20},
		{"1794174533896-1048575", 1794174533896<<20 | 1048575}, // the largest sequence
	}
	for _, c := range cases {
		got, err := idToInt(c.id)
		if err != nil || got != c.want {
			t.Errorf("idToInt(%s) = %d, %v; want %d", c.id, got, err, c.want)
		}
		if back := intToID(got); back != c.id {
			t.Errorf("intToID(%d) = %s, want %s", got, back, c.id)
		}
	}
	for _, id := range []string{"5-1048576", "5-4294967296", "x-1", "5", "5-y", "9223372036854775807-0"} {
		if _, err := idToInt(id); err == nil {
			t.Errorf("idToInt(%s) was accepted", id)
		}
	}
}

// An entry whose sequence does not fit is an error to read, not a wrong id.
func TestRedisLogRefusesASequenceOf2To20(t *testing.T) {
	t.Parallel()
	log := newRedisLog(t, fwtest.NewRedis(t))
	add := func(id string) {
		if err := log.client.XAdd(t.Context(), &redis.XAddArgs{
			Stream: log.stream, ID: id,
			Values: []any{"topic", "t", "type", "y", "client", "", "data", "{}", "outbox", "1", "logged_at", "1"},
		}).Err(); err != nil {
			t.Fatal(err)
		}
	}
	add("5-1048575")
	got, err := log.ReadAfter(t.Context(), 0, 10)
	if err != nil || len(got) != 1 || got[0].ID != 5<<20|1048575 {
		t.Fatalf("read %+v, %v; want the entry at the largest sequence", got, err)
	}
	add("5-1048576")
	if _, err := log.ReadAfter(t.Context(), 0, 10); err == nil || !strings.Contains(err.Error(), "sequence") {
		t.Fatalf("read of 5-1048576: %v, want a refusal", err)
	}
}

func TestRedisLogReadsAfterExclusively(t *testing.T) {
	t.Parallel()
	log := newRedisLog(t, fwtest.NewRedis(t))
	if latest, err := log.Latest(t.Context()); err != nil || latest != 0 {
		t.Fatalf("empty latest %d, %v", latest, err)
	}
	if trimmed, err := log.Trimmed(t.Context()); err != nil || trimmed != 0 {
		t.Fatalf("empty trimmed %d, %v", trimmed, err)
	}
	rows := make([]OutboxRow, 5)
	for i := range rows {
		rows[i] = OutboxRow{ID: int64(i + 1), Topic: "t", Type: "y", Client: "acme", Data: fmt.Sprintf(`{"n":%d}`, i), CreatedAt: begin}
	}
	if err := log.Append(t.Context(), nil, rows, begin); err != nil {
		t.Fatal(err)
	}
	all, err := log.ReadAfter(t.Context(), 0, 10)
	if err != nil || len(all) != 5 {
		t.Fatalf("read %d, %v", len(all), err)
	}
	if e := all[0]; e.OutboxID != 1 || e.Client != "acme" || e.Data != `{"n":0}` || !e.LoggedAt.Equal(begin) {
		t.Errorf("entry %+v", e)
	}
	for i := 1; i < 5; i++ {
		if all[i].ID <= all[i-1].ID {
			t.Fatalf("ids do not increase: %v", all)
		}
	}
	after, _ := log.ReadAfter(t.Context(), all[1].ID, 2)
	if len(after) != 2 || after[0].ID != all[2].ID || after[1].ID != all[3].ID {
		t.Errorf("after the second, limit 2: %+v", after)
	}
	if latest, _ := log.Latest(t.Context()); latest != all[4].ID {
		t.Errorf("latest %d, want %d", latest, all[4].ID)
	}
	if past, _ := log.ReadAfter(t.Context(), all[4].ID, 10); len(past) != 0 {
		t.Errorf("after the newest: %+v", past)
	}
}

// Prune never deletes the newest entry, even when every entry is older than the edge, and Latest
// and Trimmed stay right.
func TestRedisLogPruneKeepsTheNewest(t *testing.T) {
	t.Parallel()
	log := newRedisLog(t, fwtest.NewRedis(t))
	if n, err := log.Prune(t.Context(), time.Now().Add(time.Hour)); err != nil || n != 0 {
		t.Fatalf("prune of nothing: %d, %v", n, err)
	}
	rows := make([]OutboxRow, 2500) // more than one batch of deletes
	for i := range rows {
		rows[i] = OutboxRow{ID: int64(i + 1), Topic: "t", Type: "y", Data: "{}"}
	}
	for chunk := range 5 {
		if err := log.Append(t.Context(), nil, rows[chunk*500:(chunk+1)*500], begin); err != nil {
			t.Fatal(err)
		}
	}
	all, _ := log.ReadAfter(t.Context(), 0, 5000)
	if len(all) != 2500 {
		t.Fatalf("logged %d", len(all))
	}
	if n, err := log.Prune(t.Context(), time.Now().Add(-time.Hour)); err != nil || n != 0 {
		t.Fatalf("prune before everything: %d, %v", n, err)
	}
	n, err := log.Prune(t.Context(), time.Now().Add(time.Hour))
	if err != nil || n != 2499 {
		t.Fatalf("pruned %d, %v; want all but the newest", n, err)
	}
	left, _ := log.ReadAfter(t.Context(), 0, 10)
	if len(left) != 1 || left[0].ID != all[2499].ID {
		t.Fatalf("left %+v, want the newest %d", left, all[2499].ID)
	}
	if latest, _ := log.Latest(t.Context()); latest != all[2499].ID {
		t.Errorf("latest %d", latest)
	}
	if trimmed, _ := log.Trimmed(t.Context()); trimmed != all[2498].ID {
		t.Errorf("trimmed %d, want %d", trimmed, all[2498].ID)
	}
	if n, _ := log.Prune(t.Context(), time.Now().Add(time.Hour)); n != 0 {
		t.Errorf("a second prune deleted %d", n)
	}
}

// A row logged twice (the relay died between the append and the commit) reaches a stream once, live
// and in a replay from the log.
func TestRedisDuplicateOutboxIDIsDeliveredOnce(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	b := backend{driver: driverRedis, dbURL: fwtest.NewDatabase(t).URL, redis: r}
	n := b.start(t, nodeConfig{env: map[string]string{"PUSH_RECENT": "0"}})
	c := orderStream(t, n, "")
	log := newRedisLog(t, r)
	row := func(id int64, note string) OutboxRow {
		return OutboxRow{ID: id, Topic: "test:order:1", Type: "order.updated", Client: "acme", Data: `{"note":"` + note + `"}`, CreatedAt: begin}
	}
	batch := []OutboxRow{row(101, "a"), row(102, "b")}
	for range 2 { // the relay's second attempt appends the same rows
		if err := log.Append(t.Context(), nil, batch, begin); err != nil {
			t.Fatal(err)
		}
	}
	if err := log.Append(t.Context(), nil, []OutboxRow{row(103, "c")}, begin); err != nil {
		t.Fatal(err)
	}
	entries := n.settle(5)
	notes := make([]string, 3)
	for i := range notes {
		notes[i] = c.next()
	}
	for i, want := range []string{`"note":"a"`, `"note":"b"`, `"note":"c"`} {
		if !strings.Contains(notes[i], want) {
			t.Errorf("frame %d is %q, want %s (a duplicate must be dropped)", i, notes[i], want)
		}
	}
	if got := []int64{pushID(t, notes[0]), pushID(t, notes[1]), pushID(t, notes[2])}; got[0] != entries[0] || got[1] != entries[1] || got[2] != entries[4] {
		t.Errorf("ids %v from log entries %v", got, entries)
	}
	// a replay from the log (the hub keeps nothing in memory here) drops the duplicates too
	token, _ := n.subscribe([]string{"test:order:1"}, nil, "X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view")
	again := n.listen(token, "0")
	again.greeting()
	replayed := make([]int64, 3)
	for i := range replayed {
		replayed[i] = pushID(t, again.next())
	}
	if replayed[0] != entries[0] || replayed[1] != entries[1] || replayed[2] != entries[4] {
		t.Errorf("replayed %v from log entries %v", replayed, entries)
	}
	n.publish(acme, "test:order:1", "order.updated", map[string]any{})
	if id := pushID(t, again.next()); id <= entries[4] {
		t.Errorf("after the replay: %d, want a live push after %d (a duplicate leaked)", id, entries[4])
	}
}

// End reaches the follower of every instance on the server, this one's included, and Follow calls
// Grown when it starts and when an entry is added.
func TestRedisFollowGrowsAndEndsAcrossInstances(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	a, b := newRedisLog(t, r), newRedisLog(t, r)
	type tap struct {
		grown atomic.Int64
		ends  chan Holder
	}
	follow := func(l *RedisStreamLog) *tap {
		x := &tap{ends: make(chan Holder, 4)}
		done := make(chan error, 1)
		go func() {
			done <- l.Follow(t.Context(), Follower{Grown: func() { x.grown.Add(1) }, End: func(h Holder) { x.ends <- h }})
		}()
		t.Cleanup(func() {
			// the context ends with the test; a clean stop returns nil
			select {
			case err := <-done:
				if err != nil {
					t.Errorf("Follow returned %v", err)
				}
			case <-time.After(10 * time.Second):
				t.Error("Follow did not return after its context ended")
			}
		})
		return x
	}
	fa, fb := follow(a), follow(b)
	waitFor(t, "both followers to start", func() bool { return fa.grown.Load() == 1 && fb.grown.Load() == 1 })
	if err := a.Append(t.Context(), nil, []OutboxRow{{ID: 1, Topic: "t", Type: "y", Data: "{}"}}, begin); err != nil {
		t.Fatal(err)
	}
	waitFor(t, "both followers to see the entry", func() bool { return fa.grown.Load() >= 2 && fb.grown.Load() >= 2 })
	want := Holder{Principal: "prn_9", Session: "s1"}
	if err := a.End(t.Context(), want); err != nil {
		t.Fatal(err)
	}
	for name, x := range map[string]*tap{"a": fa, "b": fb} {
		select {
		case h := <-x.ends:
			if h != want {
				t.Errorf("%s got %+v, want %+v", name, h, want)
			}
		case <-time.After(10 * time.Second):
			t.Errorf("%s did not receive the end", name)
		}
	}
}

// A log on another prefix does not see this one's entries or ends.
func TestRedisLogPrefixesIsolate(t *testing.T) {
	t.Parallel()
	r := fwtest.NewRedis(t)
	a := newRedisLog(t, r)
	other := newRedisLog(t, fwtest.Redis{URL: r.URL, Prefix: r.Prefix + "other:"})
	if err := a.Append(t.Context(), nil, []OutboxRow{{ID: 1, Topic: "t", Type: "y", Data: "{}"}}, begin); err != nil {
		t.Fatal(err)
	}
	if got, _ := other.ReadAfter(t.Context(), 0, 10); len(got) != 0 {
		t.Errorf("another prefix reads %+v", got)
	}
	if err := a.Ping(t.Context()); err != nil {
		t.Error(err)
	}
}
