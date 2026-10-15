package httpfw

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"runtime/debug"
	"slices"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
	"github.com/flowcatalyst/framework/go/metrics"
)

const (
	correlationHeader = "X-Correlation-Id"
	statusClientGone  = 499 // what the access log records for a request the client abandoned
)

var validCorrelation = regexp.MustCompile(`^[A-Za-z0-9._-]{1,100}$`)

// Dispatcher runs a request through the full pipeline: correlation id, error mapping, access log,
// global middleware, routing, context resolution, route middleware, handler. It is an
// http.Handler; the server and httptest both call it.
type Dispatcher struct {
	router *router
	global []Middleware
	spa    *spa.Handler
	lines  []string
	opts   options

	requests  *metrics.Counter
	durations *metrics.Timer
}

// ServeHTTP implements http.Handler.
func (d *Dispatcher) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	st := &reqState{correlation: correlationOf(r), maxBody: d.opts.maxBody, logger: d.opts.logger}
	r = r.WithContext(context.WithValue(r.Context(), stateKey{}, st))
	h := w.Header()
	h.Set(correlationHeader, st.correlation.String())
	h.Set("X-Content-Type-Options", "nosniff")
	rec := &recorder{ResponseWriter: w, head: r.Method == http.MethodHead}

	pipeline := d.route
	for _, mw := range slices.Backward(d.global) {
		pipeline = mw(pipeline)
	}
	pipeline = d.accessLog(pipeline)
	pipeline = d.errorMapping(pipeline)
	_ = pipeline(rec, r)
}

func correlationOf(r *http.Request) fw.CorrelationID {
	if v := r.Header.Get(correlationHeader); validCorrelation.MatchString(v) {
		if id, err := fw.ParseCorrelationID(v); err == nil {
			return id
		}
	}
	return fw.NewCorrelationID()
}

// recorder remembers the status written, and drops the body of a HEAD request (the headers, among
// them the Content-Length a handler set, stay).
type recorder struct {
	http.ResponseWriter
	status int
	wrote  bool
	head   bool
}

func (r *recorder) WriteHeader(status int) {
	if r.wrote {
		return
	}
	r.status, r.wrote = status, true
	r.ResponseWriter.WriteHeader(status)
}

func (r *recorder) Write(b []byte) (int, error) {
	if !r.wrote {
		r.WriteHeader(http.StatusOK)
	}
	if r.head {
		return len(b), nil
	}
	return r.ResponseWriter.Write(b)
}

// Unwrap lets http.ResponseController reach the real writer.
func (r *recorder) Unwrap() http.ResponseWriter { return r.ResponseWriter }

func asRecorder(w http.ResponseWriter) *recorder {
	rec, _ := w.(*recorder)
	return rec
}

// errorMapping turns returned errors and panics into the envelope.
func (d *Dispatcher) errorMapping(next HandlerFunc) HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) (_ error) {
		defer func() {
			if p := recover(); p != nil {
				d.opts.logger.Error("unhandled panic", slog.String("correlation_id", stateOf(r).correlation.String()),
					slog.Any("panic", p), slog.String("stack", string(debug.Stack())))
				d.answerInternal(w)
			}
		}()
		err := next(w, r)
		if err == nil {
			return nil
		}
		cid := stateOf(r).correlation.String()
		switch {
		case canceled(r, err):
			d.opts.logger.Debug("client disconnected", slog.String("correlation_id", cid))
		case isService(err):
			se, _ := errors.AsType[ServiceError](err)
			if rec := asRecorder(w); rec != nil && rec.wrote {
				d.opts.logger.Error("error after the response began", slog.String("correlation_id", cid), slog.String("error", err.Error()))
				return nil
			}
			writeEnvelope(w, se.Status(), se.Code(), se.Message(), serviceDetails(se))
		default:
			d.opts.logger.Error("unhandled error", slog.String("correlation_id", cid), slog.String("error", err.Error()))
			d.answerInternal(w)
		}
		return nil
	}
}

func isService(err error) bool {
	_, ok := errors.AsType[ServiceError](err)
	return ok
}

func (d *Dispatcher) answerInternal(w http.ResponseWriter) {
	if rec := asRecorder(w); rec != nil && rec.wrote {
		return
	}
	writeEnvelope(w, http.StatusInternalServerError, "INTERNAL", "internal error", nil)
}

// accessLog logs method, route pattern, status, duration and correlation id (never the query or a
// body) and records the request metrics with the same labels. The route label is the matched
// pattern, "(spa)" or "<unmatched>", so request paths never become label values.
func (d *Dispatcher) accessLog(next HandlerFunc) HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		start := d.opts.clock.Now()
		err := next(w, r)
		took := d.opts.clock.Now().Sub(start)
		st := stateOf(r)
		status := http.StatusOK
		switch rec := asRecorder(w); {
		case rec != nil && rec.wrote:
			status = rec.status
		case err != nil:
			status = statusOf(r, err)
		}
		route := st.route
		if route == "" {
			route = "<unmatched>"
		}
		if d.requests != nil {
			d.requests.With(r.Method, route, strconv.Itoa(status)).Inc()
			d.durations.With(r.Method, route).Record(took)
		}
		d.opts.logger.Info("request", slog.String("method", r.Method), slog.String("route", route),
			slog.Int("status", status), slog.Int64("took_ms", took.Milliseconds()), slog.String("cid", st.correlation.String()))
		return err
	}
}

func statusOf(r *http.Request, err error) int {
	switch {
	case canceled(r, err):
		return statusClientGone
	case isService(err):
		se, _ := errors.AsType[ServiceError](err)
		return se.Status()
	}
	return http.StatusInternalServerError
}

// rawPath is the request-target's path as sent (still percent-encoded): RequestURI for a server
// request, else the URL's escaped path.
func rawPath(r *http.Request) string {
	target := r.RequestURI
	if target == "" {
		target = r.URL.EscapedPath()
	}
	if !strings.HasPrefix(target, "/") && strings.Contains(target, "://") { // absolute form
		if u, err := url.Parse(target); err == nil {
			target = u.EscapedPath()
		}
	}
	if i := strings.IndexByte(target, '?'); i >= 0 {
		target = target[:i]
	}
	return target
}

// route is the innermost framework step: match the path, then answer 404/405/OPTIONS/SPA or run
// the route.
func (d *Dispatcher) route(w http.ResponseWriter, r *http.Request) error {
	st := stateOf(r)
	if r.ContentLength > st.maxBody {
		return NewError(http.StatusRequestEntityTooLarge, "PAYLOAD_TOO_LARGE", "request body is too large")
	}
	path := rawPath(r)
	if !strings.HasPrefix(path, "/") {
		return NewError(http.StatusNotFound, "NOT_FOUND", "no route for this path")
	}
	parts := strings.Split(path[1:], "/")
	segments := make([]string, len(parts))
	for i, raw := range parts {
		seg, ok := decodeSegment(raw, false)
		if !ok {
			return NewError(http.StatusBadRequest, "VALIDATION", "request is invalid",
				Detail{"path", "INVALID_ENCODING: contains an invalid percent-escape"})
		}
		segments[i] = seg
	}
	method := r.Method
	m := d.router.match(method, segments)
	switch m.kind {
	case notFound:
		if d.spa != nil && d.spa.Answer(w, r, segments, spaResponder{st: st}) {
			return nil
		}
		return NewError(http.StatusNotFound, "NOT_FOUND", "no route for this path")
	case methodNotAllowed:
		w.Header().Set("Allow", allowHeader(m.allowed))
		return NewError(http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "method not allowed for this path")
	case optionsAnswer:
		w.Header().Set("Allow", allowHeader(m.allowed))
		w.WriteHeader(http.StatusNoContent)
		return nil
	case found:
		route := m.route
		st.route = route.pattern.text
		for name, v := range m.params {
			r.SetPathValue(name, v)
		}
		if route.requiresContext {
			cx, err := d.opts.resolver.Resolve(r)
			if err != nil {
				return err
			}
			if cx.IsZero() {
				return errors.New("the ContextResolver returned the zero Context")
			}
			st.cx, st.hasCx = cx.WithCorrelation(st.correlation), true
		}
		return route.handler(w, r)
	}
	return fmt.Errorf("httpfw: unknown match kind %d", m.kind)
}

// spaResponder is what the SPA answers through.
type spaResponder struct{ st *reqState }

func (s spaResponder) Bind(label string) { s.st.route = label }

func (spaResponder) Error(w http.ResponseWriter, status int, code, message string) {
	writeEnvelope(w, status, code, message, nil)
}
