// Package module is the framework adapter of the HTTP client (http-client contract section 5 as
// amended by the config-and-code contract section 3.9): clients are named in code with their
// policy (timeouts, size limit, retry, authentication strategy), each client's base URL and
// credentials are configuration, and the adapter closes the engine at shutdown, records the
// client metrics and gives a client that sends the context's correlation id.
//
//	d := module.Declare(app)                                  // in main
//	clients, err := module.Build(ctx, b, d,                   // in build
//		module.WithClient("billing", module.DefaultClientOptions().
//			WithRequestTimeout(10*time.Second).
//			WithAuth(module.ClientCredentials().WithScope("billing"))))
//	billing, err := clients.For("billing", cx)
//
// with, per client named in code, in configuration:
//
//	http-clients:
//	  instances:
//	    billing:
//	      base-url: https://billing.example.com
//	      auth: { token-url: https://login.example.com/oauth/token, client-id: orders-service }  # client-secret from the environment
//
// The engine setting http-client.max-connections-per-host is configuration (default 50, 1 to
// 1000). The Java engine's http-client.event-loops has no counterpart in Go and is not declared, so
// giving it fails start-up as an unknown key naming the real one.
package module

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpclient"
	"github.com/flowcatalyst/framework/go/metrics"
)

// ModuleID is the adapter's module id; it claims the component of the same name.
const ModuleID = "http-client"

// CorrelationHeader is the header [Clients.For] sets from the context.
const CorrelationHeader = "X-Correlation-Id"

const section = "http-clients"

// The keys of a client's section (http-clients.instances.<name>.<key>).
var (
	keyBaseURL      = config.URL("base-url", "Base URL of the service: absolute http(s), a host, no user info, query or fragment; its path is a prefix.").Required()
	keyToken        = config.SecretValue("auth.token", "Bearer token (ClientAuth Bearer).")
	keyUsername     = config.String("auth.username", "User name (ClientAuth Basic).")
	keyPassword     = config.SecretValue("auth.password", "Password (ClientAuth Basic).")
	keyValue        = config.SecretValue("auth.value", "API key (ClientAuth Header).")
	keyTokenURL     = config.URL("auth.token-url", "Token endpoint, https or http to localhost (ClientAuth ClientCredentials).")
	keyClientID     = config.String("auth.client-id", "OAuth client id (ClientAuth ClientCredentials).")
	keyClientSecret = config.SecretValue("auth.client-secret", "OAuth client secret (ClientAuth ClientCredentials).")

	maxConnsSetting = config.Int("http-client.max-connections-per-host",
		"Pooled connections per host of the HTTP client engine, 1 to 1000.").WithDefault(50).Checked(config.Between(1, 1000))
)

// Declared is the adapter's declaration, from [Declare].
type Declared struct {
	instances fw.InstancesHandle
	maxConns  config.Handle[int]
}

// Declare declares the module: it claims the component http-client (a misspelt key under it fails
// start-up), declares http-client.max-connections-per-host and the client instance section
// http-clients, whose keys are base-url and the credentials of each strategy.
func Declare(app *fw.App) *Declared {
	d := app.Module(ModuleID)
	d.ClaimComponent("http-client")
	return &Declared{
		maxConns: config.Declare(d, maxConnsSetting),
		instances: d.InstancesWithoutDefault(section, "HTTP client",
			keyBaseURL, keyToken, keyUsername, keyPassword, keyValue, keyTokenURL, keyClientID, keyClientSecret),
	}
}

// ClientOptions is the policy of one client named in code. Build it from [DefaultClientOptions]
// with the With methods; [Build] checks every value.
type ClientOptions struct {
	// ConnectTimeout is the time to make a connection (default 5s).
	ConnectTimeout time.Duration
	// RequestTimeout is the time one attempt may take (default 30s).
	RequestTimeout time.Duration
	// MaxResponseBytes is the largest answer accepted (default 10 MiB).
	MaxResponseBytes int64
	// Retry is the retry policy (default httpclient.DefaultRetry).
	Retry httpclient.RetryPolicy
	// Auth is the authentication strategy; its credentials come from configuration.
	Auth ClientAuth
}

const (
	maxTimeout = time.Hour
	maxLimit   = int64(1) << 30
)

// DefaultClientOptions is 5s connect, 30s request, 10 MiB, the default retry and no
// authentication.
func DefaultClientOptions() ClientOptions {
	return ClientOptions{
		ConnectTimeout: 5 * time.Second, RequestTimeout: 30 * time.Second, MaxResponseBytes: 10 << 20,
		Retry: httpclient.DefaultRetry(), Auth: None(),
	}
}

// WithConnectTimeout returns a copy with another connect timeout.
func (o ClientOptions) WithConnectTimeout(d time.Duration) ClientOptions {
	o.ConnectTimeout = d
	return o
}

// WithRequestTimeout returns a copy with another request timeout.
func (o ClientOptions) WithRequestTimeout(d time.Duration) ClientOptions {
	o.RequestTimeout = d
	return o
}

// WithMaxResponseBytes returns a copy with another size limit.
func (o ClientOptions) WithMaxResponseBytes(n int64) ClientOptions { o.MaxResponseBytes = n; return o }

// WithRetry returns a copy with another retry policy.
func (o ClientOptions) WithRetry(p httpclient.RetryPolicy) ClientOptions { o.Retry = p; return o }

// WithAuth returns a copy with another authentication strategy.
func (o ClientOptions) WithAuth(a ClientAuth) ClientOptions { o.Auth = a; return o }

// Validate checks every value: timeouts more than 0 and at most 1h, the size limit 1 B to 1 GiB, the
// retry ranges.
func (o ClientOptions) Validate() error {
	for _, t := range []struct {
		field string
		d     time.Duration
	}{{"connectTimeout", o.ConnectTimeout}, {"requestTimeout", o.RequestTimeout}} {
		if t.d <= 0 || t.d > maxTimeout {
			return fmt.Errorf("%s must be more than 0 and at most 1h, was %v", t.field, t.d)
		}
	}
	if o.MaxResponseBytes < 1 || o.MaxResponseBytes > maxLimit {
		return fmt.Errorf("maxResponseBytes must be 1 B to 1 GiB, was %d", o.MaxResponseBytes)
	}
	if err := o.Retry.Validate(); err != nil {
		return err
	}
	if o.Auth == nil {
		return errors.New("auth is not set (use module.None())")
	}
	return nil
}

// ClientAuth is the authentication strategy of a client named in code. It says which strategy and
// its non-secret parameters; the credentials it reads are in the client's configuration section.
//
//sumtype:decl
type ClientAuth interface{ clientAuth() }

// NoAuth sends nothing.
type NoAuth struct{}

// BearerAuth sends auth.token as a bearer token.
type BearerAuth struct{}

// BasicAuth sends auth.username and auth.password.
type BasicAuth struct{}

// HeaderAuth sends auth.value in the header Name.
type HeaderAuth struct{ Name string }

// ClientCredentialsGrant is an OAuth 2 client-credentials grant against auth.token-url with
// auth.client-id and auth.client-secret.
type ClientCredentialsGrant struct {
	Scope    string
	Audience string
	Method   httpclient.GrantMethod
}

func (NoAuth) clientAuth()                 {}
func (BearerAuth) clientAuth()             {}
func (BasicAuth) clientAuth()              {}
func (HeaderAuth) clientAuth()             {}
func (ClientCredentialsGrant) clientAuth() {}

// None is no authentication.
func None() ClientAuth { return NoAuth{} }

// Bearer authenticates with the token in auth.token.
func Bearer() ClientAuth { return BearerAuth{} }

// Basic authenticates with auth.username and auth.password.
func Basic() ClientAuth { return BasicAuth{} }

// Header authenticates with an API key in the named header, the key in auth.value.
func Header(name string) ClientAuth { return HeaderAuth{Name: name} }

// ClientCredentials is an OAuth 2 client-credentials grant; refine it with WithScope,
// WithAudience and WithMethod.
func ClientCredentials() ClientCredentialsGrant { return ClientCredentialsGrant{} }

// WithScope returns a copy with a scope.
func (g ClientCredentialsGrant) WithScope(s string) ClientCredentialsGrant { g.Scope = s; return g }

// WithAudience returns a copy with an audience.
func (g ClientCredentialsGrant) WithAudience(a string) ClientCredentialsGrant {
	g.Audience = a
	return g
}

// WithMethod returns a copy with another way to authenticate to the token endpoint.
func (g ClientCredentialsGrant) WithMethod(m httpclient.GrantMethod) ClientCredentialsGrant {
	g.Method = m
	return g
}

var clientName = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)

// BuildOption names a client for [Build].
type BuildOption func(*buildConfig)

type optionsFunc func(b *fw.Boot) (ClientOptions, error)

type namedClient struct {
	name string
	opts optionsFunc
}

type buildConfig struct{ clients []namedClient }

// WithClient names a client with its policy. A name is [a-z][a-z0-9-]*, at most 100 characters,
// and given once.
func WithClient(name string, o ClientOptions) BuildOption {
	return func(c *buildConfig) {
		c.clients = append(c.clients, namedClient{name: name, opts: func(*fw.Boot) (ClientOptions, error) { return o, nil }})
	}
}

// WithClientFrom names a client whose policy is resolved once configuration is loaded, from the
// application's own settings (config-and-code contract section 4).
func WithClientFrom(name string, f func(b *fw.Boot) (ClientOptions, error)) BuildOption {
	return func(c *buildConfig) { c.clients = append(c.clients, namedClient{name: name, opts: f}) }
}

// Clients are the clients of an application: those named in code and any registered later.
type Clients struct {
	engine  *httpclient.Engine
	metrics *clientMetrics

	mu      sync.Mutex
	clients map[string]*httpclient.Service
	order   []string
}

// Build makes the engine (closed at shutdown) and every client named in code from its policy and
// its configuration section. Every problem is reported at once as a *fw.StartupError: a client
// with no base-url, a missing or a surplus credential key for its strategy, a section for a client
// the code does not name, an invalid policy.
func Build(_ context.Context, b *fw.Boot, d *Declared, opts ...BuildOption) (*Clients, error) {
	var cfg buildConfig
	for _, o := range opts {
		o(&cfg)
	}
	m, err := newClientMetrics(b.Metrics())
	if err != nil {
		return nil, err
	}
	engine, err := httpclient.NewEngine(httpclient.MaxConnectionsPerHost(d.maxConns.Get(b)),
		httpclient.WithClock(b.Clock()), httpclient.WithLogger(b.Logger(ModuleID)))
	if err != nil {
		return nil, &fw.StartupError{Problems: []fw.Problem{{Module: ModuleID, Key: maxConnsSetting.Key(), Message: err.Error()}}}
	}
	b.OnShutdown("http-client", func(context.Context) error { engine.Close(); return nil })
	c := &Clients{engine: engine, metrics: m, clients: map[string]*httpclient.Service{}}

	insts := b.Instances(d.instances)
	var problems []fw.Problem
	problem := func(key, msg string) {
		problems = append(problems, fw.Problem{Module: ModuleID, Key: key, Message: msg})
	}
	named := map[string]bool{}
	for _, nc := range cfg.clients {
		switch {
		case len(nc.name) > 100 || !clientName.MatchString(nc.name):
			problem(section+".instances."+nc.name, "client name must match [a-z][a-z0-9-]*, was '"+nc.name+"'")
		case named[nc.name]:
			problem(section+".instances."+nc.name, "client '"+nc.name+"' is already given")
		}
		named[nc.name] = true
	}
	for _, name := range insts.Names {
		if !named[name] {
			problem(section+".instances."+name, "no client named '"+name+"' in code: name it with module.WithClient(\""+name+
				"\", ...) or delete the section; named: "+listOrNone(cfg.clients))
		}
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	for _, nc := range cfg.clients {
		path := section + ".instances." + nc.name
		o, err := nc.opts(b)
		if err != nil {
			problem(path, "client '"+nc.name+"': "+err.Error())
			continue
		}
		if err := o.Validate(); err != nil {
			problem(path, "client '"+nc.name+"': "+err.Error())
			continue
		}
		in, ok := insts.ByName[nc.name]
		if !ok {
			problem(path+".base-url", "missing required key: client '"+nc.name+"' is named in code, so its section needs a base-url; set it in a config file or as environment variable "+config.EnvName(path+".base-url"))
			continue
		}
		svcOpts, ps := serviceOptions(nc.name, path, o, in)
		for _, p := range ps {
			problem(p.Key, p.Message)
		}
		if len(ps) > 0 {
			continue
		}
		base := keyBaseURL.Get(in)
		svc, err := engine.Service(nc.name, &base, append(svcOpts, httpclient.WithObserver(m))...)
		if err != nil {
			problem(path, "client '"+nc.name+"': "+err.Error())
			continue
		}
		c.add(nc.name, svc)
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}
	return c, nil
}

func listOrNone(cs []namedClient) string {
	if len(cs) == 0 {
		return "(none)"
	}
	names := make([]string, len(cs))
	for i, c := range cs {
		names[i] = c.name
	}
	return strings.Join(names, ", ")
}

// strategyKeys are the relative keys each strategy reads.
func strategyKeys(a ClientAuth) []string {
	switch a.(type) {
	case BearerAuth:
		return []string{keyToken.Key()}
	case BasicAuth:
		return []string{keyUsername.Key(), keyPassword.Key()}
	case HeaderAuth:
		return []string{keyValue.Key()}
	case ClientCredentialsGrant:
		return []string{keyTokenURL.Key(), keyClientID.Key(), keyClientSecret.Key()}
	case NoAuth:
		return nil
	}
	return nil
}

var allCredentialKeys = []string{
	keyToken.Key(), keyUsername.Key(), keyPassword.Key(), keyValue.Key(), keyTokenURL.Key(), keyClientID.Key(), keyClientSecret.Key(),
}

func isSet(in config.Instance, key string) bool {
	switch key {
	case keyToken.Key():
		_, ok := keyToken.Lookup(in)
		return ok
	case keyUsername.Key():
		_, ok := keyUsername.Lookup(in)
		return ok
	case keyPassword.Key():
		_, ok := keyPassword.Lookup(in)
		return ok
	case keyValue.Key():
		_, ok := keyValue.Lookup(in)
		return ok
	case keyTokenURL.Key():
		_, ok := keyTokenURL.Lookup(in)
		return ok
	case keyClientID.Key():
		_, ok := keyClientID.Lookup(in)
		return ok
	case keyClientSecret.Key():
		_, ok := keyClientSecret.Lookup(in)
		return ok
	}
	return false
}

// serviceOptions turns a client's policy and section into the library's options, reporting a
// missing credential key and every key its strategy does not read.
func serviceOptions(name, path string, o ClientOptions, in config.Instance) ([]httpclient.ServiceOption, []fw.Problem) {
	var ps []fw.Problem
	read := strategyKeys(o.Auth)
	for _, k := range allCredentialKeys {
		switch {
		case isSet(in, k) && !slices.Contains(read, k):
			ps = append(ps, fw.Problem{Key: path + "." + k, Message: "unknown key for client '" + name + "' (its ClientAuth reads only its own credentials)"})
		case !isSet(in, k) && slices.Contains(read, k):
			ps = append(ps, fw.Problem{Key: path + "." + k, Message: "missing required key: the authentication of client '" + name +
				"' reads it; set it in a config file or as environment variable " + config.EnvName(path+"."+k)})
		}
	}
	if len(ps) > 0 {
		return nil, ps
	}
	opts := []httpclient.ServiceOption{
		httpclient.ConnectTimeout(o.ConnectTimeout), httpclient.RequestTimeout(o.RequestTimeout),
		httpclient.MaxResponseBytes(o.MaxResponseBytes), httpclient.Retry(o.Retry),
	}
	var auth httpclient.Auth
	switch a := o.Auth.(type) {
	case NoAuth:
		auth = httpclient.NoAuth()
	case BearerAuth:
		token, _ := keyToken.Lookup(in)
		auth = httpclient.Bearer(secretCredential(token))
	case BasicAuth:
		user, _ := keyUsername.Lookup(in)
		pw, _ := keyPassword.Lookup(in)
		basic, err := httpclient.Basic(user, secretCredential(pw))
		if err != nil {
			return nil, []fw.Problem{{Key: path + "." + keyUsername.Key(), Message: "client '" + name + "': " + err.Error()}}
		}
		auth = basic
	case HeaderAuth:
		v, _ := keyValue.Lookup(in)
		h, err := httpclient.APIKey(a.Name, secretCredential(v))
		if err != nil {
			return nil, []fw.Problem{{Key: path, Message: "client '" + name + "': " + err.Error()}}
		}
		auth = h
	case ClientCredentialsGrant:
		tokenURL, _ := keyTokenURL.Lookup(in)
		id, _ := keyClientID.Lookup(in)
		secret, _ := keyClientSecret.Lookup(in)
		var cco []httpclient.ClientCredentialsOption
		if a.Scope != "" {
			cco = append(cco, httpclient.Scope(a.Scope))
		}
		if a.Audience != "" {
			cco = append(cco, httpclient.Audience(a.Audience))
		}
		cco = append(cco, httpclient.Method(a.Method))
		cc, err := httpclient.NewClientCredentials(&tokenURL, id, secretCredential(secret), cco...)
		if err != nil {
			return nil, []fw.Problem{{Key: path + "." + keyTokenURL.Key(), Message: "client '" + name + "': " + err.Error()}}
		}
		auth = httpclient.OAuthClientCredentials(cc)
	default:
		return nil, []fw.Problem{{Key: path, Message: "client '" + name + "': unknown ClientAuth"}}
	}
	return append(opts, httpclient.WithAuth(auth)), nil
}

func secretCredential(s config.Secret) httpclient.Credential {
	return func(context.Context) (string, error) { return s.Reveal(), nil }
}

func (c *Clients) add(name string, s *httpclient.Service) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.clients[name] = s
	c.order = append(c.order, name)
}

// Get returns the named client, or an error naming the known ones.
func (c *Clients) Get(name string) (*httpclient.Service, error) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if s, ok := c.clients[name]; ok {
		return s, nil
	}
	return nil, fmt.Errorf("no service client named '%s'; known: %s", name, strings.Join(c.order, ", "))
}

// For returns the named client with X-Correlation-Id set to the context's correlation id on every
// request (only that: the client id is not sent to other services).
func (c *Clients) For(name string, cx fw.Context) (*httpclient.Service, error) {
	s, err := c.Get(name)
	if err != nil {
		return nil, err
	}
	return s.WithHeaders(map[string]string{CorrelationHeader: cx.Correlation().String()})
}

// Register adds a client built in code (for example with Auth set to a bearer over the platform
// client's token). The adapter's metrics observer is set after the options given.
func (c *Clients) Register(name string, baseURL *url.URL, opts ...httpclient.ServiceOption) (*httpclient.Service, error) {
	c.mu.Lock()
	_, exists := c.clients[name]
	c.mu.Unlock()
	if exists {
		return nil, fmt.Errorf("service client '%s' already exists", name)
	}
	s, err := c.engine.Service(name, baseURL, append(slices.Clone(opts), httpclient.WithObserver(c.metrics))...)
	if err != nil {
		return nil, err
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if _, exists := c.clients[name]; exists {
		return nil, fmt.Errorf("service client '%s' already exists", name)
	}
	c.clients[name] = s
	c.order = append(c.order, name)
	return s, nil
}

// Names lists the clients, in the order they were made.
func (c *Clients) Names() []string {
	c.mu.Lock()
	defer c.mu.Unlock()
	return slices.Clone(c.order)
}

// clientMetrics records fw_http_client_requests_total and fw_http_client_request_duration_seconds,
// one observation per attempt.
type clientMetrics struct {
	requests  *metrics.Counter
	durations *metrics.Timer
}

func newClientMetrics(r *metrics.Registry) (*clientMetrics, error) {
	req, err := r.Counter("fw_http_client_requests_total",
		"Outgoing HTTP attempts by service, method and outcome (status, or failure kind).", "service", "method", "outcome")
	if err != nil {
		return nil, err
	}
	dur, err := r.Timer("fw_http_client_request_duration_seconds",
		"Outgoing HTTP attempt duration by service and method.", "service", "method")
	if err != nil {
		return nil, err
	}
	return &clientMetrics{requests: req, durations: dur}, nil
}

// Attempted implements httpclient.Observer.
func (m *clientMetrics) Attempted(a httpclient.Attempt) {
	outcome := strconv.Itoa(a.Status)
	if a.Failure != 0 {
		outcome = a.Failure.String()
	}
	m.requests.With(a.Service, a.Method, outcome).Inc()
	m.durations.With(a.Service, a.Method).Record(a.Took)
}
