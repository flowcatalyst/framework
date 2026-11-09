package push

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/metrics"
)

// The fixes after the first load test (docs/push-load-test-results.md): the relay lock's lease, the
// memory of a restarted instance, the drain, and the memory per stream.

// memLog is a Log in memory for the hub's own tests: it records every ReadAfter, can hold the reads
// until released, and remembers whether a read's context was cancelled while it was held.
type memLog struct {
	mu       sync.Mutex
	entries  []Entry
	limits   []int         // the limit of each ReadAfter
	hold     chan struct{} // when not nil, ReadAfter waits for it to be closed
	reading  chan struct{} // receives once per ReadAfter that starts
	canceled bool          // a held read found its context ended
}

var _ Log = (*memLog)(nil)

func (l *memLog) Append(context.Context, pgx.Tx, []OutboxRow, time.Time) error { return nil }

func (l *memLog) ReadAfter(ctx context.Context, after int64, limit int) ([]Entry, error) {
	l.mu.Lock()
	l.limits = append(l.limits, limit)
	hold, reading := l.hold, l.reading
	l.mu.Unlock()
	if reading != nil {
		reading <- struct{}{}
	}
	if hold != nil {
		<-hold
		if ctx.Err() != nil {
			l.mu.Lock()
			l.canceled = true
			l.mu.Unlock()
			return nil, ctx.Err()
		}
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []Entry
	for _, e := range l.entries {
		if e.ID > after && len(out) < limit {
			out = append(out, e)
		}
	}
	return out, nil
}

func (l *memLog) Newest(_ context.Context, limit int) ([]Entry, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.entries[max(0, len(l.entries)-limit):]), nil
}

func (l *memLog) Latest(context.Context) (int64, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.entries) == 0 {
		return 0, nil
	}
	return l.entries[len(l.entries)-1].ID, nil
}

func (l *memLog) Trimmed(context.Context) (int64, error) { return 0, nil }

func (l *memLog) Prune(context.Context, time.Time) (int64, error) { return 0, nil }

func (l *memLog) Follow(ctx context.Context, f Follower) error {
	f.Grown()
	<-ctx.Done()
	return nil
}

func (l *memLog) End(context.Context, Holder) error { return nil }

func (l *memLog) Ping(context.Context) error { return nil }

// fill adds n pushes with ids 1..n; push i goes to topic test:order:<i mod topics>.
func (l *memLog) fill(n, topics int, outboxIDs bool) {
	for i := 1; i <= n; i++ {
		e := Entry{ID: int64(i), Topic: fmt.Sprintf("test:order:%d", i%topics), Type: "order.updated", Client: "acme", Data: fmt.Sprintf(`{"n":%d}`, i)}
		if outboxIDs {
			e.OutboxID = int64(i)
		}
		l.entries = append(l.entries, e)
	}
}

func (l *memLog) reads() []int {
	l.mu.Lock()
	defer l.mu.Unlock()
	return slices.Clone(l.limits)
}

// bareHub is a hub on log without the rest of the module.
func bareHub(t *testing.T, log Log, cfg hubConfig) (*hub, *metrics.Registry) {
	t.Helper()
	reg, err := metrics.NewRegistry()
	if err != nil {
		t.Fatal(err)
	}
	m, err := newHubMetrics(reg)
	if err != nil {
		t.Fatal(err)
	}
	if cfg.buffer == 0 {
		cfg.buffer = 256
	}
	cfg.maxStreams, cfg.maxPerPrincipal = 1<<30, 1<<30
	if cfg.poll == 0 {
		cfg.poll = time.Hour
	}
	return newHub(log, slog.New(slog.DiscardHandler), fwtest.NewClock(begin), m, cfg), reg
}

var orderSub = subscription{topics: []string{"test:order:1"}, client: "acme"}

// ---- fix 1: the relay lock's lease ----

// relayLockLeft is how long the relay's lock has left, and whether an owner holds it.
func relayLockLeft(t *testing.T, n *node) (time.Duration, bool) {
	t.Helper()
	var secs float64
	var owner *string
	err := n.db.Pool().QueryRow(t.Context(),
		`SELECT extract(epoch FROM expires_at - clock_timestamp())::float8, owner_token FROM fw_lock WHERE lock_key = $1`,
		locks.MustServiceKey(relayLoop).Stored()).Scan(&secs, &owner)
	if err != nil {
		return 0, false
	}
	return time.Duration(secs * float64(time.Second)), owner != nil && secs > 0
}

// The relay's lock is taken with push.relay.lease (default 5 s, was 15 s): after an instance dies
// holding it, the next relay starts within that.
func TestTheRelayLockLeaseIsTheSetting(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		env   string
		lease time.Duration
	}{{"", 5 * time.Second}, {"2s", 2 * time.Second}} {
		t.Run("lease="+c.lease.String(), func(t *testing.T) {
			t.Parallel()
			env := map[string]string{}
			if c.env != "" {
				env["PUSH_RELAY_LEASE"] = c.env
			}
			n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: env})
			var left time.Duration
			waitFor(t, "the relay to hold its lock", func() bool {
				var held bool
				left, held = relayLockLeft(t, n)
				return held
			})
			if left > c.lease || left < c.lease/3 {
				t.Errorf("the relay's lock has %v left, want at most the lease %v (refreshed every third)", left, c.lease)
			}
		})
	}
	n, err := tryNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_RELAY_LEASE": "61s"}})
	if err == nil || !strings.Contains(err.Error(), "push.relay.lease") {
		t.Errorf("a lease over 60s: %v, %v; want a start-up problem naming push.relay.lease", n, err)
	}
}

// A relay that stops cleanly releases its lock, so another instance's relay takes over at once (a
// rolling deploy), not after the lease.
func TestACleanlyStoppedRelayHandsOverAtOnce(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	env := map[string]string{"PUSH_RELAY_LEASE": "60s"}
	a := startNode(t, url, nodeConfig{env: env})
	a.publish(acme, "test:order:1", "order.updated", map[string]any{"from": "a"})
	a.settle(1) // a's relay holds the lock
	b := startNode(t, url, nodeConfig{env: env})
	c := orderStream(t, b, "")
	if err := a.run.Shutdown(); err != nil {
		t.Fatal(err)
	}
	b.publish(acme, "test:order:1", "order.updated", map[string]any{"from": "b"})
	if id := pushID(t, c.next()); id != b.settle(2)[1] { // within 10 s, a sixth of the lease
		t.Errorf("got push %d", id)
	}
}

// A relay run under way when the application stops (or the lock is lost) is let finish: its queries
// are not cancelled, so the pool keeps its connection and the rows are logged once.
func TestARelayRunIsNotCancelledByTheStop(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		db := openDB(t, b)
		r, log := relayParts(t, db, b)
		if err := pgx.BeginFunc(t.Context(), db.Pool(), func(tx pgx.Tx) error {
			for i := range 3 {
				insertPush(t, tx, fmt.Sprint(i))
			}
			return nil
		}); err != nil {
			t.Fatal(err)
		}
		ctx, stop := context.WithCancel(t.Context())
		r.beforeCommit = func(context.Context) error {
			stop() // the application stops while the run is between its delete and its commit
			return nil
		}
		if n, err := r.once(ctx); err != nil || n != 3 {
			t.Fatalf("the run moved %d (%v), want all 3 committed", n, err)
		}
		if data, _ := logged(t, log); len(data) != 3 {
			t.Errorf("logged %v", data)
		}
	})
}

// ---- fix 2: a restarted instance replays from memory ----

// A new instance starts with the newest push.recent pushes of the log in memory, so its clients
// resume from memory, not the log; one whose Last-Event-ID is older than that memory is replayed
// from the log, and one older than the log's retention gets a reset.
func TestARestartedInstanceResumesFromMemory(t *testing.T) {
	t.Parallel()
	eachDriver(t, func(t *testing.T, b backend) {
		first := b.start(t, nodeConfig{})
		for i := range 30 {
			first.publish(acme, fmt.Sprintf("test:order:%d", 1+i%2), "order.updated", map[string]any{"n": i})
		}
		ids := first.settle(30)
		if err := first.run.Shutdown(); err != nil {
			t.Fatal(err)
		}
		mine := func(from int) []int64 { // the pushes of test:order:1 after the from-th
			var out []int64
			for i := from + 1; i < 30; i++ {
				if i%2 == 0 {
					out = append(out, ids[i])
				}
			}
			return out
		}

		restarted := b.start(t, nodeConfig{})
		c := orderStream(t, restarted, fmt.Sprint(ids[9]))
		if got, want := c.ids(len(mine(9))), mine(9); fmt.Sprint(got) != fmt.Sprint(want) {
			t.Fatalf("replayed %v, want %v", got, want)
		}
		if m, l := counter(restarted.metrics, "fw_push_replay_source_total", "memory"), counter(restarted.metrics, "fw_push_replay_source_total", "log"); m != 1 || l != 0 {
			t.Errorf("replays from memory %v and from the log %v, want 1 and 0", m, l)
		}

		small := b.start(t, nodeConfig{env: map[string]string{"PUSH_RECENT": "10"}}) // holds the newest 10
		c = orderStream(t, small, fmt.Sprint(ids[25]))
		if got := c.ids(len(mine(25))); fmt.Sprint(got) != fmt.Sprint(mine(25)) {
			t.Fatalf("from memory: %v", got)
		}
		c = orderStream(t, small, fmt.Sprint(ids[5]))
		if got := c.ids(len(mine(5))); fmt.Sprint(got) != fmt.Sprint(mine(5)) {
			t.Fatalf("from the log: %v", got)
		}
		if m, l := counter(small.metrics, "fw_push_replay_source_total", "memory"), counter(small.metrics, "fw_push_replay_source_total", "log"); m != 1 || l != 1 {
			t.Errorf("replays from memory %v and from the log %v, want 1 and 1", m, l)
		}

		deleteLogUpTo(t, small.p.log, small.db, ids[1]) // retention took the first two
		trimmed := b.start(t, nodeConfig{})
		reset := orderStream(t, trimmed, fmt.Sprint(ids[0]))
		if f, want := reset.next(), fmt.Sprintf("id: %d\nevent: reset\ndata: {\"reason\":\"retention\",\"latest\":%[1]d}\n\n", ids[29]); f != want {
			t.Errorf("%q, want %q", f, want)
		}
		c = orderStream(t, trimmed, fmt.Sprint(ids[1]))
		if got := c.ids(len(mine(1))); fmt.Sprint(got) != fmt.Sprint(mine(1)) {
			t.Fatalf("after the oldest kept: %v", got)
		}
		if m := counter(trimmed.metrics, "fw_push_replay_source_total", "memory"); m != 1 {
			t.Errorf("replays from memory %v, want 1 (everything after the deleted pushes is held)", m)
		}
	})
}

// A replay from the log holds one page at a time, whatever its length: every read asks for at most
// pageSize entries, and the replay is complete.
func TestALogReplayReadsOnePageAtATime(t *testing.T) {
	t.Parallel()
	log := &memLog{}
	log.fill(3*pageSize+500, 4, true) // longer than any memory; outbox ids as A2 has them
	h, _ := bareHub(t, log, hubConfig{})
	s, _, err := h.open(orderSub, false, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	got := make([]int64, 0, len(log.entries))
	reset, err := h.replayLog(t.Context(), s, 0, int64(len(log.entries)), func(e Entry) error {
		got = append(got, e.ID)
		return nil
	})
	if err != nil || reset {
		t.Fatalf("replay: reset %v, %v", reset, err)
	}
	if len(got) != len(log.entries)/4 || got[0] != 1 || got[len(got)-1] != 3497 {
		t.Errorf("replayed %d pushes (%d to %d), want every fourth", len(got), got[0], got[len(got)-1])
	}
	reads := log.reads()
	if len(reads) != 4 {
		t.Errorf("%d reads, want 4 pages", len(reads))
	}
	for _, limit := range reads {
		if limit > pageSize {
			t.Errorf("a read asked for %d entries, more than a page (%d)", limit, pageSize)
		}
	}
}

// Streams that resume from the same id at the same time share each page read.
func TestConcurrentReplaysShareTheirReads(t *testing.T) {
	t.Parallel()
	log := &memLog{hold: make(chan struct{}), reading: make(chan struct{}, 100)}
	log.fill(40, 2, false)
	h, _ := bareHub(t, log, hubConfig{})
	const streams = 20
	var wg sync.WaitGroup
	counts := make([]int, streams)
	for i := range streams {
		s, _, err := h.open(orderSub, false, 0, false)
		if err != nil {
			t.Fatal(err)
		}
		wg.Go(func() {
			if _, err := h.replayLog(t.Context(), s, 10, 40, func(Entry) error { counts[i]++; return nil }); err != nil {
				t.Error(err)
			}
		})
	}
	<-log.reading
	time.Sleep(100 * time.Millisecond) // the others reach the read under way
	close(log.hold)
	wg.Wait()
	if n := len(log.reads()); n != 1 {
		t.Errorf("%d reads of the log, want 1 shared by the %d replays", n, streams)
	}
	for i, n := range counts {
		if n != 15 {
			t.Errorf("stream %d replayed %d pushes, want 15", i, n)
		}
	}
}

// ---- fix 3: the drain ----

// The hub's drain ends every stream at once, with the reason drain, and opens none after it.
func TestTheHubDrainEndsEveryStreamAtOnce(t *testing.T) {
	t.Parallel()
	h, reg := bareHub(t, &memLog{}, hubConfig{})
	const streams = 50_000
	all := make([]*stream, streams)
	for i := range all {
		s, _, err := h.open(subscription{topics: []string{fmt.Sprintf("test:order:%d", i)}, client: "acme", principal: "prn_1"}, false, 0, false)
		if err != nil {
			t.Fatal(err)
		}
		all[i] = s
	}
	start := time.Now()
	h.drain()
	took := time.Since(start)
	for i, s := range all {
		select {
		case <-s.done:
		default:
			t.Fatalf("stream %d is still open", i)
		}
		if s.reason != endDrain {
			t.Fatalf("stream %d ended %s", i, s.reason)
		}
	}
	if took > 2*time.Second {
		t.Errorf("the drain of %d streams took %v", streams, took)
	}
	if h.openCount() != 0 || counter(reg, "fw_push_streams_ended_total", "drain") != streams || counter(reg, "fw_push_streams_open") != 0 {
		t.Errorf("open %d, ended drain %v, gauge %v", h.openCount(), counter(reg, "fw_push_streams_ended_total", "drain"), counter(reg, "fw_push_streams_open"))
	}
	if _, _, err := h.open(orderSub, false, 0, false); !errors.Is(err, errDraining) {
		t.Errorf("open after the drain: %v, want errDraining", err)
	}
}

// A drained hub gives the database no more work: it stops reading the log (no stream is left), and a
// replay of a stream that was ended stops at its next page.
func TestADrainedHubStopsReadingTheLog(t *testing.T) {
	t.Parallel()
	log := &memLog{}
	log.fill(10, 2, false)
	h, _ := bareHub(t, log, hubConfig{poll: 10 * time.Millisecond})
	ctx, stop := context.WithCancel(t.Context())
	defer stop()
	go h.run(ctx)
	waitFor(t, "the hub to read the log", func() bool { return h.lastID() == 10 })
	s, _, err := h.open(orderSub, false, 0, false)
	if err != nil {
		t.Fatal(err)
	}
	h.drain()
	// a read that began before the drain is let finish (17.11), so the count is taken once the
	// reads have stopped moving; after that the drained hub must not read again
	before := len(log.reads())
	for {
		time.Sleep(30 * time.Millisecond) // three polls
		n := len(log.reads())
		if n == before {
			break
		}
		before = n
	}
	time.Sleep(100 * time.Millisecond) // ten polls
	h.poke()
	time.Sleep(20 * time.Millisecond)
	if after := len(log.reads()); after != before {
		t.Errorf("the drained hub read the log %d more times", after-before)
	}
	if _, err := h.replayLog(t.Context(), s, 0, 10, func(Entry) error { return nil }); !errors.Is(err, errEnded) {
		t.Errorf("a replay of an ended stream: %v, want errEnded", err)
	}
	if after := len(log.reads()); after != before {
		t.Errorf("the replay of an ended stream read the log")
	}
}

// When the server's drain begins, every open stream ends well within the drain window and the
// application stops.
func TestShutdownWithManyStreamsEndsThemWithinTheDrain(t *testing.T) {
	t.Parallel()
	n := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{env: map[string]string{"PUSH_MAX_STREAMS_PER_PRINCIPAL": "1000"}})
	token, _ := n.subscribe([]string{"test:order:1"}, nil, user...)
	const streams = 300
	clients := make([]*client, streams)
	for i := range clients {
		clients[i] = n.listen(token, "")
		clients[i].greeting()
	}
	start := time.Now()
	if err := n.run.Shutdown(); err != nil {
		t.Fatal(err)
	}
	took := time.Since(start)
	for i, c := range clients {
		if rest := c.ended(time.Second); len(rest) != 0 {
			t.Fatalf("stream %d: frames %q before the end", i, rest)
		}
	}
	if took > 3*time.Second {
		t.Errorf("the shutdown with %d streams took %v (app.shutdown.drain is 10s)", streams, took)
	}
	n.p.hub.mu.Lock()
	drained := n.p.hub.draining
	n.p.hub.mu.Unlock()
	if !drained {
		t.Error("the hub did not see the server's drain")
	}
	if v := counter(n.metrics, "fw_push_streams_ended_total", "drain"); v != streams {
		t.Errorf("ended drain %v, want %d", v, streams)
	}
}

// Stopping the hub lets a read of the log under way finish instead of cancelling it (a cancelled
// query makes the pool wait for PostgreSQL to acknowledge the cancel when it closes).
func TestStoppingTheHubLetsALogReadFinish(t *testing.T) {
	t.Parallel()
	log := &memLog{hold: make(chan struct{}), reading: make(chan struct{}, 10)}
	h, _ := bareHub(t, log, hubConfig{})
	ctx, stop := context.WithCancel(t.Context())
	go h.run(ctx)
	<-log.reading
	stop()
	time.Sleep(50 * time.Millisecond)
	close(log.hold)
	select {
	case <-h.stopped:
	case <-time.After(10 * time.Second):
		t.Fatal("the hub did not stop")
	}
	log.mu.Lock()
	defer log.mu.Unlock()
	if log.canceled {
		t.Error("the read under way was cancelled by the stop")
	}
}

// ---- fix 4: memory per stream ----

// A push is encoded once and the same frame is handed to every stream; an idle stream holds no
// buffer, and one that caught up lets its backlog go.
func TestAPushIsEncodedOnceForEveryStream(t *testing.T) {
	t.Parallel()
	h, _ := bareHub(t, &memLog{}, hubConfig{buffer: 256})
	streams := make([]*stream, 0, 3)
	for range 3 {
		s, _, err := h.open(orderSub, false, 0, false)
		if err != nil {
			t.Fatal(err)
		}
		if s.queue != nil {
			t.Fatalf("an idle stream holds a buffer of %d", len(s.queue))
		}
		streams = append(streams, s)
	}
	page := make([]Entry, 0, 100)
	for i := 1; i <= 100; i++ {
		page = append(page, Entry{ID: int64(i), Topic: "test:order:1", Type: "order.updated", Client: "acme", Data: `{}`})
	}
	h.dispatch(framesOf(page))
	first := make([]*frame, 0, 100)
	for f := streams[0].next(); f != nil; f = streams[0].next() {
		first = append(first, f)
	}
	if len(first) != 100 || string(first[0].wire) != pushEvent(page[0]) {
		t.Fatalf("got %d frames, the first %q", len(first), first[0].wire)
	}
	for _, s := range streams[1:] {
		for i := range 100 {
			if f := s.next(); f != first[i] {
				t.Fatalf("frame %d is not the shared one", i)
			}
		}
	}
	if streams[0].queue != nil {
		t.Errorf("a stream that caught up keeps a buffer of %d", len(streams[0].queue))
	}
}

// What one idle stream costs the hub, without the connection: well under the 256-entry buffer the
// first load test measured (18.6 KiB).
func TestAnIdleStreamIsSmall(t *testing.T) {
	h, _ := bareHub(t, &memLog{}, hubConfig{buffer: 256})
	const streams = 20_000
	keep := make([]*stream, 0, streams)
	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	for i := range streams {
		s, _, err := h.open(subscription{topics: []string{fmt.Sprintf("test:order:%d", i)}, client: "acme"}, false, 0, false)
		if err != nil {
			t.Fatal(err)
		}
		keep = append(keep, s)
	}
	runtime.GC()
	runtime.ReadMemStats(&after)
	per := (int64(after.HeapAlloc) - int64(before.HeapAlloc)) / streams
	t.Logf("%d bytes per idle stream in the hub", per)
	if per > 2048 {
		t.Errorf("%d bytes per idle stream, want at most 2 KiB", per)
	}
	runtime.KeepAlive(keep)
}
