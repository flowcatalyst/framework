package push

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/metrics"
)

// Why a stream ended (the reason label of fw_push_streams_ended_total).
const (
	endClient  = "client"
	endSlow    = "slow"
	endExpired = "expired"
	endRevoked = "revoked"
	endDrain   = "drain"
)

// pageSize is how many entries one read of the log asks for.
const pageSize = 1000

// opTimeout bounds one read of the log by the hub and one relay run. Neither is cancelled when the
// application stops: it is let finish (they take milliseconds), because a query cancelled half-way
// makes the driver abandon its connection, and closing the pool then waits for PostgreSQL to
// acknowledge the cancel (pgx gives it up to 15 s).
const opTimeout = 10 * time.Second

// opContext is the context of one log read or relay run: not cancelled with ctx, bounded by
// opTimeout.
func opContext(ctx context.Context) (context.Context, context.CancelFunc) {
	return context.WithTimeout(context.WithoutCancel(ctx), opTimeout)
}

// errDraining is a listen after the hub began to drain.
var errDraining = errors.New("push: draining")

// errEnded stops a replay from the log for a stream that the hub ended meanwhile (the drain, a
// revocation, a slow stream).
var errEnded = errors.New("push: the stream ended")

// errLimit is a subscribe or a listen over push.max-streams or push.max-streams-per-principal, or
// over an application's stream limit (an *appLimitError, which matches it).
var errLimit = errors.New("push: stream limit")

// appLimitError is a subscribe or a listen over an application's stream limit (push contract 17.16).
type appLimitError struct{ app string }

func (e *appLimitError) Error() string { return "push: stream limit of application " + e.app }

// Is makes errors.Is(err, errLimit) true.
func (e *appLimitError) Is(target error) bool { return target == errLimit }

// subscription is what a stream listens to and who holds it: a stream token's claims.
type subscription struct {
	topics                     []string
	client                     string // the stream's client; the platform client's value for platform-wide
	principal, device, session string
}

// frame is one push as the hub holds it: the log entry and its SSE event, encoded once when the hub
// reads it and shared, read-only, by the memory of recent pushes and by every stream it goes to.
type frame struct {
	Entry
	wire []byte // the push event (pushEvent), never modified
}

func newFrame(e Entry) *frame { return &frame{Entry: e, wire: appendPushEvent(nil, e)} }

// stream is one open stream on this instance.
type stream struct {
	sub      subscription
	topics   map[string]struct{}
	platform bool     // opened in the platform client's context: hears every client
	apps     []string // the applications of its topics, counted by the hub (multi-application hosts)
	done     chan struct{}
	reason   string // why it ended, set before done is closed
	ended    bool

	// The live pushes not yet taken by the stream's goroutine: at most push.stream.buffer, in a ring
	// that grows only as far as the stream falls behind (an idle stream holds none). The hub appends
	// under qmu and never blocks; ready (capacity 1) wakes the stream's goroutine.
	qmu   sync.Mutex
	queue []*frame
	head  int
	n     int
	ready chan struct{}
}

// offer queues f unless limit pushes are already waiting; false means the stream is too slow.
func (s *stream) offer(f *frame, limit int) bool {
	s.qmu.Lock()
	if s.n >= limit {
		s.qmu.Unlock()
		return false
	}
	if s.n == len(s.queue) {
		grown := make([]*frame, min(limit, max(4, 2*len(s.queue))))
		for i := range s.n {
			grown[i] = s.queue[(s.head+i)%len(s.queue)]
		}
		s.queue, s.head = grown, 0
	}
	s.queue[(s.head+s.n)%len(s.queue)] = f
	s.n++
	s.qmu.Unlock()
	select {
	case s.ready <- struct{}{}:
	default:
	}
	return true
}

// next takes the oldest waiting push, nil when none waits. An emptied ring larger than a burst of a
// few pushes is let go, so a stream keeps no memory of a backlog it has caught up with.
func (s *stream) next() *frame {
	s.qmu.Lock()
	defer s.qmu.Unlock()
	if s.n == 0 {
		return nil
	}
	f := s.queue[s.head]
	s.queue[s.head] = nil
	s.head = (s.head + 1) % len(s.queue)
	s.n--
	if s.n == 0 {
		s.head = 0
		if len(s.queue) > 16 {
			s.queue = nil
		}
	}
	return f
}

// accepts is the client rule of push contract section 5, made for every push on every stream.
func (s *stream) accepts(e Entry) bool {
	return e.Client == "" || s.platform || e.Client == s.sub.client
}

// wants reports whether the stream receives e: a granted topic and the client rule.
func (s *stream) wants(e Entry) bool {
	_, ok := s.topics[e.Topic]
	return ok && s.accepts(e)
}

// hubMetrics are the metrics of push contract section 11.
type hubMetrics struct {
	published    *metrics.Counter
	relayLag     *metrics.Timer
	logID        *metrics.Gauge
	open         *metrics.Gauge
	delivered    *metrics.Counter
	ended        *metrics.Counter
	resets       *metrics.Counter
	replaySource *metrics.Counter
}

func newHubMetrics(reg *metrics.Registry) (*hubMetrics, error) {
	var m hubMetrics
	var err error
	steps := []func() error{
		func() error {
			m.published, err = reg.Counter("fw_push_published_total", "Pushes logged by the relay (each committed push once).")
			return err
		},
		func() error {
			m.relayLag, err = reg.Timer("fw_push_relay_lag_seconds", "Age of the oldest outbox row a relay run moved.")
			return err
		},
		func() error {
			m.logID, err = reg.Gauge("fw_push_log_id", "Highest push log id this instance has followed.")
			return err
		},
		func() error {
			m.open, err = reg.Gauge("fw_push_streams_open", "Push streams open on this instance.")
			return err
		},
		func() error {
			m.delivered, err = reg.Counter("fw_push_delivered_total", "Pushes written to streams (live and replayed).")
			return err
		},
		func() error {
			m.ended, err = reg.Counter("fw_push_streams_ended_total", "Push streams ended, by reason.", "reason")
			return err
		},
		func() error {
			m.resets, err = reg.Counter("fw_push_resets_total", "Reset events sent, by reason.", "reason")
			return err
		},
		func() error {
			m.replaySource, err = reg.Counter("fw_push_replay_source_total", "Replays served, by source (memory or log).", "source")
			return err
		},
	}
	for _, f := range steps {
		if err := f(); err != nil {
			return nil, err
		}
	}
	return &m, nil
}

type hubConfig struct {
	buffer, recent, maxStreams, maxPerPrincipal int
	poll                                        time.Duration
	perApp                                      bool // count streams per application (multi-application hosts)
}

// hub is this instance's view of the log (push contract section 8): it follows the log, keeps the
// most recent pushes in memory and fans each push out to the streams of its topic.
type hub struct {
	log    Log
	logger *slog.Logger
	clock  fw.Clock
	m      *hubMetrics
	cfg    hubConfig

	mu          sync.Mutex
	last        int64    // the highest id dispatched
	from        int64    // the ring holds every push after from
	ring        []*frame // the recent pushes; circular once full, head the oldest
	head        int
	seen        outboxSeen
	byTopic     map[string]map[*stream]struct{}
	byPrincipal map[string]map[*stream]struct{}
	byApp       map[string]int // open streams per application; a stream counts once for each it spans
	all         map[*stream]struct{}
	draining    bool                     // the server's drain began: every stream ended, none opens
	drains      map[<-chan struct{}]bool // the servers' drain signals being watched
	wake        chan struct{}
	following   chan struct{} // closed once the driver follows the log for the first time
	followOnce  sync.Once
	stopped     chan struct{} // closed when run returns
	stopOnce    sync.Once

	pmu   sync.Mutex
	pages map[int64]*pageRead // log reads of replays in flight, by the id they read after
}

func newHub(log Log, logger *slog.Logger, clock fw.Clock, m *hubMetrics, cfg hubConfig) *hub {
	return &hub{
		log: log, logger: logger, clock: clock, m: m, cfg: cfg,
		seen:    outboxSeen{capacity: max(cfg.recent, 10_000), set: map[int64]struct{}{}},
		byTopic: map[string]map[*stream]struct{}{}, byPrincipal: map[string]map[*stream]struct{}{},
		byApp: map[string]int{}, all: map[*stream]struct{}{}, drains: map[<-chan struct{}]bool{}, wake: make(chan struct{}, 1),
		following: make(chan struct{}), stopped: make(chan struct{}), pages: map[int64]*pageRead{},
	}
}

// warm reads where the log stands before the hub serves (push contract section 8): the last id, and
// the newest push.recent pushes into memory, in one read, so that the clients of an instance that
// was just started resume from memory too. The memory then holds every push after from: the push
// before the oldest one read, or, when the log holds no more than push.recent pushes, the highest id
// the log has deleted (read after the pushes, so a push pruned meanwhile is not counted as held).
func (h *hub) warm(ctx context.Context) error {
	latest, err := h.log.Latest(ctx)
	if err != nil {
		return err
	}
	var entries []Entry
	trimmed := latest
	if h.cfg.recent > 0 {
		if entries, err = h.log.Newest(ctx, h.cfg.recent+1); err != nil {
			return err
		}
		if len(entries) <= h.cfg.recent {
			if trimmed, err = h.log.Trimmed(ctx); err != nil {
				return err
			}
		}
	}
	h.mu.Lock()
	defer h.mu.Unlock()
	h.last, h.from = latest, latest
	if len(entries) > 0 {
		if len(entries) > h.cfg.recent {
			h.from, entries = entries[0].ID, entries[1:]
		} else {
			h.from = trimmed
		}
		for _, e := range entries {
			if e.ID <= h.from {
				continue
			}
			h.last = max(h.last, e.ID)
			if e.OutboxID != 0 && !h.seen.add(e.OutboxID) {
				continue
			}
			h.ring = append(h.ring, newFrame(e))
		}
	}
	h.m.logID.Set(float64(h.last))
	return nil
}

// admitLocked checks the limits of section 8 for one more stream of principal, and the stream limit
// of each application in caps (17.16).
func (h *hub) admitLocked(principal string, caps []appCap) error {
	if len(h.all) >= h.cfg.maxStreams {
		return errLimit
	}
	if principal != "" && len(h.byPrincipal[principal]) >= h.cfg.maxPerPrincipal {
		return errLimit
	}
	for _, c := range caps {
		if h.byApp[c.app] >= c.max {
			return &appLimitError{app: c.app}
		}
	}
	return nil
}

// admit reports whether a subscribe of principal, spanning the applications of caps, is within the
// limits now.
func (h *hub) admit(principal string, caps []appCap) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.admitLocked(principal, caps)
}

// opening is what a stream starts from: the highest id dispatched when it was registered (pushes
// after it come live), and, when the memory holds everything after the client's Last-Event-ID, the
// pushes to replay from it.
type opening struct {
	latest int64
	memory bool
	replay []*frame
}

// open registers a stream. With hasLast, the replay from memory is taken in the same critical
// section, so nothing is evicted between deciding and reading.
func (h *hub) open(sub subscription, platform bool, last int64, hasLast bool) (*stream, opening, error) {
	return h.openIn(sub, platform, last, hasLast, nil)
}

// openIn is open within the stream limits of the applications in caps (17.16).
func (h *hub) openIn(sub subscription, platform bool, last int64, hasLast bool, caps []appCap) (*stream, opening, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.draining {
		return nil, opening{}, errDraining
	}
	if err := h.admitLocked(sub.principal, caps); err != nil {
		return nil, opening{}, err
	}
	s := &stream{
		sub: sub, platform: platform, topics: make(map[string]struct{}, len(sub.topics)),
		done: make(chan struct{}), ready: make(chan struct{}, 1),
	}
	for _, t := range sub.topics {
		s.topics[t] = struct{}{}
		add(h.byTopic, t, s)
	}
	if sub.principal != "" {
		add(h.byPrincipal, sub.principal, s)
	}
	if h.cfg.perApp {
		s.apps = applicationsOf(sub.topics)
		for _, a := range s.apps {
			h.byApp[a]++
		}
	}
	h.all[s] = struct{}{}
	h.m.open.Set(float64(len(h.all)))
	op := opening{latest: h.last}
	if hasLast && last >= h.from && last <= h.last {
		op.memory = true
		n := len(h.ring)
		for i := range n {
			f := h.ring[(h.head+i)%n]
			if f.ID > last && s.wants(f.Entry) {
				op.replay = append(op.replay, f)
			}
		}
	}
	return s, op, nil
}

func add(m map[string]map[*stream]struct{}, key string, s *stream) {
	set := m[key]
	if set == nil {
		set = map[*stream]struct{}{}
		m[key] = set
	}
	set[s] = struct{}{}
}

func remove(m map[string]map[*stream]struct{}, key string, s *stream) {
	if set := m[key]; set != nil {
		delete(set, s)
		if len(set) == 0 {
			delete(m, key)
		}
	}
}

// close ends a stream for reason; a stream already ended keeps its first reason.
func (h *hub) close(s *stream, reason string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.endLocked(s, reason)
}

func (h *hub) endLocked(s *stream, reason string) {
	if s.ended {
		return
	}
	s.ended, s.reason = true, reason
	for t := range s.topics {
		remove(h.byTopic, t, s)
	}
	if s.sub.principal != "" {
		remove(h.byPrincipal, s.sub.principal, s)
	}
	for _, a := range s.apps {
		if h.byApp[a]--; h.byApp[a] <= 0 {
			delete(h.byApp, a)
		}
	}
	delete(h.all, s)
	h.m.open.Set(float64(len(h.all)))
	h.m.ended.With(reason).Inc()
	close(s.done)
}

// endReason returns why an ended stream ended.
func (h *hub) endReason(s *stream) string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return s.reason
}

// endHolder ends the streams of a holder (revocation, section 7).
func (h *hub) endHolder(holder Holder) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for s := range h.all {
		sub := s.sub
		if (holder.Principal != "" && holder.Principal == sub.principal) ||
			(holder.Device != "" && holder.Device == sub.device) ||
			(holder.Session != "" && holder.Session == sub.session) {
			h.endLocked(s, endRevoked)
		}
	}
}

// watchDrain watches a server's drain signal (httpfw.Draining): once it closes, every stream ends at
// once. Each signal is watched once; the watch ends with the hub.
func (h *hub) watchDrain(signal <-chan struct{}) {
	h.mu.Lock()
	watched := h.drains[signal]
	h.drains[signal] = true
	h.mu.Unlock()
	if watched {
		return
	}
	go func() {
		select {
		case <-signal:
			h.drain()
		case <-h.stopped:
		}
	}()
}

// drain ends every open stream at once with the reason drain, in one pass under the lock, and opens
// no stream after it (push contract section 8: a draining instance ends its streams; the clients
// reconnect to another instance after the retry, with jitter). Each stream's goroutine sees done
// closed and returns, so the server's drain does not wait for a write or a heartbeat.
func (h *hub) drain() {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.draining = true
	n := len(h.all)
	for s := range h.all {
		s.ended, s.reason = true, endDrain
		close(s.done)
	}
	clear(h.all)
	clear(h.byTopic)
	clear(h.byPrincipal)
	clear(h.byApp)
	h.m.open.Set(0)
	if n > 0 {
		h.m.ended.With(endDrain).Add(float64(n))
	}
}

// openCount returns the number of open streams (tests).
func (h *hub) openCount() int {
	h.mu.Lock()
	defer h.mu.Unlock()
	return len(h.all)
}

// lastID returns the highest id dispatched.
func (h *hub) lastID() int64 {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.last
}

// dispatch hands a page of the log to the streams of each push's topic, in id order. A stream whose
// buffer is full is ended (no event: a slow client cannot get one); it resumes from its
// Last-Event-ID like any other drop.
func (h *hub) dispatch(page []*frame) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, f := range page {
		if f.ID <= h.last {
			continue
		}
		h.last = f.ID
		if f.OutboxID != 0 && !h.seen.add(f.OutboxID) {
			continue // logged twice (A2): delivered once
		}
		h.remember(f)
		for s := range h.byTopic[f.Topic] {
			if !s.accepts(f.Entry) {
				continue
			}
			if !s.offer(f, h.cfg.buffer) {
				h.endLocked(s, endSlow)
			}
		}
	}
	h.m.logID.Set(float64(h.last))
}

// remember keeps f among the push.recent most recent pushes.
func (h *hub) remember(f *frame) {
	switch {
	case h.cfg.recent == 0:
		h.from = f.ID
	case len(h.ring) < h.cfg.recent:
		h.ring = append(h.ring, f)
	default:
		h.from = h.ring[h.head].ID
		h.ring[h.head] = f
		h.head = (h.head + 1) % len(h.ring)
	}
}

// framesOf encodes a page of the log, once for every stream.
func framesOf(page []Entry) []*frame {
	out := make([]*frame, len(page))
	for i, e := range page {
		out[i] = newFrame(e)
	}
	return out
}

// isDraining reports whether the drain began.
func (h *hub) isDraining() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.draining
}

// catchUp reads the log after the last id dispatched until it has everything, until stop ends, or
// until the drain begins (no stream is left to hear it, and the database is not given work while the
// instance closes its connections); a read under way then is finished, not cancelled (opTimeout).
func (h *hub) catchUp(stop context.Context) error {
	for stop.Err() == nil && !h.isDraining() {
		ctx, cancel := opContext(stop)
		page, err := h.log.ReadAfter(ctx, h.lastID(), pageSize)
		cancel()
		if err != nil {
			return err
		}
		h.dispatch(framesOf(page))
		if len(page) < pageSize {
			return nil
		}
	}
	return nil
}

// poke wakes the hub to read the log.
func (h *hub) poke() {
	select {
	case h.wake <- struct{}{}:
	default:
	}
}

// run follows the log until ctx ends: woken by the driver, and every poll interval in case a
// wake-up was missed or the driver's connection is down.
func (h *hub) run(ctx context.Context) {
	defer h.stopOnce.Do(func() { close(h.stopped) })
	go h.follow(ctx)
	tick := time.NewTicker(h.cfg.poll)
	defer tick.Stop()
	for {
		if err := h.catchUp(ctx); err != nil && ctx.Err() == nil {
			h.logger.Warn("push: reading the log failed; will retry", slog.String("error", err.Error()))
		}
		select {
		case <-ctx.Done():
			return
		case <-h.wake:
		case <-tick.C:
		}
	}
}

// follow keeps the driver's follower running, with a growing pause after a failure.
func (h *hub) follow(ctx context.Context) {
	pause := time.Second
	for {
		started := h.clock.Now()
		grown := func() {
			h.followOnce.Do(func() { close(h.following) })
			h.poke()
		}
		err := h.log.Follow(ctx, Follower{Grown: grown, End: h.endHolder})
		if ctx.Err() != nil {
			return
		}
		if h.clock.Now().Sub(started) > time.Minute {
			pause = time.Second
		}
		h.logger.Warn("push: following the log failed; polling until it is back", slog.Any("error", err), slog.Duration("retry_in", pause))
		t := time.NewTimer(pause)
		select {
		case <-ctx.Done():
			t.Stop()
			return
		case <-t.C:
		}
		pause = min(30*time.Second, pause*2)
	}
}

// pageRead is one read of the log for replays, shared by every replay that reads after the same id
// while it is under way: streams resuming together from the same Last-Event-ID (the followers of a
// broadcast topic) read each page once.
type pageRead struct {
	done    chan struct{}
	page    []Entry // read-only once done
	trimmed int64
	err     error
}

// readPage reads pageSize entries after after, then the log's Trimmed, sharing the read with any
// replay already reading after the same id. The read is not cancelled with ctx (another stream may be
// waiting for it); it is bounded by opTimeout.
func (h *hub) readPage(ctx context.Context, after int64) ([]Entry, int64, error) {
	h.pmu.Lock()
	if r, ok := h.pages[after]; ok {
		h.pmu.Unlock()
		select {
		case <-r.done:
			return r.page, r.trimmed, r.err
		case <-ctx.Done():
			return nil, 0, ctx.Err()
		}
	}
	r := &pageRead{done: make(chan struct{})}
	h.pages[after] = r
	h.pmu.Unlock()
	rctx, cancel := opContext(ctx)
	r.page, r.err = h.log.ReadAfter(rctx, after, pageSize)
	if r.err == nil {
		r.trimmed, r.err = h.log.Trimmed(rctx)
	}
	cancel()
	h.pmu.Lock()
	delete(h.pages, after)
	h.pmu.Unlock()
	close(r.done)
	return r.page, r.trimmed, r.err
}

// replayLog writes the stream's pushes after after, up to upto, from the log, one page at a time:
// it holds one page (at most pageSize entries) and a bounded set of recent outbox ids, whatever the
// length of the replay. It returns true when the log no longer keeps everything after the cursor:
// the caller sends a reset. The check follows each read, so a row pruned before the read is never
// silently skipped.
func (h *hub) replayLog(ctx context.Context, s *stream, after, upto int64, emit func(Entry) error) (bool, error) {
	cursor := after
	// an outbox row logged twice (A2) is logged again by the next relay run, within one relay batch
	// (at most 5000) of the first: remembering the last 10 000 outbox ids is enough
	seen := outboxSeen{capacity: 10_000, set: map[int64]struct{}{}}
	for cursor < upto {
		select {
		case <-s.done:
			return false, errEnded
		default:
		}
		page, trimmed, err := h.readPage(ctx, cursor)
		if err != nil {
			return false, err
		}
		if cursor < trimmed {
			return true, nil
		}
		for _, e := range page {
			if e.ID > upto {
				return false, nil
			}
			cursor = e.ID
			if e.OutboxID != 0 && !seen.add(e.OutboxID) {
				continue
			}
			if s.wants(e) {
				if err := emit(e); err != nil {
					return false, err
				}
			}
		}
		if len(page) < pageSize {
			break
		}
	}
	return false, nil
}

// outboxSeen remembers the most recent outbox ids dispatched (bounded), for drivers that can log a
// row twice.
type outboxSeen struct {
	capacity int
	set      map[int64]struct{}
	order    []int64
	next     int
}

// add reports false when id was seen before.
func (o *outboxSeen) add(id int64) bool {
	if _, dup := o.set[id]; dup {
		return false
	}
	if len(o.order) < o.capacity {
		o.order = append(o.order, id)
	} else {
		delete(o.set, o.order[o.next])
		o.order[o.next] = id
		o.next = (o.next + 1) % o.capacity
	}
	o.set[id] = struct{}{}
	return true
}
