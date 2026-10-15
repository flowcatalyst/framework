package httpclient

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"maps"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"time"
)

const maxResponseLimit = int64(1) << 30

var serviceName = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

// Attempt is one finished attempt, as told to the [Observer].
type Attempt struct {
	// Service is the service's name.
	Service string
	// Method is the HTTP method.
	Method string
	// PathTemplate is the path with its placeholders unfilled.
	PathTemplate string
	// Status is the HTTP status, or 0 when Failure is set.
	Status int
	// Failure is why there was no response, or 0.
	Failure Kind
	// Took is how long the attempt took.
	Took time.Duration
	// Number is the attempt number, from 1.
	Number int
}

// Observer is told each finished attempt; the adapter records metrics with it.
type Observer interface{ Attempted(Attempt) }

// ServiceOption configures [Engine.Service].
type ServiceOption func(*serviceOptions)

type serviceOptions struct {
	connectTimeout time.Duration
	requestTimeout time.Duration
	maxBytes       int64
	retry          RetryPolicy
	auth           Auth
	headers        [][2]string
	observer       Observer
}

// ConnectTimeout sets the time to make a connection (default 5s).
func ConnectTimeout(d time.Duration) ServiceOption {
	return func(o *serviceOptions) { o.connectTimeout = d }
}

// RequestTimeout sets the time one attempt may take, the whole exchange including the body
// (default 30s).
func RequestTimeout(d time.Duration) ServiceOption {
	return func(o *serviceOptions) { o.requestTimeout = d }
}

// MaxResponseBytes sets the largest answer accepted, 1 B to 1 GiB (default 10 MiB); bytes
// received count, and a decoded body is held to the same limit.
func MaxResponseBytes(n int64) ServiceOption { return func(o *serviceOptions) { o.maxBytes = n } }

// Retry sets the retry policy (default [DefaultRetry]).
func Retry(p RetryPolicy) ServiceOption { return func(o *serviceOptions) { o.retry = p } }

// WithAuth sets the authentication strategy (default none).
func WithAuth(a Auth) ServiceOption { return func(o *serviceOptions) { o.auth = a } }

// DefaultHeader adds a header sent on every request of the service.
func DefaultHeader(name, value string) ServiceOption {
	return func(o *serviceOptions) { o.headers = append(o.headers, [2]string{name, value}) }
}

// WithObserver sets the observer of finished attempts.
func WithObserver(ob Observer) ServiceOption { return func(o *serviceOptions) { o.observer = ob } }

type noObserver struct{}

func (noObserver) Attempted(Attempt) {}

// Service is one consumed service: requests relative to its base URL, with its timeouts,
// authentication and retry policy. It is safe for concurrent use; share it.
type Service struct {
	name     string
	base     *url.URL
	basePath string
	opts     serviceOptions
	engine   *Engine
	tokens   *tokenCache
	extra    http.Header
}

// Service makes the client of one consumed service. name is [a-z][a-z0-9-]* (at most 100
// characters); baseURL is absolute http or https with a host and no user info, query or fragment,
// and its path is a prefix of every request.
func (e *Engine) Service(name string, baseURL *url.URL, opts ...ServiceOption) (*Service, error) {
	o := serviceOptions{
		connectTimeout: 5 * time.Second, requestTimeout: 30 * time.Second, maxBytes: 10 << 20,
		retry: DefaultRetry(), auth: NoAuth(), observer: noObserver{},
	}
	for _, f := range opts {
		f(&o)
	}
	if len(name) > 100 || !serviceName.MatchString(name) {
		return nil, errors.New("service name must match [a-z][a-z0-9-]*")
	}
	if baseURL == nil || baseURL.Hostname() == "" || (baseURL.Scheme != "http" && baseURL.Scheme != "https") ||
		baseURL.User != nil || baseURL.RawQuery != "" || baseURL.ForceQuery || baseURL.Fragment != "" {
		return nil, fmt.Errorf("service %s: base URL must be absolute http(s) with a host and no user info, query or fragment", name)
	}
	switch {
	case o.connectTimeout <= 0 || o.requestTimeout <= 0:
		return nil, fmt.Errorf("service %s: timeouts must be positive", name)
	case o.maxBytes < 1 || o.maxBytes > maxResponseLimit:
		return nil, fmt.Errorf("service %s: maxResponseBytes must be 1 to 1 GiB", name)
	}
	if err := o.retry.Validate(); err != nil {
		return nil, fmt.Errorf("service %s: %w", name, err)
	}
	s := &Service{name: name, base: baseURL, opts: o, engine: e, extra: http.Header{}}
	for _, h := range o.headers {
		if err := checkHeader(h[0], h[1]); err != nil {
			return nil, fmt.Errorf("service %s: %w", name, err)
		}
	}
	p := baseURL.EscapedPath()
	if p == "/" {
		p = ""
	}
	s.basePath = strings.TrimSuffix(p, "/")
	if oa, ok := o.auth.(oauthAuth); ok {
		s.tokens = newTokenCache(oa.cc, s)
	}
	return s, nil
}

// Name returns the service's name.
func (s *Service) Name() string { return s.name }

// BaseURL returns the service's base URL.
func (s *Service) BaseURL() *url.URL { u := *s.base; return &u }

// WithHeaders returns the same service with extra headers on every request (for example a
// correlation id). It shares connections and the cached token.
func (s *Service) WithHeaders(headers map[string]string) (*Service, error) {
	c := *s
	c.extra = s.extra.Clone()
	for name, value := range headers {
		if err := checkHeader(name, value); err != nil {
			return nil, err
		}
		c.extra.Set(name, value)
	}
	return &c, nil
}

// Request starts a request with a method and a path starting with "/", which may hold {name}
// placeholders. A path that could leave the service is an error from Send.
func (s *Service) Request(method, path string) *Request {
	r := &Request{svc: s, method: method, params: map[string]string{}, headers: http.Header{}}
	r.path, r.err = parsePath(path)
	return r
}

// Get starts a GET.
func (s *Service) Get(path string) *Request { return s.Request(http.MethodGet, path) }

// Head starts a HEAD.
func (s *Service) Head(path string) *Request { return s.Request(http.MethodHead, path) }

// Post starts a POST (not retried unless it has an idempotency key or is marked retryable).
func (s *Service) Post(path string) *Request { return s.Request(http.MethodPost, path) }

// Put starts a PUT.
func (s *Service) Put(path string) *Request { return s.Request(http.MethodPut, path) }

// Patch starts a PATCH (not retried unless it has an idempotency key or is marked retryable).
func (s *Service) Patch(path string) *Request { return s.Request(http.MethodPatch, path) }

// Delete starts a DELETE.
func (s *Service) Delete(path string) *Request { return s.Request(http.MethodDelete, path) }

func idempotent(method string) bool {
	switch method {
	case http.MethodGet, http.MethodHead, http.MethodPut, http.MethodDelete, http.MethodOptions:
		return true
	}
	return false
}

func (s *Service) target(r *Request) (string, error) {
	filled, err := r.path.fill(r.params)
	if err != nil {
		return "", err
	}
	var q strings.Builder
	for _, kv := range r.query {
		if q.Len() > 0 {
			q.WriteByte('&')
		}
		q.WriteString(percentEncode(kv[0]) + "=" + percentEncode(kv[1]))
	}
	t := s.base.Scheme + "://" + s.base.Host + s.basePath + filled
	if q.Len() > 0 {
		t += "?" + q.String()
	}
	return t, nil
}

func (s *Service) observe(r *Request, status int, kind Kind, took time.Duration, attempt int) {
	s.engine.log.Debug(fmt.Sprintf("%s %s %s -> %s in %dms (attempt %d)", s.name, r.method, r.path.text, outcomeText(status, kind), took.Milliseconds(), attempt))
	s.opts.observer.Attempted(Attempt{Service: s.name, Method: r.method, PathTemplate: r.path.text, Status: status, Failure: kind, Took: took, Number: attempt})
}

func outcomeText(status int, kind Kind) string {
	if kind != 0 {
		return strings.ToUpper(kind.String())
	}
	return fmt.Sprint(status)
}

// execute runs a request with retries and authentication (http-client contract sections 3 and 4).
func (s *Service) execute(ctx context.Context, r *Request) (*Response, error) {
	if r.err != nil {
		return nil, r.err
	}
	description := s.name + " " + r.method + " " + r.path.text
	target, err := s.target(r)
	if err != nil {
		return nil, err
	}
	eligible := idempotent(r.method) || r.headers.Get("Idempotency-Key") != ""
	if r.retryable != nil {
		eligible = *r.retryable
	}
	retry := s.opts.retry
	timeout := s.opts.requestTimeout
	if r.timeout > 0 {
		timeout = r.timeout
	}
	authRepeated := false
	for attempt := 1; ; attempt++ {
		headers := http.Header{}
		headers.Set("User-Agent", userAgent)
		for _, h := range s.opts.headers {
			headers.Set(h[0], h[1])
		}
		maps.Copy(headers, s.extra)
		maps.Copy(headers, r.headers)
		usedToken, aerr := s.applyAuth(ctx, headers, description)
		if aerr != nil {
			return nil, aerr
		}
		start := s.engine.clock.Now()
		in, fail := s.engine.exchange(ctx, outgoing{
			method: r.method, target: target, headers: headers, body: r.body,
			connectTimeout: s.opts.connectTimeout, timeout: timeout, maxBytes: s.opts.maxBytes,
		})
		took := s.engine.clock.Now().Sub(start)
		if fail != nil {
			s.observe(r, 0, fail.kind, took, attempt)
			if eligible && attempt < retry.MaxAttempts && fail.kind != KindResponseTooLarge && fail.kind != KindCanceled && fail.kind != KindClosed {
				if err := s.pause(ctx, retry.backoff(attempt, s.engine.jitter), description); err != nil {
					return nil, err
				}
				continue
			}
			return nil, &Error{Kind: fail.kind, msg: description + ": " + describeKind(fail.kind), cause: fail.cause}
		}
		s.observe(r, in.status, 0, took, attempt)
		resp := &Response{status: in.status, headers: in.headers, body: in.body, description: description}
		if in.status == http.StatusUnauthorized && !authRepeated && s.refreshAfterUnauthorized(usedToken) {
			authRepeated = true
			attempt-- // the repeat after a refused credential is not a retry
			continue
		}
		if eligible && attempt < retry.MaxAttempts && retry.retries(in.status) {
			wait, ok := retryAfter(resp.Header("Retry-After"), s.engine.clock.Now())
			if ok && wait > retry.MaxRetryAfter {
				return resp, nil // the service asked for a longer wait than we are willing to block
			}
			if !ok {
				wait = retry.backoff(attempt, s.engine.jitter)
			}
			if err := s.pause(ctx, wait, description); err != nil {
				return nil, err
			}
			continue
		}
		return resp, nil
	}
}

func (s *Service) pause(ctx context.Context, d time.Duration, description string) error {
	if err := s.engine.sleep(ctx, d); err != nil {
		return &Error{Kind: KindCanceled, msg: description + ": canceled", cause: err}
	}
	return nil
}

// applyAuth adds the authentication header; it returns the bearer token used, for a later
// invalidation.
func (s *Service) applyAuth(ctx context.Context, h http.Header, description string) (string, error) {
	authError := func(err error) error {
		if ctx.Err() != nil {
			return &Error{Kind: KindCanceled, msg: description + ": canceled", cause: ctx.Err()}
		}
		var he *Error
		if errors.As(err, &he) && he.Kind == KindAuth {
			return &Error{Kind: KindAuth, msg: description + ": " + he.msg}
		}
		return &Error{Kind: KindAuth, msg: fmt.Sprintf("%s: the auth strategy failed (%T)", description, err)}
	}
	switch a := s.opts.auth.(type) {
	case noAuth:
		return "", nil
	case bearerAuth:
		token, err := a.token(ctx)
		if err != nil {
			return "", authError(err)
		}
		if err := checkHeaderValue("Authorization", token); err != nil {
			return "", authError(err)
		}
		h.Set("Authorization", "Bearer "+token)
		return token, nil
	case basicAuth:
		pw, err := a.password(ctx)
		if err != nil {
			return "", authError(err)
		}
		h.Set("Authorization", "Basic "+basicValue(a.user, pw))
		return "", nil
	case headerAuth:
		v, err := a.value(ctx)
		if err != nil {
			return "", authError(err)
		}
		if err := checkHeaderValue(a.name, v); err != nil {
			return "", authError(err)
		}
		h.Set(a.name, v)
		return "", nil
	case oauthAuth:
		token, err := s.tokens.get(ctx)
		if err != nil {
			return "", authError(err)
		}
		h.Set("Authorization", "Bearer "+token)
		return token, nil
	}
	return "", authError(errors.New("unknown auth strategy"))
}

func (s *Service) refreshAfterUnauthorized(used string) bool {
	switch a := s.opts.auth.(type) {
	case bearerAuth:
		if a.onUnauthorized == nil {
			return false
		}
		a.onUnauthorized()
		return true
	case oauthAuth:
		if s.tokens != nil && used != "" {
			s.tokens.invalidate(used)
		}
		return true
	case noAuth, basicAuth, headerAuth:
		return false
	}
	return false
}

func basicValue(user, password string) string {
	return base64.StdEncoding.EncodeToString([]byte(user + ":" + password))
}
