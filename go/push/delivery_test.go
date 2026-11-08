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
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
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
	if !strings.Contains(f, `"v":"committed"`) || pushID(t, f) != 1 {
		t.Fatalf("first push %q, want the committed one as id 1", f)
	}
	var rows int
	_ = n.db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM fw_push_log").Scan(&rows)
	if rows != 1 {
		t.Errorf("log rows %d, want 1", rows)
	}
}

func TestOutOfOrderCommitsArriveInLogOrderWithoutGap(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
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
	if pushID(t, f) != 1 || !strings.Contains(f, `"tx":"second"`) {
		t.Fatalf("got %q, want the second transaction's push as id 1", f)
	}
	if err := tx1.Commit(t.Context()); err != nil {
		t.Fatal(err)
	}
	f = c.next()
	if pushID(t, f) != 2 || !strings.Contains(f, `"tx":"first"`) {
		t.Fatalf("got %q, want the first transaction's push as id 2", f)
	}
}

// relayParts is a relay on its own (no loop), with the A1 log, for the queue tests.
func relayParts(t *testing.T, db *database.DB) (*relay, *PostgresLog) {
	t.Helper()
	reg, err := metrics.NewRegistry()
	if err != nil {
		t.Fatal(err)
	}
	m, err := newHubMetrics(reg)
	if err != nil {
		t.Fatal(err)
	}
	log := NewPostgresLog(db)
	return &relay{db: db, log: log, clock: fwtest.NewClock(begin), m: m, batch: 500, interval: time.Second}, log
}

func openDB(t *testing.T) *database.DB {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
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

func logged(t *testing.T, log Log) []string {
	t.Helper()
	entries, err := log.ReadAfter(t.Context(), 0, 1000)
	if err != nil {
		t.Fatal(err)
	}
	var out []string
	for _, e := range entries {
		out = append(out, fmt.Sprintf("%d:%s", e.ID, e.Data))
	}
	return out
}

// The relay drains the outbox as a queue: a row whose transaction commits after a later row's was
// relayed is still relayed (never skipped by an id cursor), after it.
func TestRelayDrainsTheOutboxAsAQueue(t *testing.T) {
	t.Parallel()
	db := openDB(t)
	r, log := relayParts(t, db)
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
	got := strings.Join(logged(t, log), " ")
	if want := `1:{"note":"b"} 2:{"note":"a"}`; got != want {
		t.Fatalf("log %s, want %s", got, want)
	}
	var left int
	_ = db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM fw_push_outbox").Scan(&left)
	if left != 0 {
		t.Errorf("%d rows left in the outbox", left)
	}
}

// A relay that dies mid-run (after logging and deleting, before its commit) logs nothing; the
// replacement, which waited for its rows, logs each exactly once. A stale relay that commits after
// all leaves the replacement nothing to repeat.
func TestRelayKilledMidRunNeitherLosesNorRepeats(t *testing.T) {
	t.Parallel()
	for _, dies := range []bool{true, false} {
		t.Run(fmt.Sprintf("dies=%v", dies), func(t *testing.T) {
			t.Parallel()
			db := openDB(t)
			first, log := relayParts(t, db)
			second, _ := relayParts(t, db)
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
			got := strings.Join(logged(t, log), " ")
			if want := `1:{"note":"0"} 2:{"note":"1"} 3:{"note":"2"}`; got != want {
				t.Fatalf("log %s, want %s", got, want)
			}
		})
	}
}

func TestTwoInstancesOnOneDatabase(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	a := startNode(t, url, nodeConfig{})
	b := startNode(t, url, nodeConfig{})
	cb := orderStream(t, b, "")
	ca := orderStream(t, a, "")
	a.publish(acme, "test:order:1", "order.updated", map[string]any{"from": "a"})
	b.publish(acme, "test:order:1", "order.updated", map[string]any{"from": "b"})
	for name, c := range map[string]*client{"a": ca, "b": cb} {
		if got := c.ids(2); got[0] != 1 || got[1] != 2 {
			t.Errorf("instance %s got %v, want [1 2]", name, got)
		}
	}
}

func TestEndPrincipalEndsItsStreamsOnEveryInstance(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	a := startNode(t, url, nodeConfig{})
	b := startNode(t, url, nodeConfig{})
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
	if id := pushID(t, other.next()); id != 1 {
		t.Errorf("another principal's stream got %d", id)
	}
	if v := counter(b.metrics, "fw_push_streams_ended_total", "revoked"); v != 1 {
		t.Errorf("ended revoked %v, want 1", v)
	}
}

func TestResumeAfterADrop(t *testing.T) {
	t.Parallel()
	for _, recent := range []string{"10000", "0"} {
		t.Run("recent="+recent, func(t *testing.T) {
			t.Parallel()
			n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_RECENT": recent}})
			h := []string{"X-Client", "acme", "X-Principal", "prn_1", "X-Permissions", "test:order:view"}
			token, _ := n.subscribe([]string{"test:order:1"}, nil, h...)
			c := n.listen(token, "")
			c.greeting()
			for i := range 3 {
				n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": i})
			}
			if got := c.ids(3); got[2] != 3 {
				t.Fatalf("got %v", got)
			}
			c.close()
			n.publish(acme, "test:order:2", "order.updated", map[string]any{}) // another topic: not replayed
			n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": 4})
			n.publish(fw.MustClientID("other"), "test:order:1", "order.updated", map[string]any{}) // another client
			n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": 6})
			waitFor(t, "the hub to follow", func() bool { return n.p.hub.lastID() == 7 })
			again := n.listen(token, "3")
			if g := again.greeting(); !strings.Contains(g, `"latest":7`) {
				t.Fatalf("subscribed %q", g)
			}
			if got := again.ids(2); got[0] != 5 || got[1] != 7 {
				t.Fatalf("replayed %v, want [5 7]", got)
			}
			n.publish(acme, "test:order:1", "order.updated", map[string]any{})
			if id := pushID(t, again.next()); id != 8 {
				t.Fatalf("live after the replay %d, want 8", id)
			}
			source := map[string]string{"10000": "memory", "0": "log"}[recent]
			if v := counter(n.metrics, "fw_push_replay_source_total", source); v != 1 {
				t.Errorf("replays from %s: %v, want 1", source, v)
			}
		})
	}
}

func TestResetForAnIDOlderThanRetentionAndForAnUnknownID(t *testing.T) {
	t.Parallel()
	const (
		retention = "id: 6\nevent: reset\ndata: {\"reason\":\"retention\",\"latest\":6}\n\n"
		unknown   = "id: 6\nevent: reset\ndata: {\"reason\":\"unknown-id\",\"latest\":6}\n\n"
	)
	cases := []struct {
		last string
		want []string
	}{
		{"1", []string{retention}},          // 2 is gone
		{"2", []string{"3", "4", "5", "6"}}, // the oldest kept is 3: nothing lost
		{"0", []string{retention}},          // from the start
		{"9", []string{unknown}},            // never logged here
		{"6", nil},                          // up to date
	}
	for _, c := range cases {
		t.Run("Last-Event-ID="+c.last, func(t *testing.T) {
			t.Parallel()
			n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_RECENT": "0"}})
			for i := range 6 {
				n.publish(acme, "test:order:1", "order.updated", map[string]any{"n": i})
			}
			waitFor(t, "the hub to follow", func() bool { return n.p.hub.lastID() == 6 })
			if _, err := n.db.Pool().Exec(t.Context(), "DELETE FROM fw_push_log WHERE id <= 2"); err != nil { // retention took 1 and 2
				t.Fatal(err)
			}
			s := orderStream(t, n, c.last)
			for _, w := range c.want {
				f := s.next()
				if strings.HasPrefix(w, "id: ") {
					if f != w {
						t.Errorf("%q, want %q", f, w)
					}
				} else if got := fmt.Sprint(pushID(t, f)); got != w {
					t.Errorf("push %s, want %s", got, w)
				}
			}
			n.publish(acme, "test:order:1", "order.updated", map[string]any{})
			if id := pushID(t, s.next()); id != 7 { // live continues after a reset or a replay
				t.Errorf("live push %d, want 7", id)
			}
		})
	}
}

func TestPruneKeepsTheNewestSoIdsKeepIncreasing(t *testing.T) {
	t.Parallel()
	db := openDB(t)
	r, log := relayParts(t, db)
	for range 3 {
		if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error { insertPush(t, tx, "x"); return nil }); err != nil {
			t.Fatal(err)
		}
		if _, err := r.once(t.Context()); err != nil {
			t.Fatal(err)
		}
	}
	deleted, err := log.Prune(t.Context(), begin.Add(time.Hour))
	if err != nil || deleted != 2 {
		t.Fatalf("pruned %d (%v), want 2 of 3", deleted, err)
	}
	if trimmed, _ := log.Trimmed(t.Context()); trimmed != 2 {
		t.Errorf("trimmed %d, want 2", trimmed)
	}
	if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error { insertPush(t, tx, "y"); return nil }); err != nil {
		t.Fatal(err)
	}
	if _, err := r.once(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(logged(t, log), " "); got != `3:{"note":"x"} 4:{"note":"y"}` {
		t.Fatalf("log %s", got)
	}
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
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_STREAM_BUFFER": "16"}})
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
	for len(slow.ch) > 0 {
		seen = append(seen, (<-slow.ch).ID)
	}
	if len(seen) != 16 || seen[15] != 16 {
		t.Fatalf("buffered %v, want 1..16", seen)
	}
	waitFor(t, "the hub to follow", func() bool { return n.p.hub.lastID() == 20 })
	resumed, op, err := n.p.hub.open(sub, false, seen[15], true)
	if err != nil {
		t.Fatal(err)
	}
	defer n.p.hub.close(resumed, endClient)
	if !op.memory || len(op.replay) != 4 || op.replay[0].ID != 17 || op.replay[3].ID != 20 {
		t.Fatalf("resume %+v, want 17..20 from memory", op)
	}
	if v := counter(n.metrics, "fw_push_streams_ended_total", "slow"); v != 1 {
		t.Errorf("ended slow %v", v)
	}
}

func TestClientFilter(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
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
	for name, tc := range map[string]struct {
		c    *client
		want []int64
	}{
		"acme":     {acmeStream, []int64{1, 3, 4}},
		"other":    {otherStream, []int64{2, 3}},
		"platform": {platformStream, []int64{1, 2, 3, 4}},
	} {
		got := tc.c.ids(len(tc.want))
		if fmt.Sprint(got) != fmt.Sprint(tc.want) {
			t.Errorf("%s: %v, want %v", name, got, tc.want)
		}
	}
	// nothing else arrives: other's next push is 6, not acme's 5
	n.publish(acme, "test:order:1", "order.updated", map[string]any{})
	n.publish(fw.MustClientID("other"), "test:order:1", "order.updated", map[string]any{})
	if id := pushID(t, otherStream.next()); id != 6 {
		t.Errorf("other got push %d, want 6", id)
	}
}
