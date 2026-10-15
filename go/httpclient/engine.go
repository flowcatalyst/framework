package httpclient

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"context"
	"crypto/tls"
	"errors"
	"io"
	"log/slog"
	"net"
	"net/http"
	"net/http/httptrace"
	"strings"
	"sync/atomic"
	"time"
)

const userAgent = "flowcatalyst-framework-httpclient"

// Clock gives the wall-clock time: retry waits read a Retry-After date against it, token expiry
// against its readings. fw.Clock satisfies it.
type Clock interface{ Now() time.Time }

type systemClock struct{}

func (systemClock) Now() time.Time { return time.Now() } //nolint:forbidigo // the one reader of the system clock in this package

// Engine owns the connection pools every Service of an application shares (one net/http
// Transport). Create one per application, close it at shutdown; it is safe for concurrent use.
type Engine struct {
	transport *http.Transport
	client    *http.Client
	clock     Clock
	sleep     func(ctx context.Context, d time.Duration) error
	jitter    func(bound int64) int64
	log       *slog.Logger
	closed    atomic.Bool
}

// EngineOption configures [NewEngine].
type EngineOption func(*engineConfig)

type engineConfig struct {
	maxConns int
	clock    Clock
	sleep    func(ctx context.Context, d time.Duration) error
	jitter   func(bound int64) int64
	log      *slog.Logger
	tls      *tls.Config
}

// MaxConnectionsPerHost sets the pooled connections per host, 1 to 1000 (default 50): the
// http-client.max-connections-per-host setting. The Java engine's event-loops setting has no
// counterpart: net/http has no event-loop pool to size.
func MaxConnectionsPerHost(n int) EngineOption { return func(c *engineConfig) { c.maxConns = n } }

// WithClock sets the wall clock (tests; default the system clock).
func WithClock(c Clock) EngineOption { return func(e *engineConfig) { e.clock = c } }

// WithSleep replaces the wait between attempts (tests): it must return ctx.Err() when the context
// ends first.
func WithSleep(f func(ctx context.Context, d time.Duration) error) EngineOption {
	return func(c *engineConfig) { c.sleep = f }
}

// WithJitter replaces the full jitter of the backoff (tests): it gets the backoff's upper bound in
// nanoseconds and returns the wait in nanoseconds, 0 to bound. The default is uniform.
func WithJitter(f func(bound int64) int64) EngineOption {
	return func(c *engineConfig) { c.jitter = f }
}

// WithLogger sets the logger of the per-attempt DEBUG lines: service, method, path template,
// status or failure kind, duration and attempt number; never a query string, header or body.
func WithLogger(l *slog.Logger) EngineOption { return func(c *engineConfig) { c.log = l } }

// WithTLSConfig sets the TLS configuration (tests with a private CA; default the system roots,
// with host verification).
func WithTLSConfig(t *tls.Config) EngineOption { return func(c *engineConfig) { c.tls = t } }

type connectKey struct{}

// NewEngine builds the engine. It returns an error for a setting out of range.
func NewEngine(opts ...EngineOption) (*Engine, error) {
	cfg := engineConfig{maxConns: 50}
	for _, o := range opts {
		o(&cfg)
	}
	if cfg.maxConns < 1 || cfg.maxConns > 1000 {
		return nil, errors.New("maxConnectionsPerHost must be 1 to 1000")
	}
	if cfg.clock == nil {
		cfg.clock = systemClock{}
	}
	if cfg.sleep == nil {
		cfg.sleep = sleepFor
	}
	if cfg.log == nil {
		cfg.log = slog.New(slog.DiscardHandler)
	}
	if cfg.jitter == nil {
		cfg.jitter = fullJitter
	}
	tr := &http.Transport{
		// the connect timeout is per service: it travels in the request's context
		DialContext: func(ctx context.Context, network, addr string) (net.Conn, error) {
			d := net.Dialer{Timeout: 5 * time.Second, KeepAlive: 30 * time.Second}
			if t, ok := ctx.Value(connectKey{}).(time.Duration); ok {
				d.Timeout = t
			}
			return d.DialContext(ctx, network, addr)
		},
		ForceAttemptHTTP2:     true,
		MaxConnsPerHost:       cfg.maxConns,
		MaxIdleConnsPerHost:   cfg.maxConns,
		MaxIdleConns:          0,
		IdleConnTimeout:       90 * time.Second,
		TLSHandshakeTimeout:   10 * time.Second,
		ExpectContinueTimeout: time.Second,
		DisableCompression:    true, // gzip and deflate are decoded here, so the size limit counts bytes received
		TLSClientConfig:       cfg.tls,
	}
	return &Engine{
		transport: tr, clock: cfg.clock, sleep: cfg.sleep, jitter: cfg.jitter, log: cfg.log,
		client: &http.Client{
			Transport:     tr,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}, nil
}

// Close closes idle connections and refuses further requests (KindClosed).
func (e *Engine) Close() {
	e.closed.Store(true)
	e.transport.CloseIdleConnections()
}

func sleepFor(ctx context.Context, d time.Duration) error {
	if d <= 0 {
		return nil
	}
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-t.C:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

type outgoing struct {
	method         string
	target         string
	headers        http.Header
	body           []byte
	connectTimeout time.Duration
	timeout        time.Duration
	maxBytes       int64
}

type incoming struct {
	status  int
	headers http.Header
	body    []byte
}

type failure struct {
	kind  Kind
	cause error
}

// exchange runs one attempt: the whole exchange, body included, within the timeout.
func (e *Engine) exchange(ctx context.Context, out outgoing) (incoming, *failure) {
	if e.closed.Load() {
		return incoming{}, &failure{kind: KindClosed}
	}
	actx, cancel := context.WithTimeout(context.WithValue(ctx, connectKey{}, out.connectTimeout), out.timeout)
	defer cancel()
	var body io.Reader
	if out.body != nil {
		body = bytes.NewReader(out.body)
	}
	req, err := http.NewRequestWithContext(actx, out.method, out.target, body)
	if err != nil {
		return incoming{}, &failure{kind: KindIO, cause: stripURL(err)}
	}
	req.Header = out.headers.Clone()
	req.Header.Set("Accept-Encoding", "gzip, deflate")
	var gotConn atomic.Bool
	req = req.WithContext(httptrace.WithClientTrace(actx, &httptrace.ClientTrace{GotConn: func(httptrace.GotConnInfo) { gotConn.Store(true) }}))

	classify := func(err error, afterConn bool) *failure {
		switch {
		case ctx.Err() != nil:
			return &failure{kind: KindCanceled, cause: ctx.Err()}
		case errors.Is(actx.Err(), context.DeadlineExceeded):
			return &failure{kind: KindTimeout}
		case !afterConn && !gotConn.Load():
			return &failure{kind: KindConnect, cause: stripURL(err)}
		}
		return &failure{kind: KindIO, cause: stripURL(err)}
	}

	resp, err := e.client.Do(req)
	if err != nil {
		return incoming{}, classify(err, false)
	}
	defer resp.Body.Close()
	if resp.ContentLength > out.maxBytes {
		return incoming{}, &failure{kind: KindResponseTooLarge}
	}
	raw, err := io.ReadAll(io.LimitReader(resp.Body, out.maxBytes+1))
	if err != nil {
		return incoming{}, classify(err, true)
	}
	if int64(len(raw)) > out.maxBytes {
		return incoming{}, &failure{kind: KindResponseTooLarge}
	}
	decoded, tooLarge, err := decode(resp.Header.Get("Content-Encoding"), raw, out.maxBytes)
	switch {
	case tooLarge:
		return incoming{}, &failure{kind: KindResponseTooLarge}
	case err != nil:
		return incoming{}, &failure{kind: KindIO, cause: err}
	}
	return incoming{status: resp.StatusCode, headers: resp.Header, body: decoded}, nil
}

// decode undoes gzip and deflate; the decoded size is held to the same limit, so a small
// compressed body cannot expand without bound.
func decode(encoding string, raw []byte, maxBytes int64) (out []byte, tooLarge bool, err error) {
	var r io.Reader
	switch strings.ToLower(strings.TrimSpace(encoding)) {
	case "gzip", "x-gzip":
		gz, gerr := gzip.NewReader(bytes.NewReader(raw))
		if gerr != nil {
			return nil, false, errors.New("the body is not valid gzip")
		}
		r = gz
	case "deflate":
		zr, zerr := zlib.NewReader(bytes.NewReader(raw))
		if zerr != nil {
			r = flate.NewReader(bytes.NewReader(raw)) // some servers send raw deflate
		} else {
			r = zr
		}
	default:
		return raw, false, nil
	}
	b, rerr := io.ReadAll(io.LimitReader(r, maxBytes+1))
	if rerr != nil {
		return nil, false, errors.New("the body could not be decompressed")
	}
	if int64(len(b)) > maxBytes {
		return nil, true, nil
	}
	return b, false, nil
}
