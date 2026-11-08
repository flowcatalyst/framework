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

// errLimit is a subscribe or a listen over push.max-streams or push.max-streams-per-principal.
var errLimit = errors.New("push: stream limit")

// subscription is what a stream listens to and who holds it: a stream token's claims.
type subscription struct {
	topics                     []string
	client                     string // the stream's client; the platform client's value for platform-wide
	principal, device, session string
}

// stream is one open stream on this instance.
type stream struct {
	sub      subscription
	topics   map[string]struct{}
	platform bool       // opened in the platform client's context: hears every client
	ch       chan Entry // the live pushes; the hub never blocks on it
	done     chan struct{}
	reason   string // why it ended, set before done is closed
	ended    bool
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
	last        int64   // the highest id dispatched
	from        int64   // the ring holds every push after from
	ring        []Entry // the recent pushes; circular once full, head the oldest
	head        int
	seen        outboxSeen
	byTopic     map[string]map[*stream]struct{}
	byPrincipal map[string]map[*stream]struct{}
	all         map[*stream]struct{}
	wake        chan struct{}
	following   chan struct{} // closed once the driver follows the log for the first time
	followOnce  sync.Once
}

func newHub(log Log, logger *slog.Logger, clock fw.Clock, m *hubMetrics, cfg hubConfig, latest int64) *hub {
	h := &hub{
		log: log, logger: logger, clock: clock, m: m, cfg: cfg, last: latest, from: latest,
		seen:    outboxSeen{capacity: max(cfg.recent, 10_000), set: map[int64]struct{}{}},
		byTopic: map[string]map[*stream]struct{}{}, byPrincipal: map[string]map[*stream]struct{}{},
		all: map[*stream]struct{}{}, wake: make(chan struct{}, 1), following: make(chan struct{}),
	}
	m.logID.Set(float64(latest))
	return h
}

// admitLocked checks the limits of section 8 for one more stream of principal.
func (h *hub) admitLocked(principal string) error {
	if len(h.all) >= h.cfg.maxStreams {
		return errLimit
	}
	if principal != "" && len(h.byPrincipal[principal]) >= h.cfg.maxPerPrincipal {
		return errLimit
	}
	return nil
}

// admit reports whether a subscribe of principal is within the limits now.
func (h *hub) admit(principal string) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.admitLocked(principal)
}

// opening is what a stream starts from: the highest id dispatched when it was registered (pushes
// after it come live), and, when the memory holds everything after the client's Last-Event-ID, the
// pushes to replay from it.
type opening struct {
	latest int64
	memory bool
	replay []Entry
}

// open registers a stream. With hasLast, the replay from memory is taken in the same critical
// section, so nothing is evicted between deciding and reading.
func (h *hub) open(sub subscription, platform bool, last int64, hasLast bool) (*stream, opening, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if err := h.admitLocked(sub.principal); err != nil {
		return nil, opening{}, err
	}
	s := &stream{
		sub: sub, platform: platform, topics: make(map[string]struct{}, len(sub.topics)),
		ch: make(chan Entry, h.cfg.buffer), done: make(chan struct{}),
	}
	for _, t := range sub.topics {
		s.topics[t] = struct{}{}
		add(h.byTopic, t, s)
	}
	if sub.principal != "" {
		add(h.byPrincipal, sub.principal, s)
	}
	h.all[s] = struct{}{}
	h.m.open.Set(float64(len(h.all)))
	op := opening{latest: h.last}
	if hasLast && last >= h.from && last <= h.last {
		op.memory = true
		n := len(h.ring)
		for i := range n {
			e := h.ring[(h.head+i)%n]
			if e.ID > last && s.wants(e) {
				op.replay = append(op.replay, e)
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
func (h *hub) dispatch(page []Entry) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, e := range page {
		if e.ID <= h.last {
			continue
		}
		h.last = e.ID
		if e.OutboxID != 0 && !h.seen.add(e.OutboxID) {
			continue // logged twice (A2): delivered once
		}
		h.remember(e)
		for s := range h.byTopic[e.Topic] {
			if !s.accepts(e) {
				continue
			}
			select {
			case s.ch <- e:
			default:
				h.endLocked(s, endSlow)
			}
		}
	}
	h.m.logID.Set(float64(h.last))
}

// remember keeps e among the push.recent most recent pushes.
func (h *hub) remember(e Entry) {
	switch {
	case h.cfg.recent == 0:
		h.from = e.ID
	case len(h.ring) < h.cfg.recent:
		h.ring = append(h.ring, e)
	default:
		h.from = h.ring[h.head].ID
		h.ring[h.head] = e
		h.head = (h.head + 1) % len(h.ring)
	}
}

// catchUp reads the log after the last id dispatched until it has everything.
func (h *hub) catchUp(ctx context.Context) error {
	for {
		page, err := h.log.ReadAfter(ctx, h.lastID(), pageSize)
		if err != nil {
			return err
		}
		h.dispatch(page)
		if len(page) < pageSize {
			return nil
		}
	}
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

// replayLog writes the stream's pushes after after, up to upto, from the log, page by page. It
// returns true when the log no longer keeps everything after the cursor: the caller sends a reset.
// The check follows each read, so a row pruned before the read is never silently skipped.
func (h *hub) replayLog(ctx context.Context, s *stream, after, upto int64, emit func(Entry) error) (bool, error) {
	cursor := after
	var seen map[int64]struct{}
	for cursor < upto {
		page, err := h.log.ReadAfter(ctx, cursor, pageSize)
		if err != nil {
			return false, err
		}
		trimmed, err := h.log.Trimmed(ctx)
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
			if e.OutboxID != 0 {
				if seen == nil {
					seen = map[int64]struct{}{}
				}
				if _, dup := seen[e.OutboxID]; dup {
					continue
				}
				seen[e.OutboxID] = struct{}{}
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
