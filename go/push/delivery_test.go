package push

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/pushrow"
	"github.com/flowcatalyst/framework/go/metrics"
	"github.com/flowcatalyst/framework/go/redisfw"
)

var acme = fw.MustClientID("acme")

// orderStream subscribes an acme user who may view orders to test:order:1 and opens the stream.
func orderStream(t *testing.T, n *node, last string, extra ...string) *client {
	t.Helper()
	h := append([]string{"X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view"}, extra...)
	token, _ := n.subscribe([]string{"test:order:1"}, nil, h...)
	c := n.listen(token, last)
	c.greeting()
	return c
}

func TestRolledBackTransactionSendsNothing(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		c := orderStream(t, n, "")
		p, _ := New("test:order:1", "order.updated", map[string]any{"v": "rolled back"})
		err := pgx.BeginTxFunc(t.Context(), n.db.Pool(), pgx.TxOptions{}, func(tx pgx.Tx) error {
			if err := n.p.Publish(t.Context(), tx, fw.ContextFor(acme), p); err != nil {
				return err
			}
			return errors.New("roll back")
		})
		if err == nil {
			t.Fatal("the transaction committed")
		}
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"v": "committed"})
		f := c.next()
		ids := n.settle(1)
		if !strings.Contains(f, `"v":"committed"`) || pushID(t, f) != ids[0] {
			t.Fatalf("first push %q, want the committed one as the log's only entry %d", f, ids[0])
		}
	})
}

func TestOutOfOrderCommitsArriveInLogOrderWithoutGap(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		c := orderStream(t, n, "")
		first, _ := New("test:order:1", "order.updated", map[string]any{"tx": "first"})
		second, _ := New("test:order:1", "order.updated", map[string]any{"tx": "second"})
		tx1, err := n.db.Pool().Begin(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx1.Rollback(context.WithoutCancel(t.Context())) }()
		if err := n.p.Publish(t.Context(), tx1, fw.ContextFor(acme), first); err != nil { // the lower outbox id
			t.Fatal(err)
		}
		if err := n.p.PublishAlone(t.Context(), fw.ContextFor(acme), second); err != nil { // commits first
			t.Fatal(err)
		}
		f := c.next()
		firstID := pushID(t, f)
		if !strings.Contains(f, `"tx":"second"`) || n.settle(1)[0] != firstID {
			t.Fatalf("got %q, want the second transaction's push as the log's first", f)
		}
		if err := tx1.Commit(t.Context()); err != nil {
			t.Fatal(err)
		}
		f = c.next()
		if got := n.settle(2); pushID(t, f) != got[1] || !strings.Contains(f, `"tx":"first"`) {
			t.Fatalf("got %q, want the first transaction's push as the log's second (%v)", f, got)
		}
	})
}

// relayParts is a relay on its own (no loop), with the backend's log, for the queue tests. Relays of
// one backend share the log.
func relayParts(t *testing.T, db *database.DB, b backend) (*relay, Log) {
	t.Helper()
	reg, err := metrics.NewRegistry()
	if err != nil {
		t.Fatal(err)
	}
	m, err := newHubMetrics(reg)
	if err != nil {
		t.Fatal(err)
	}
	var log Log = NewPostgresLog(db)
	if b.driver == driverRedis {
		c, err := redisfw.Open(t.Context(), "test", b.redis.URL)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { _ = c.Close() })
		log = NewRedisStreamLog(c.Redis(), b.redis.Prefix)
	}
	return &relay{db: db, log: log, clock: fwtest.NewClock(begin), m: m, batch: 500, interval: time.Second}, log
}

func openDB(t *testing.T, b backend) *database.DB {
	t.Helper()
	db, err := database.Open(t.Context(), "main", b.dbURL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	return db
}

func insertPush(t *testing.T, tx pgx.Tx, note string) {
	t.Helper()
	if err := pushrow.Insert(t.Context(), tx, "test:order:1", "order.updated", acme, `{"note":"`+note+`"}`, begin); err != nil {
		t.Fatal(err)
	}
}

// logged returns the data of the log's entries in order, without the entries the hub would drop (an
// outbox id it has already seen: A2's crash window), and the number of entries the log holds.
func logged(t *testing.T, log Log) ([]string, int) {
	t.Helper()
	entries, err := log.ReadAfter(t.Context(), 0, 1000)
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	seen := map[int64]bool{}
	last := int64(0)
	for _, e := range entries {
		if e.ID <= last {
			t.Fatalf("ids do not increase: %d after %d", e.ID, last)
		}
		last = e.ID
		if e.OutboxID != 0 && seen[e.OutboxID] {
			continue
		}
		seen[e.OutboxID] = true
		out = append(out, e.Data)
	}
	return out, len(entries)
}

// The relay drains the outbox as a queue: a row whose transaction commits after a later row's was
// relayed is still relayed (never skipped by an id cursor), after it.
func TestRelayDrainsTheOutboxAsAQueue(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		db := openDB(t, b)
		r, log := relayParts(t, db, b)
		tx1, err := db.Pool().Begin(t.Context())
		if err != nil {
			t.Fatal(err)
		}
		defer func() { _ = tx1.Rollback(context.WithoutCancel(t.Context())) }()
		insertPush(t, tx1, "a") // outbox id 1, committed last
		if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error { insertPush(t, tx, "b"); return nil }); err != nil {
			t.Fatal(err)
		}
		if n, err := r.once(t.Context()); err != nil || n != 1 {
			t.Fatalf("first run moved %d (%v), want 1", n, err)
		}
		if err := tx1.Commit(t.Context()); err != nil {
			t.Fatal(err)
		}
		if n, err := r.once(t.Context()); err != nil || n != 1 {
			t.Fatalf("second run moved %d (%v), want the late row", n, err)
		}
		data, _ := logged(t, log)
		if got, want := strings.Join(data, " "), `{"note":"b"} {"note":"a"}`; got != want {
			t.Fatalf("log %s, want %s", got, want)
		}
		var left int
		_ = db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM fw_push_outbox").Scan(&left)
		if left != 0 {
			t.Errorf("%d rows left in the outbox", left)
		}
	})
}

// A relay that dies mid-run (after logging and deleting, before its commit) logs nothing; the
// replacement, which waited for its rows, logs each exactly once. A stale relay that commits after
// all leaves the replacement nothing to repeat.
func TestRelayKilledMidRunNeitherLosesNorRepeats(t *testing.T) {
	t.Parallel()
	for _, dies := range []bool{true, false} {
		t.Run(fmt.Sprintf("dies=%v", dies), func(t *testing.T) {
			t.Parallel()
			eachDriver(t, func(t *testing.T, b backend) {
				db := openDB(t, b)
				first, log := relayParts(t, db, b)
				second, _ := relayParts(t, db, b)
				if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error {
					for i := range 3 {
						insertPush(t, tx, fmt.Sprint(i))
					}
					return nil
				}); err != nil {
					t.Fatal(err)
				}
				inside, release := make(chan struct{}), make(chan struct{})
				first.beforeCommit = func(context.Context) error {
					close(inside)
					<-release
					if dies {
						return errors.New("killed")
					}
					return nil
				}
				var wg sync.WaitGroup
				var firstMoved, secondMoved int
				var firstErr, secondErr error
				wg.Go(func() { firstMoved, firstErr = first.once(t.Context()) })
				<-inside
				wg.Go(func() { secondMoved, secondErr = second.once(t.Context()) }) // waits on the rows' locks
				time.Sleep(100 * time.Millisecond)
				close(release)
				wg.Wait()
				if dies {
					if firstErr == nil || firstMoved != 0 || secondErr != nil || secondMoved != 3 {
						t.Fatalf("first %d %v, second %d %v", firstMoved, firstErr, secondMoved, secondErr)
					}
				} else if firstErr != nil || firstMoved != 3 || secondErr != nil || secondMoved != 0 {
					t.Fatalf("first %d %v, second %d %v", firstMoved, firstErr, secondMoved, secondErr)
				}
				if n, err := second.once(t.Context()); err != nil || n != 0 {
					t.Fatalf("a further run moved %d (%v)", n, err)
				}
				data, entries := logged(t, log)
				if got, want := strings.Join(data, " "), `{"note":"0"} {"note":"1"} {"note":"2"}`; got != want {
					t.Fatalf("log %s, want %s", got, want)
				}
				// A2 appends before the commit: the relay that died left its three entries, which the
				// hub drops by outbox id; every other case logs each row once
				want := 3
				if b.driver == driverRedis && dies {
					want = 6
				}
				if entries != want {
					t.Errorf("the log holds %d entries, want %d", entries, want)
				}
			})
		})
	}
}

func TestTwoInstancesOnOneDatabase(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, bk backend) {
		a := bk.start(t, nodeConfig{})
		b := bk.start(t, nodeConfig{})
		cb := orderStream(t, b, "")
		ca := orderStream(t, a, "")
		a.publish(acme, "test:order:1", "order.updated", map[string]any{"from": "a"})
		b.publish(acme, "test:order:1", "order.updated", map[string]any{"from": "b"})
		want := a.settle(2)
		for name, c := range map[string]*client{"a": ca, "b": cb} {
			if got := c.ids(2); fmt.Sprint(got) != fmt.Sprint(want) {
				t.Errorf("instance %s got %v, want %v", name, got, want)
			}
		}
	})
}

func TestEndPrincipalEndsItsStreamsOnEveryInstance(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, bk backend) {
		a := bk.start(t, nodeConfig{})
		b := bk.start(t, nodeConfig{})
		mine := orderStream(t, b, "")
		token, _ := b.subscribe([]string{"test:order:1"}, nil, "X-Client", "acme", "X-Principal", "prn_2", "X-Permissions", "test:order:view")
		other := b.listen(token, "")
		other.greeting()
		if err := a.p.EndPrincipal(t.Context(), "prn_1"); err != nil {
			t.Fatal(err)
		}
		if rest := mine.ended(10 * time.Second); len(rest) != 1 || rest[0] != "event: auth-expired\ndata: {}\n\n" {
			t.Fatalf("frames before the end %q, want auth-expired", rest)
		}
		a.publish(acme, "test:order:1", "order.updated", map[string]any{})
		if id := pushID(t, other.next()); id != a.settle(1)[0] {
			t.Errorf("another principal's stream got %d", id)
		}
		if v := counter(b.metrics, "fw_push_streams_ended_total", "revoked"); v != 1 {
			t.Errorf("ended revoked %v, want 1", v)
		}
	})
}

func TestResumeAfterADrop(t *testing.T) {
	t.Parallel()
	for _, recent := range []string{"10000", "0"} {
		t.Run("recent="+recent, func(t *testing.T) {
			t.Parallel()
			eachDriver(t, func(t *testing.T, b backend) {
				n := b.start(t, nodeConfig{env: map[string]string{"PUSH_RECENT": recent}})
				h := []string{"X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view"}
				token, _ := n.subscribe([]string{"test:order:1"}, nil, h...)
				c := n.listen(token, "")
				c.greeting()
				for i := range 3 {
					n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": i})
				}
				first := n.settle(3)
				if got := c.ids(3); fmt.Sprint(got) != fmt.Sprint(first) {
					t.Fatalf("got %v, want %v", got, first)
				}
				c.close()
				n.publish(acme, "test:order:2", "order.updated", map[string]any{}) // another topic: not replayed
				n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": 4})
				n.publish(fw.MustClientID("other"), "test:order:1", "order.updated", map[string]any{}) // another client
				n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": 6})
				ids := n.settle(7)
				again := n.listen(token, fmt.Sprint(ids[2]))
				if g := again.greeting(); !strings.Contains(g, fmt.Sprintf(`"latest":%d`, ids[6])) {
					t.Fatalf("subscribed %q", g)
				}
				if got := again.ids(2); got[0] != ids[4] || got[1] != ids[6] {
					t.Fatalf("replayed %v, want %v", got, []int64{ids[4], ids[6]})
				}
				n.publish(acme, "test:order:1", "order.updated", map[string]any{})
				if id := pushID(t, again.next()); id != n.settle(8)[7] {
					t.Fatalf("live after the replay %d, want the eighth entry", id)
				}
				source := map[string]string{"10000": "memory", "0": "log"}[recent]
				if v := counter(n.metrics, "fw_push_replay_source_total", source); v != 1 {
					t.Errorf("replays from %s: %v, want 1", source, v)
				}
			})
		})
	}
}

func TestResetForAnIDOlderThanRetentionAndForAnUnknownID(t *testing.T) {
	t.Parallel()
	const (
		retention = "id: %d\nevent: reset\ndata: {\"reason\":\"retention\",\"latest\":%[1]d}\n\n"
		unknown   = "id: %d\nevent: reset\ndata: {\"reason\":\"unknown-id\",\"latest\":%[1]d}\n\n"
	)
	// the log holds six pushes, numbered 0 to 5 here; retention took 0 and 1
	cases := []struct {
		name string
		last func(ids []int64) string
		// want is the pushes replayed (their numbers), or a reset
		want  []int
		reset string
	}{
		{"deleted", func(ids []int64) string { return fmt.Sprint(ids[0]) }, nil, retention},
		{"newest deleted", func(ids []int64) string { return fmt.Sprint(ids[1]) }, []int{2, 3, 4, 5}, ""}, // nothing lost
		{"from the start", func([]int64) string { return "0" }, nil, retention},
		{"never logged", func(ids []int64) string { return fmt.Sprint(ids[5] + 3) }, nil, unknown},
		{"up to date", func(ids []int64) string { return fmt.Sprint(ids[5]) }, nil, ""},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			eachDriver(t, func(t *testing.T, b backend) {
				n := b.start(t, nodeConfig{env: map[string]string{"PUSH_RECENT": "0"}})
				for i := range 6 {
					n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": i})
				}
				ids := n.settle(6)
				deleteLogUpTo(t, n.p.log, n.db, ids[1]) // retention took the first two
				s := orderStream(t, n, c.last(ids))
				if c.reset != "" {
					if f, want := s.next(), fmt.Sprintf(c.reset, ids[5]); f != want {
						t.Errorf("%q, want %q", f, want)
					}
				}
				for _, k := range c.want {
					if got := pushID(t, s.next()); got != ids[k] {
						t.Errorf("push %d, want %d", got, ids[k])
					}
				}
				n.publish(acme, "test:order:1", "order.updated", map[string]any{})
				if id := pushID(t, s.next()); id <= ids[5] { // live continues after a reset or a replay
					t.Errorf("live push %d, want one after %d", id, ids[5])
				}
			})
		})
	}
}

func TestPruneKeepsTheNewestSoIdsKeepIncreasing(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		db := openDB(t, b)
		r, log := relayParts(t, db, b)
		for range 3 {
			if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error { insertPush(t, tx, "x"); return nil }); err != nil {
				t.Fatal(err)
			}
			if _, err := r.once(t.Context()); err != nil {
				t.Fatal(err)
			}
		}
		before, err := log.ReadAfter(t.Context(), 0, 10)
		if err != nil || len(before) != 3 {
			t.Fatalf("log %v (%v), want 3 entries", before, err)
		}
		// A1 stamps the relay's clock (fixed at begin); A2's ids are the Redis server's clock
		cutoff := begin.Add(time.Hour)
		if b.driver == driverRedis {
			cutoff = time.Now().Add(time.Hour)
		}
		deleted, err := log.Prune(t.Context(), cutoff)
		if err != nil || deleted != 2 {
			t.Fatalf("pruned %d (%v), want 2 of 3", deleted, err)
		}
		if trimmed, _ := log.Trimmed(t.Context()); trimmed != before[1].ID {
			t.Errorf("trimmed %d, want %d", trimmed, before[1].ID)
		}
		if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error { insertPush(t, tx, "y"); return nil }); err != nil {
			t.Fatal(err)
		}
		if _, err := r.once(t.Context()); err != nil {
			t.Fatal(err)
		}
		data, _ := logged(t, log)
		if got := strings.Join(data, " "); got != `{"note":"x"} {"note":"y"}` {
			t.Fatalf("log %s", got)
		}
		after, _ := log.ReadAfter(t.Context(), 0, 10)
		if after[0].ID != before[2].ID || after[1].ID <= after[0].ID {
			t.Errorf("ids %d, %d: the newest %d must stay and the next must be above it", after[0].ID, after[1].ID, before[2].ID)
		}
	})
}

func TestAuthExpiredAtTheStreamTokensExpiry(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	expires := fmt.Sprint(begin.Add(2 * time.Second).Unix())
	token, answer := n.subscribe([]string{"test:order:1"}, nil, "X-Client", "acme", "X-Principal", "prn_1",
		"X-Permissions", "test:order:view", "X-Expires", expires)
	if answer["expiresAt"] != "2026-11-08T12:00:02Z" {
		t.Fatalf("expiresAt %v", answer["expiresAt"])
	}
	c := n.listen(token, "")
	c.greeting()
	if rest := c.ended(10 * time.Second); len(rest) != 1 || rest[0] != "event: auth-expired\ndata: {}\n\n" {
		t.Fatalf("frames %q, want auth-expired then the end", rest)
	}
	n.clock.Advance(3 * time.Second)
	if _, status := n.tryListen(token, ""); status != 401 {
		t.Fatalf("an expired token: %d, want 401", status)
	}
	a := n.call("GET", "/push/stream?st="+token, "")
	if a.code() != "STREAM_TOKEN_EXPIRED" {
		t.Errorf("code %s", a.code())
	}
	if v := counter(n.metrics, "fw_push_streams_ended_total", "expired"); v != 1 {
		t.Errorf("ended expired %v", v)
	}
}

// A stream that falls push.stream.buffer pushes behind is ended without an event, and resumes from
// its Last-Event-ID with nothing lost.
func TestSlowStreamIsEndedAndResumesLosslessly(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{env: map[string]string{"PUSH_STREAM_BUFFER": "16"}})
		sub := subscription{topics: []string{"test:order:1"}, client: "acme"}
		slow, _, err := n.p.hub.open(sub, false, 0, false) // never read
		if err != nil {
			t.Fatal(err)
		}
		for i := range 20 {
			n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": i})
		}
		select {
		case <-slow.done:
		case <-time.After(10 * time.Second):
			t.Fatal("the slow stream was not ended")
		}
		if r := n.p.hub.endReason(slow); r != endSlow {
			t.Fatalf("reason %s", r)
		}
		var seen []int64
		for f := slow.next(); f != nil; f = slow.next() {
			seen = append(seen, f.ID)
		}
		ids := n.settle(20)
		if fmt.Sprint(seen) != fmt.Sprint(ids[:16]) {
			t.Fatalf("buffered %v, want the first 16 of %v", seen, ids)
		}
		resumed, op, err := n.p.hub.open(sub, false, seen[15], true)
		if err != nil {
			t.Fatal(err)
		}
		defer n.p.hub.close(resumed, endClient)
		if !op.memory || len(op.replay) != 4 || op.replay[0].ID != ids[16] || op.replay[3].ID != ids[19] {
			t.Fatalf("resume %+v, want the last four from memory", op)
		}
		if v := counter(n.metrics, "fw_push_streams_ended_total", "slow"); v != 1 {
			t.Errorf("ended slow %v", v)
		}
	})
}

func TestClientFilter(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		n := b.start(t, nodeConfig{})
		open := func(client string) *client {
			token, _ := n.subscribe([]string{"test:order:1"}, nil, "X-Client", client, "X-Principal", "prn_"+client, "X-Permissions", "test:order:view")
			c := n.listen(token, "")
			c.greeting()
			return c
		}
		acmeStream, otherStream, platformStream := open("acme"), open("other"), open("_platform")
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"for": "acme"})         // 1
		n.publish(fw.MustClientID("other"), "test:order:1", "order.updated", map[string]any{})  // 2
		n.publish(fw.PlatformClient, "test:order:1", "order.updated", map[string]any{"all": 1}) // 3
		n.publish(acme, "test:order:1", "order.updated", map[string]any{"for": "acme", "n": 2}) // 4
		ids := n.settle(4)
		pick := func(idx ...int) []int64 {
			var out []int64
			for _, i := range idx {
				out = append(out, ids[i])
			}
			return out
		}
		for name, tc := range map[string]struct {
			c    *client
			want []int64
		}{
			"acme":     {acmeStream, pick(0, 2, 3)},
			"other":    {otherStream, pick(1, 2)},
			"platform": {platformStream, pick(0, 1, 2, 3)},
		} {
			got := tc.c.ids(len(tc.want))
			if fmt.Sprint(got) != fmt.Sprint(tc.want) {
				t.Errorf("%s: %v, want %v", name, got, tc.want)
			}
		}
		// nothing else arrives: other's next push is the sixth, not acme's fifth
		n.publish(acme, "test:order:1", "order.updated", map[string]any{})
		n.publish(fw.MustClientID("other"), "test:order:1", "order.updated", map[string]any{})
		if id, sixth := pushID(t, otherStream.next()), n.settle(6)[5]; id != sixth {
			t.Errorf("other got push %d, want the sixth, %d", id, sixth)
		}
	})
}
