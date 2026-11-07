// Package sse writes server-sent event streams (text/event-stream) from an httpfw handler: the
// events a client missed since its Last-Event-ID, read through a [Replay] the application supplies,
// then the live ones, each once and in id order; a heartbeat comment while idle; and an end when the
// client goes, the live source ends, or the server begins to shut down ([httpfw.Draining]), so an
// open stream never holds the drain.
//
// The caller subscribes to its live source first and then calls [Serve], which replays and then
// follows the subscription, skipping what the replay already sent:
//
//	rt.HandleContext("GET /api/stores/{store}/events", func(w http.ResponseWriter, r *http.Request, cx fw.Context) error {
//		live, cancel := hub.Subscribe(r.PathValue("store"))
//		defer cancel()
//		return sse.Serve(w, r, live, sse.Options{Replay: log.After(r.PathValue("store"))})
//	})
package sse

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/httpfw"
)

// Defaults of [Options].
const (
	// DefaultHeartbeat is how often an idle stream sends a comment, so proxies keep it open.
	DefaultHeartbeat = 25 * time.Second
	// DefaultReplayLimit is the most events one [Replay] call is asked for.
	DefaultReplayLimit = 1000
	// DefaultRetry is the reconnection delay the stream asks the client for.
	DefaultRetry = 3 * time.Second
)

// Event is one server-sent event.
type Event struct {
	// ID is the event's position in its stream, increasing; it is sent as the event's id, which the
	// client sends back as Last-Event-ID when it reconnects. 0 sends no id, and such an event is
	// never skipped as already sent.
	ID int64
	// Type is the event's type (the client's addEventListener name); "" is the default, "message".
	// It must not contain a line break.
	Type string
	// Data is the event's data; each line is sent as its own data field, and the client joins them
	// with "\n".
	Data string
}

// Replay reads the events of a stream after the cursor after, in id order, at most limit of them. A
// page shorter than limit means the replay is complete.
type Replay func(ctx context.Context, after int64, limit int) ([]Event, error)

// Options configures [Serve]. The zero value has no replay and the defaults.
type Options struct {
	// Replay reads what a reconnecting client missed; nil when the stream has no history (a
	// Last-Event-ID is then ignored and the stream starts live).
	Replay Replay
	// ReplayLimit is the page size of a Replay call (default [DefaultReplayLimit]).
	ReplayLimit int
	// Heartbeat is how often an idle stream sends a comment (default [DefaultHeartbeat]).
	Heartbeat time.Duration
	// Retry is the reconnection delay sent to the client (default [DefaultRetry]).
	Retry time.Duration
}

// Serve writes a server-sent event stream: the response headers (text/event-stream, no-cache, no
// proxy buffering) and the reconnection delay; when the request has a Last-Event-ID (the header, or
// the lastEventId query parameter for EventSource polyfills) and the options have a Replay, every
// event after it, page by page; then the events of live, skipping any whose id was already sent;
// and a heartbeat comment whenever the stream has been idle for the heartbeat interval.
//
// It returns nil when the client goes, when live is closed (the source ended or dropped a slow
// stream; the client reconnects with its Last-Event-ID) or when the server begins to shut down
// ([httpfw.Draining]). It returns an error when the replay fails or an event's type has a line
// break; the response has begun by then, so the error is logged, not answered.
//
// http.request-timeout does not end a stream: net/http clears the read deadline once the request
// has been read, and the server sets no write timeout.
func Serve(w http.ResponseWriter, r *http.Request, live <-chan Event, opts Options) error {
	if opts.ReplayLimit <= 0 {
		opts.ReplayLimit = DefaultReplayLimit
	}
	if opts.Heartbeat <= 0 {
		opts.Heartbeat = DefaultHeartbeat
	}
	if opts.Retry <= 0 {
		opts.Retry = DefaultRetry
	}
	rc := http.NewResponseController(w)
	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no") // proxies must not buffer the stream
	w.WriteHeader(http.StatusOK)
	s := &stream{w: w, rc: rc}
	if !s.send("retry: " + strconv.FormatInt(opts.Retry.Milliseconds(), 10) + "\n\n") {
		return nil
	}
	var sent int64
	if last, ok := lastEventID(r); ok && opts.Replay != nil {
		sent = last
		for {
			page, err := opts.Replay(r.Context(), sent, opts.ReplayLimit)
			if err != nil {
				if r.Context().Err() != nil {
					return nil
				}
				return fmt.Errorf("sse: the replay after %d failed: %w", sent, err)
			}
			for _, e := range page {
				ok, err := s.event(e)
				if err != nil || !ok {
					return err
				}
				if e.ID > sent {
					sent = e.ID
				}
			}
			if len(page) < opts.ReplayLimit {
				break
			}
		}
	}
	heartbeat := time.NewTicker(opts.Heartbeat)
	defer heartbeat.Stop()
	draining := httpfw.Draining(r)
	for {
		select {
		case <-r.Context().Done():
			return nil
		case <-draining:
			return nil
		case e, open := <-live:
			if !open {
				return nil
			}
			if e.ID != 0 && e.ID <= sent {
				continue // the replay sent it
			}
			ok, err := s.event(e)
			if err != nil || !ok {
				return err
			}
			if e.ID > sent {
				sent = e.ID
			}
			heartbeat.Reset(opts.Heartbeat)
		case <-heartbeat.C:
			if !s.send(": ping\n\n") {
				return nil
			}
		}
	}
}

// errLineBreak is an event type that would inject fields into the stream.
var errLineBreak = errors.New("sse: an event type must not contain a line break")

type stream struct {
	w  io.Writer
	rc *http.ResponseController
}

// send writes and flushes text; false when the client has gone.
func (s *stream) send(text string) bool {
	if _, err := io.WriteString(s.w, text); err != nil {
		return false
	}
	return s.rc.Flush() == nil
}

// event writes one event; false (with a nil error) when the client has gone.
func (s *stream) event(e Event) (bool, error) {
	if strings.ContainsAny(e.Type, "\r\n") {
		return false, errLineBreak
	}
	var b strings.Builder
	if e.ID != 0 {
		b.WriteString("id: " + strconv.FormatInt(e.ID, 10) + "\n")
	}
	if e.Type != "" {
		b.WriteString("event: " + e.Type + "\n")
	}
	data := strings.ReplaceAll(strings.ReplaceAll(e.Data, "\r\n", "\n"), "\r", "\n")
	for line := range strings.SplitSeq(data, "\n") {
		b.WriteString("data: " + line + "\n")
	}
	b.WriteString("\n")
	return s.send(b.String()), nil
}

// lastEventID reads the client's cursor: the Last-Event-ID header, else the lastEventId query
// parameter. Anything but a non-negative integer is no cursor.
func lastEventID(r *http.Request) (int64, bool) {
	raw := strings.TrimSpace(r.Header.Get("Last-Event-ID"))
	if raw == "" {
		v, _, err := httpfw.QueryValue(r, "lastEventId")
		if err != nil {
			return 0, false
		}
		raw = strings.TrimSpace(v)
	}
	if raw == "" {
		return 0, false
	}
	n, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || n < 0 {
		return 0, false
	}
	return n, true
}
