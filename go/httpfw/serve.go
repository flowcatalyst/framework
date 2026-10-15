package httpfw

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/metrics"
)

// Server is the HTTP server: the dispatcher behind net/http's server, with the body limit, the
// timeouts, the health and metrics endpoints and a graceful drain. [NewServer] makes one; it is
// the application's [fw.Serve].
type Server struct {
	dispatcher *Dispatcher
	srv        *http.Server
	log        *slog.Logger
	host       string
	port       int
	grace      time.Duration
	boot       *fw.Boot

	listener net.Listener
	started  atomic.Bool
	draining atomic.Bool
}

// NewServer builds the server from the http.* settings: it checks the route tables together (a
// *fw.StartupError lists every problem), adds the health endpoints (GET /health/live and
// /health/ready) and, with http.metrics.enabled, the Prometheus endpoint, and registers the start
// action that binds the address (so an address that cannot be bound fails the start). Pass the
// result to Launch as the application's Serve.
func NewServer(b *fw.Boot, d *Declared, tables []*Routes, opts ...Option) (*Server, error) {
	log := b.Logger(ModuleID)
	s := &Server{
		host: d.host.Get(b), port: d.port.Get(b), boot: b, log: log,
		grace: b.ShutdownDrain(),
	}
	if g, ok := d.shutdownGrace.Lookup(b); ok {
		s.grace = g
	}
	own := NewRoutes(ModuleID)
	own.Handle("GET /health/live", func(w http.ResponseWriter, _ *http.Request) error {
		return JSON(w, http.StatusOK, map[string]string{"status": "UP"})
	})
	own.Handle("GET /health/ready", s.ready)
	if d.metricsEnabled.Get(b) {
		path := d.metricsPath.Get(b)
		var token *config.Secret
		if t, ok := d.metricsToken.Lookup(b); ok && strings.TrimSpace(t.Reveal()) != "" {
			token = &t
		} else {
			log.Warn("the metrics endpoint is enabled without http.metrics.bearer-token", slog.String("path", path))
		}
		if !b.Metrics().Readable() {
			log.Warn("the metrics endpoint is enabled but app.metrics.driver records nothing")
		}
		h := metrics.Handler(b.Metrics(), token)
		own.Handle("GET "+path, func(w http.ResponseWriter, r *http.Request) error {
			h.ServeHTTP(w, r)
			return nil
		})
	}
	all := append(append([]*Routes(nil), tables...), own)
	base := []Option{
		WithEnvironment(b.Environment()), WithMaxBodyBytes(d.maxBody.Get(b)), WithLogger(log), WithClock(b.Clock()),
		WithMetrics(b.Metrics()), WithDeclared(d.declaredOperations()...),
	}
	disp, err := Compile(all, append(base, opts...)...)
	if err != nil {
		return nil, err
	}
	s.dispatcher = disp
	for _, line := range disp.RouteLines() {
		log.Info("route " + line)
	}
	timeout := d.requestTimeout.Get(b)
	s.srv = &http.Server{
		Handler:           http.HandlerFunc(s.serveHTTP),
		ReadHeaderTimeout: timeout,
		ReadTimeout:       timeout,
		IdleTimeout:       timeout,
		ErrorLog:          slog.NewLogLogger(log.Handler(), slog.LevelWarn),
		BaseContext:       func(net.Listener) context.Context { return context.WithoutCancel(context.Background()) }, //nolint:forbidigo // a server's base context starts from Background
	}
	b.OnStarted("http:listen", s.listen)
	return s, nil
}

func (s *Server) listen(ctx context.Context) error {
	addr := net.JoinHostPort(s.host, strconv.Itoa(s.port))
	var lc net.ListenConfig
	ln, err := lc.Listen(ctx, "tcp", addr)
	if err != nil {
		return fmt.Errorf("cannot listen on %s: %w", addr, err)
	}
	s.listener = ln
	s.started.Store(true)
	s.log.Info("HTTP server listening on " + ln.Addr().String())
	return nil
}

// Port returns the bound port (useful when http.port is 0). It is valid once the application has
// started.
func (s *Server) Port() int {
	if s.listener == nil {
		return 0
	}
	return s.listener.Addr().(*net.TCPAddr).Port //nolint:forcetypeassert // a tcp listener
}

// Addr returns the address the server listens on, "" before it has started.
func (s *Server) Addr() string {
	if s.listener == nil {
		return ""
	}
	return s.listener.Addr().String()
}

// ShutdownGrace is how long Serve lets in-flight requests finish (http.shutdown-grace, else
// app.shutdown.drain); the application waits for Serve that long.
func (s *Server) ShutdownGrace() time.Duration { return s.grace }

// Handler returns the dispatcher behind the drain and method guards, for httptest.
func (s *Server) Handler() http.Handler { return http.HandlerFunc(s.serveHTTP) }

// IsDraining reports whether shutdown has begun: new requests get 503 and /health/ready reports
// down.
func (s *Server) IsDraining() bool { return s.draining.Load() }

// Serve implements fw.Serve: it serves until ctx is cancelled, then stops accepting, lets
// in-flight requests finish for up to http.shutdown-grace and closes what is left.
func (s *Server) Serve(ctx context.Context) error {
	if s.listener == nil {
		return errors.New("httpfw: the server was not started (the http:listen start action did not run)")
	}
	errc := make(chan error, 1)
	go func() { errc <- s.srv.Serve(s.listener) }()
	select {
	case err := <-errc:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
	}
	s.draining.Store(true)
	shutdown, cancel := context.WithTimeout(context.WithoutCancel(ctx), s.grace)
	defer cancel()
	if err := s.srv.Shutdown(shutdown); err != nil {
		s.log.Warn("requests still running after the grace period; closing them", slog.Duration("grace", s.grace))
		_ = s.srv.Close()
	}
	<-errc
	return nil
}

// serveHTTP guards what the dispatcher does not see: a request that arrives during the drain, and a
// method the framework does not route.
func (s *Server) serveHTTP(w http.ResponseWriter, r *http.Request) {
	if s.draining.Load() {
		w.Header().Set("Connection", "close")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		writeEnvelope(w, http.StatusServiceUnavailable, "UNAVAILABLE", "the server is shutting down", nil)
		return
	}
	if !isMethod(r.Method) {
		w.Header().Set("Allow", "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		writeEnvelope(w, http.StatusMethodNotAllowed, "METHOD_NOT_ALLOWED", "method not allowed", nil)
		return
	}
	s.dispatcher.ServeHTTP(w, r)
}

// ready is GET /health/ready: 200 with UP or DEGRADED while every critical check is up, else 503.
func (s *Server) ready(w http.ResponseWriter, r *http.Request) error {
	if s.draining.Load() || (s.started.Load() && !s.boot.Ready()) {
		return JSON(w, http.StatusServiceUnavailable, orderedMap{{"status", "DOWN"}, {"reason", "shutting down"}})
	}
	report := s.boot.HealthReport(r.Context())
	checks := make(orderedMap, 0, len(report.Results))
	for _, res := range report.Results {
		one := orderedMap{{"status", res.Status.String()}}
		if res.Status == fw.Down {
			one = append(one, entry{"reason", res.Reason})
		}
		if res.Criticality == fw.Informational {
			one = append(one, entry{"informational", true})
		}
		checks = append(checks, entry{res.Name, one})
	}
	status := "DOWN"
	switch {
	case report.IsUp():
		status = "UP"
	case report.IsReady():
		status = "DEGRADED"
	}
	code := http.StatusOK
	if !report.IsReady() {
		code = http.StatusServiceUnavailable
	}
	return JSON(w, code, orderedMap{{"status", status}, {"checks", checks}})
}
