package push

import (
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/internal/pushrow"
)

// The stream's timing (http contract 13.5).
const (
	streamRetry     = 3 * time.Second
	streamHeartbeat = 25 * time.Second
)

// The reasons of a reset event (push contract section 7).
const (
	resetRetention = "retention"
	resetUnknownID = "unknown-id"
)

type subscribeBody struct {
	Topics  []string `json:"topics"`
	Tickets []string `json:"tickets"`
}

type subscribeAnswer struct {
	StreamToken string   `json:"streamToken"`
	ExpiresAt   string   `json:"expiresAt"`
	Granted     []string `json:"granted"`
	Denied      []Denial `json:"denied"`
}

func draining(r *http.Request) bool {
	select {
	case <-httpfw.Draining(r):
		return true
	default:
		return false
	}
}

func unavailable() error {
	return httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", "the server is shutting down; reconnect to another instance")
}

func pushLimit() error {
	return httpfw.NewError(http.StatusTooManyRequests, "PUSH_LIMIT", "too many push streams are open; try again later")
}

// subscribe is POST {base}/push/subscriptions (push contract 6.1).
func (p *Pushes) subscribe(w http.ResponseWriter, r *http.Request) error {
	if draining(r) {
		return unavailable()
	}
	body, err := httpfw.DecodeJSON[subscribeBody](r)
	if err != nil {
		return err
	}
	topics, err := p.checkTopics(body.Topics)
	if err != nil {
		return err
	}
	creds, present := Credentials{}, false
	if p.auth != nil {
		c, ok, err := p.auth.Authenticate(r)
		if err != nil {
			return err
		}
		creds, present = c, ok
	}
	now := p.clock.Now()
	d, err := p.granter.decide(topics, body.Tickets, creds, present, now)
	if err != nil {
		return err
	}
	if err := p.hub.admit(d.sub.principal); err != nil {
		return pushLimit()
	}
	token, err := p.tokens.sign(streamClaims{
		Client: d.sub.client, Principal: d.sub.principal, Device: d.sub.device, Session: d.sub.session,
		Topics: d.granted, Exp: d.expires.Unix(),
	})
	if err != nil {
		return err
	}
	denied := d.denied
	if denied == nil {
		denied = []Denial{}
	}
	return httpfw.JSON(w, http.StatusOK, subscribeAnswer{
		StreamToken: token, ExpiresAt: d.expires.Format(time.RFC3339), Granted: d.granted, Denied: denied,
	})
}

// checkTopics checks the requested topics: 1 to push.max-topics, each valid; duplicates dropped.
func (p *Pushes) checkTopics(topics []string) ([]string, error) {
	if len(topics) == 0 {
		return nil, httpfw.Validation("topics must name at least one topic")
	}
	if len(topics) > p.cfg.maxTopics {
		return nil, httpfw.Validation(fmt.Sprintf("at most %d topics per subscribe (push.max-topics)", p.cfg.maxTopics))
	}
	out := make([]string, 0, len(topics))
	seen := make(map[string]bool, len(topics))
	for i, t := range topics {
		if err := pushrow.CheckTopic(t); err != nil {
			return nil, httpfw.Validation(fmt.Sprintf("topics[%d] is not a topic: {application}:{segment}(:{segment})*", i))
		}
		if !seen[t] {
			seen[t] = true
			out = append(out, t)
		}
	}
	return out, nil
}

// keys is GET {base}/push/keys: the ticket keys' JWKS.
func (p *Pushes) keys(w http.ResponseWriter, _ *http.Request) error {
	w.Header().Set("Cache-Control", "public, max-age=300")
	httpfw.Bytes(w, http.StatusOK, "application/json", p.jwks)
	return nil
}

// listen is GET {base}/push/stream?st=... (push contract 6.2 and section 7).
func (p *Pushes) listen(w http.ResponseWriter, r *http.Request) error {
	if draining(r) {
		return unavailable()
	}
	st, _, err := httpfw.QueryValue(r, "st")
	if err != nil {
		return err
	}
	claims, code := p.tokens.verify(st, p.clock.Now())
	if code != "" {
		msg := "the stream token is not valid; subscribe again"
		if code == codeTokenExpired {
			msg = "the stream token has expired; subscribe again"
		}
		return httpfw.NewError(http.StatusUnauthorized, code, msg)
	}
	sub := subscription{topics: claims.Topics, client: claims.Client, principal: claims.Principal, device: claims.Device, session: claims.Session}
	last, hasLast, badLast := lastEventID(r)
	drain := httpfw.Draining(r)
	p.hub.watchDrain(drain)
	s, op, err := p.hub.open(sub, claims.Client == platformClient, last, hasLast)
	if err != nil {
		switch {
		case errors.Is(err, errLimit):
			return pushLimit()
		case errors.Is(err, errDraining):
			return unavailable()
		}
		return err
	}
	reason := endClient
	defer func() { p.hub.close(s, reason) }()

	h := w.Header()
	h.Set("Content-Type", "text/event-stream")
	h.Set("Cache-Control", "no-cache")
	h.Set("X-Accel-Buffering", "no")
	w.WriteHeader(http.StatusOK)
	out := &sseWriter{w: w, rc: http.NewResponseController(w)}
	if !out.send(retryField(streamRetry)) || !out.send(subscribedEvent(claims.Topics, op.latest)) {
		return nil
	}
	sent := op.latest // live pushes at or below it are already accounted for
	var scratch []byte
	emit := func(e Entry) error { // a push replayed from the log, encoded for this stream
		scratch = appendPushEvent(scratch[:0], e)
		if !out.write(scratch) || !out.flush() {
			return io.ErrClosedPipe
		}
		p.metrics.delivered.Inc()
		return nil
	}
	if badLast { // a cursor that names no push cannot be resumed from: the client reloads its state
		p.metrics.resets.With(resetUnknownID).Inc()
		if !out.send(resetEvent(resetUnknownID, op.latest)) {
			return nil
		}
	}
	if hasLast {
		switch {
		case last > op.latest:
			top, err := p.log.Latest(r.Context())
			if err != nil {
				p.logger.Warn("push: reading the log's last id failed; ending the stream", slog.String("error", err.Error()))
				return nil
			}
			if last > top {
				p.metrics.resets.With(resetUnknownID).Inc()
				if !out.send(resetEvent(resetUnknownID, op.latest)) {
					return nil
				}
			} else {
				sent = last // this instance is behind; skip what the client has
			}
		case last == op.latest:
		case op.memory:
			p.metrics.replaySource.With("memory").Inc()
			for _, f := range op.replay {
				if !out.write(f.wire) {
					return nil
				}
			}
			if !out.flush() {
				return nil
			}
			p.metrics.delivered.Add(float64(len(op.replay)))
		default:
			p.metrics.replaySource.With("log").Inc()
			reset, err := p.hub.replayLog(r.Context(), s, last, op.latest, emit)
			switch {
			case errors.Is(err, io.ErrClosedPipe):
				return nil
			case errors.Is(err, errEnded): // the loop below sees why (auth-expired for a revocation)
			case err != nil:
				if r.Context().Err() == nil {
					p.logger.Warn("push: the replay from the log failed; ending the stream", slog.String("error", err.Error()))
				}
				return nil
			case reset:
				p.metrics.resets.With(resetRetention).Inc()
				if !out.send(resetEvent(resetRetention, op.latest)) {
					return nil
				}
			}
		}
	}
	scratch = nil

	expiry := time.NewTimer(time.Unix(claims.Exp, 0).Sub(p.clock.Now()))
	defer expiry.Stop()
	heartbeat := time.NewTicker(streamHeartbeat)
	defer heartbeat.Stop()
	for {
		select {
		case <-r.Context().Done():
			return nil
		case <-drain:
			reason = endDrain
			return nil
		case <-s.done:
			if p.hub.endReason(s) == endRevoked {
				out.send(authExpiredEvent())
			}
			return nil
		case <-expiry.C:
			reason = endExpired
			out.send(authExpiredEvent())
			return nil
		case <-s.ready:
			// every push waiting, written in id order and flushed once; the frames are shared
			n := 0
			for f := s.next(); f != nil; f = s.next() {
				if f.ID <= sent {
					continue
				}
				if !out.write(f.wire) {
					return nil
				}
				sent = f.ID
				n++
			}
			if n > 0 {
				if !out.flush() {
					return nil
				}
				p.metrics.delivered.Add(float64(n))
				heartbeat.Reset(streamHeartbeat)
			}
		case <-heartbeat.C:
			if !out.send(": ping\n\n") {
				return nil
			}
		}
	}
}

// sseWriter writes and flushes; false once the client has gone.
type sseWriter struct {
	w  io.Writer
	rc *http.ResponseController
}

func (s *sseWriter) send(text string) bool {
	if _, err := io.WriteString(s.w, text); err != nil {
		return false
	}
	return s.flush()
}

// write writes b (a frame's shared bytes: never kept or changed by the writer) without flushing.
func (s *sseWriter) write(b []byte) bool {
	_, err := s.w.Write(b)
	return err == nil
}

func (s *sseWriter) flush() bool { return s.rc.Flush() == nil }

// The wire (push contract 6.2 and section 7; conformance/push/wire.json pins these bytes).

func retryField(d time.Duration) string {
	return "retry: " + strconv.FormatInt(d.Milliseconds(), 10) + "\n\n"
}

func pushEvent(e Entry) string { return string(appendPushEvent(nil, e)) }

// appendPushEvent appends the push event of e to b.
func appendPushEvent(b []byte, e Entry) []byte {
	topic := jsonString(e.Topic)
	b = slices.Grow(b, 40+len(e.Type)+len(topic)+len(e.Data))
	b = append(b, "id: "...)
	b = strconv.AppendInt(b, e.ID, 10)
	b = append(b, "\nevent: "...)
	b = append(b, e.Type...)
	b = append(b, "\ndata: {\"topic\":"...)
	b = append(b, topic...)
	b = append(b, ",\"data\":"...)
	b = append(b, e.Data...)
	return append(b, "}\n\n"...)
}

func subscribedEvent(granted []string, latest int64) string {
	list, _ := json.Marshal(granted)
	return "event: subscribed\ndata: {\"granted\":" + string(list) + ",\"latest\":" + strconv.FormatInt(latest, 10) + "}\n\n"
}

func resetEvent(reason string, latest int64) string {
	id := strconv.FormatInt(latest, 10)
	return "id: " + id + "\nevent: reset\ndata: {\"reason\":" + jsonString(reason) + ",\"latest\":" + id + "}\n\n"
}

func authExpiredEvent() string { return "event: auth-expired\ndata: {}\n\n" }

func jsonString(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

// lastEventID reads the client's cursor: the Last-Event-ID header, else the lastEventId query
// parameter (an EventSource cannot set headers). Empty or blank is no cursor (hasLast false). A
// value that is not a non-negative integer is a cursor that names no push (bad true, hasLast
// false): the stream is answered with a reset, never silently started live.
func lastEventID(r *http.Request) (last int64, hasLast, bad bool) {
	raw := strings.TrimSpace(r.Header.Get("Last-Event-ID"))
	if raw == "" {
		v, _, err := httpfw.QueryValue(r, "lastEventId")
		if err != nil {
			return 0, false, false
		}
		raw = strings.TrimSpace(v)
	}
	if raw == "" {
		return 0, false, false
	}
	n, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || n < 0 {
		return 0, false, true
	}
	return n, true, false
}
