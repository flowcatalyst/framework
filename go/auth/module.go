package auth

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

// ModuleID is the auth module's id; it claims the configuration component "auth".
const ModuleID = "auth"

// Declared is the auth module's declaration, from [Declare]: the connection and secret settings.
// Everything else is code, set with the options of [Declared.Build].
type Declared struct {
	issuer        config.Handle[string]
	clientID      config.Handle[string]
	clientSecret  config.Handle[config.Secret]
	publicBaseURL config.Handle[string]
	cookieKeys    config.Handle[config.Secret]
	bearer        bool
}

// DeclareOption changes what [Declare] declares.
type DeclareOption func(*Declared)

// NoBearerTokens declares sessions only: the document gets no bearer security scheme and the
// resolver ignores an Authorization header (contract section 3, "bearer tokens accepted").
func NoBearerTokens() DeclareOption { return func(d *Declared) { d.bearer = false } }

// Declare declares the module: the settings auth.issuer (the platform's base URL, which must equal
// the discovery document's issuer and every token's iss), auth.client-id, auth.client-secret (set
// for a CONFIDENTIAL login client; unset means PUBLIC), auth.public-base-url (this application's
// public origin; the redirect URI is {public-base-url}/auth/callback and the trusted origin of the
// CSRF check) and auth.cookie-keys (only for the cookie store), the claim on the "auth" component (a
// misspelt key under it fails start-up), and, in the OpenAPI document, the two typed operations
// getAuthSession and logout with the session (and, unless [NoBearerTokens], bearer) security
// schemes.
func Declare(app *fw.App, web *httpfw.Declared, opts ...DeclareOption) *Declared {
	m := app.Module(ModuleID)
	m.ClaimComponent("auth")
	d := &Declared{
		issuer:   config.Declare(m, config.String("auth.issuer", "The FlowCatalyst platform's base URL; the issuer of its tokens.").Required()),
		clientID: config.Declare(m, config.String("auth.client-id", "The OAuth client id of this application's login client.").Required()),
		clientSecret: config.Declare(m, config.SecretValue("auth.client-secret",
			"The login client's secret for a CONFIDENTIAL client; leave unset for a PUBLIC client.")),
		publicBaseURL: config.Declare(m, config.String("auth.public-base-url",
			"This application's public base URL, for example https://ingest.example.com.").Required()),
		cookieKeys: config.Declare(m, config.SecretValue("auth.cookie-keys",
			"Keys that seal session cookies: <kid>:<base64url of 32 bytes>, comma-separated, the sealing key first.")),
		bearer: true,
	}
	for _, o := range opts {
		o(d)
	}
	sessionapi.Declare(web, d.bearer)
	return d
}

// Option sets the module's behaviour, in code ([Declared.Build]).
type Option func(*options)

type options struct {
	store          StoreFactory
	cookieSessions bool
	idle, absolute time.Duration
	permissions    PermissionMapper
	clients        ClientSelector
	application    fw.ApplicationCode
	defaultLanding string
	returnTo       ReturnToPolicy
	postLogoutPath string
	failures       LoginFailureHandler
	scope          string
	loginTheme     string
	rateLimit      httpfw.Middleware
	rateLimited    bool
	httpTimeout    time.Duration
	problems       []string
}

// SessionStore names the session store: auth.MemoryStoreFactory(), authdb.Factory(dbs) (the documented
// default) or authredis.Factory(clients). Without it (and without [CookieSessions]) start-up fails.
func SessionStore(f StoreFactory) Option {
	return func(o *options) { o.store, o.cookieSessions = f, false }
}

// CookieSessions seals sessions in the cookie, with the keys of auth.cookie-keys. Nothing is kept
// on the server, so logout cannot revoke a copied cookie (contract section 6.5).
func CookieSessions() Option {
	return func(o *options) { o.cookieSessions, o.store = true, nil }
}

// IdleTimeout is how long a session lasts without activity. Default 30 minutes.
func IdleTimeout(d time.Duration) Option { return func(o *options) { o.idle = d } }

// AbsoluteTimeout is how long a session lasts at most. Default 8 hours.
func AbsoluteTimeout(d time.Duration) Option { return func(o *options) { o.absolute = d } }

// Permissions sets how a principal's permissions are found. Default [ScopesMapper]: a bearer
// token's scope, nothing for a browser session.
func Permissions(m PermissionMapper) Option { return func(o *options) { o.permissions = m } }

// Clients sets how the client of a request is chosen. Default [HomeClient].
func Clients(s ClientSelector) Option { return func(o *options) { o.clients = s } }

// Application sets the application code every Context carries.
func Application(code fw.ApplicationCode) Option { return func(o *options) { o.application = code } }

// DefaultLanding is where a browser lands after signing in when no usable returnTo was given.
// Default "/".
func DefaultLanding(path string) Option {
	return func(o *options) {
		if !isSameOriginPath(path) {
			o.problems = append(o.problems, "the default landing must be a same-origin path: "+path)
			return
		}
		o.defaultLanding = path
	}
}

// ReturnTo decides where to land after signing in. Default [SameOriginReturnTo].
func ReturnTo(p ReturnToPolicy) Option { return func(o *options) { o.returnTo = p } }

// PostLogoutRedirect is where the platform sends the browser after single logout; the logout
// answer's endSessionUrl names it. It must be registered as a post-logout redirect URI of the
// platform's login client. A same-origin path, made absolute with auth.public-base-url.
func PostLogoutRedirect(path string) Option {
	return func(o *options) {
		if !isSameOriginPath(path) {
			o.problems = append(o.problems, "the post-logout redirect must be a same-origin path: "+path)
			return
		}
		o.postLogoutPath = path
	}
}

// FailureHandler sets how a failed sign-in is answered. Default [JSONFailures].
func FailureHandler(h LoginFailureHandler) Option { return func(o *options) { o.failures = h } }

// Scope sets the scopes requested. Default "openid profile email"; it must contain openid, and
// offline_access is refused: the session keeps no refresh token (contract section 6.4).
func Scope(scope string) Option {
	return func(o *options) {
		fields := strings.Fields(scope)
		switch {
		case !slices.Contains(fields, "openid"):
			o.problems = append(o.problems, "the scope must contain openid")
		case slices.Contains(fields, "offline_access"):
			o.problems = append(o.problems, "offline_access is not supported: sessions keep no refresh token")
		default:
			o.scope = strings.Join(fields, " ")
		}
	}
}

var themePattern = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]*[a-z0-9])?$`)

// LoginTheme is the platform's login theme hint (client= on the authorize URL): a client
// identifier, [a-z0-9]([a-z0-9-]*[a-z0-9])?, at most 64 characters.
func LoginTheme(identifier string) Option {
	return func(o *options) {
		if len(identifier) > 64 || !themePattern.MatchString(identifier) {
			o.problems = append(o.problems, "a login theme is a client identifier: "+identifier)
			return
		}
		o.loginTheme = identifier
	}
}

// RateLimit replaces the rate limit on /auth/login and /auth/callback. Default: a memory limiter
// named auth, 20 a minute per client address, burst 20.
func RateLimit(mw httpfw.Middleware) Option {
	return func(o *options) { o.rateLimit, o.rateLimited = mw, true }
}

// NoRateLimit removes the rate limit on the public routes (for example when a proxy in front
// limits them).
func NoRateLimit() Option { return func(o *options) { o.rateLimit, o.rateLimited = nil, false } }

// HTTPTimeout is how long to wait for the platform (discovery, keys, the token endpoint). Default
// 10 seconds.
func HTTPTimeout(d time.Duration) Option { return func(o *options) { o.httpTimeout = d } }

// Module is the built module: provide [Module.Resolver] to httpfw.NewServer and [Module.Routes]
// among its tables.
type Module struct {
	routes      *httpfw.Routes
	resolver    *ContextResolver
	sessions    *Sessions
	tokens      *PlatformTokens
	environment string
}

// Resolver is the httpfw.ContextResolver: bearer token, else session cookie, else 401.
func (m *Module) Resolver() *ContextResolver { return m.resolver }

// Sessions is the browser session of a request, for handlers on routes that do not require a
// context.
func (m *Module) Sessions() *Sessions { return m.sessions }

// Tokens is the platform access-token verifier the module built (its keys are cached).
func (m *Module) Tokens() *PlatformTokens { return m.tokens }

// Routes is the module's route table: the middleware, /auth/login, /auth/callback, /auth/session
// and /auth/logout. Pass it among the tables of httpfw.NewServer.
func (m *Module) Routes() *httpfw.Routes { return m.routes }

// Build builds the module when the application starts: it checks the settings and the options,
// builds the session store, the relying party and the route table. Every problem found is reported
// at once as a *fw.StartupError. The session store is not chosen for you: name it with [SessionStore] or
// [CookieSessions].
func (d *Declared) Build(ctx context.Context, b *fw.Boot, opts ...Option) (*Module, error) {
	o := options{
		idle: 30 * time.Minute, absolute: 8 * time.Hour,
		permissions: ScopesMapper(), clients: HomeClient(), defaultLanding: "/",
		returnTo: SameOriginReturnTo(), failures: JSONFailures(), scope: "openid profile email",
		rateLimited: true, httpTimeout: 10 * time.Second,
	}
	for _, f := range opts {
		f(&o)
	}
	log := b.Logger(ModuleID)
	var problems []fw.Problem
	bad := func(key, msg string) {
		problems = append(problems, config.Problem{Module: ModuleID, Key: key, Message: msg})
	}
	for _, p := range o.problems {
		bad("", p)
	}
	if o.idle <= 0 || o.absolute <= 0 || o.httpTimeout <= 0 {
		bad("", "the idle timeout, the absolute timeout and the HTTP timeout must be positive")
	}
	if o.idle > o.absolute {
		bad("", "the idle timeout is longer than the absolute timeout")
	}
	env := b.Environment()
	development := env == "local" || env == "test"
	issuer := d.issuer.Get(b)
	baseText := d.publicBaseURL.Get(b)
	base, baseErr := checkURL("auth.public-base-url", baseText, development)
	if baseErr != "" {
		bad("auth.public-base-url", baseErr)
	} else if (base.Path != "" && base.Path != "/") || base.RawQuery != "" || base.Fragment != "" {
		bad("auth.public-base-url", "must be an origin with no path, query or fragment: "+baseText)
	}
	if _, msg := checkURL("auth.issuer", issuer, development); msg != "" {
		bad("auth.issuer", msg)
	}
	if o.store == nil && !o.cookieSessions {
		bad("", "no session store is named: use auth.SessionStore(authdb.Factory(dbs)) (the default driver), authredis.Factory, auth.MemoryStoreFactory() or auth.CookieSessions()")
	}
	var cookieKeys *CookieKeys
	if o.cookieSessions {
		if keys, ok := d.cookieKeys.Lookup(b); !ok {
			bad("auth.cookie-keys", "the cookie session store needs auth.cookie-keys")
		} else if k, err := ParseCookieKeys(keys.Reveal()); err != nil {
			bad("auth.cookie-keys", err.Error())
		} else {
			cookieKeys = k
		}
	}
	if len(problems) > 0 {
		return nil, &fw.StartupError{Problems: problems}
	}

	secure := strings.EqualFold(base.Scheme, "https")
	if !secure {
		log.Warn("SESSIONS OVER PLAIN HTTP (" + baseText + "): development only; cookies are sent without Secure and without the __Host- prefix")
	}
	if strings.HasPrefix(strings.ToLower(issuer), "http://") {
		log.Warn("the platform (" + issuer + ") is reached over plain http: development only")
	}
	var store Store
	if o.cookieSessions {
		store = NewCookieStore(cookieKeys, b.Clock())
	} else {
		var err error
		if store, err = o.store(ctx, b); err != nil {
			return nil, err
		}
		if store == nil {
			return nil, errors.New("the session store factory returned no store")
		}
	}
	secret, _ := d.clientSecret.Lookup(b)
	clientID := d.clientID.Get(b)
	origin := httpfw.OriginOf(base)

	p, err := newPlatform(issuer, o.httpTimeout, b.Clock(), log, development)
	if err != nil {
		return nil, err
	}
	tokens := &PlatformTokens{platform: p, clock: b.Clock()}
	rp := &relyingParty{
		platform: p, clientID: clientID, clientSecret: secret.Reveal(), redirectURI: origin + "/auth/callback",
		scope: o.scope, theme: o.loginTheme, clock: b.Clock(),
	}
	cookies := cookieNames{secure: secure}
	auths := &authorities{permissions: o.permissions, clients: o.clients, application: o.application, logger: log}
	mgr, err := newManager(store, b.Clock(), o.idle, o.absolute)
	if err != nil {
		return nil, err
	}
	sessions := &Sessions{manager: mgr, cookies: cookies, csrf: httpfw.SessionsGuard(base), authorities: auths, clock: b.Clock()}
	resolver := &ContextResolver{sessions: sessions, authorities: auths, log: log}
	if d.bearer {
		resolver.bearer = tokens
	}
	postLogout := ""
	if o.postLogoutPath != "" {
		postLogout = origin + o.postLogoutPath
	}
	rt := &routes{
		rp: rp, manager: mgr, sessions: sessions, cookies: cookies, returnTo: o.returnTo, defaultLanding: o.defaultLanding,
		failures: o.failures, postLogout: postLogout, clock: b.Clock(), log: log,
	}
	if !store.Revocable() {
		log.Info("session store keeps nothing on the server: logout cannot revoke a copied session cookie")
	}

	limit := o.rateLimit
	if limit == nil && o.rateLimited {
		limit, err = defaultRateLimit(b, log)
		if err != nil {
			return nil, err
		}
	}
	var publicRoute []httpfw.RouteOption
	if limit != nil {
		publicRoute = append(publicRoute, httpfw.With(limit))
	}
	table := httpfw.NewRoutes(ModuleID)
	table.Use(rt.middleware)
	table.Handle("GET /auth/login", rt.login, publicRoute...)
	table.Handle("GET /auth/callback", rt.callback, publicRoute...)
	table.Bind(httpfw.Handle(sessionapi.Session, rt.session))
	table.Bind(httpfw.Handle(sessionapi.Logout, rt.logout))
	return &Module{routes: table, resolver: resolver, sessions: sessions, tokens: tokens, environment: env}, nil
}

func defaultRateLimit(b *fw.Boot, log *slog.Logger) (httpfw.Middleware, error) {
	l, err := ratelimit.NewMemory("auth", ratelimit.MustPolicy(20, time.Minute, 20), ratelimit.MemoryClock(b.Clock()), ratelimit.MemoryLogger(log))
	if err != nil {
		return nil, err
	}
	return ratelimit.Middleware(l, peerKeyWarningOnForwarded(log), ratelimit.WithMetrics(b.Metrics()), ratelimit.WithLogger(log), ratelimit.WithClock(b.Clock()))
}

// peerKeyWarningOnForwarded keys by the TCP peer (ratelimit.ClientAddress) and warns once, the first
// time a request carries X-Forwarded-For: behind a proxy every client then shares the proxy's
// bucket (20 a minute for all of them), and the application should pass auth.RateLimit with
// ratelimit.ForwardedFor(n).
func peerKeyWarningOnForwarded(log *slog.Logger) ratelimit.KeyFunc {
	peer := ratelimit.ClientAddress()
	var warned atomic.Bool
	return func(r *http.Request) string {
		if r.Header.Get("X-Forwarded-For") != "" && warned.CompareAndSwap(false, true) {
			log.Warn("the default sign-in rate limit keys on the TCP peer, but requests carry X-Forwarded-For: behind a proxy all users share one bucket; pass auth.RateLimit with ratelimit.ForwardedFor(<trusted proxies>)")
		}
		return peer(r)
	}
}

// checkURL checks an absolute http(s) URL; plain http only in local and test. The URL is never nil;
// when the message is not empty it may be empty or unusable.
func checkURL(key, raw string, development bool) (*url.URL, string) {
	u, err := url.Parse(raw)
	if err != nil || u.Hostname() == "" || (!strings.EqualFold(u.Scheme, "https") && !strings.EqualFold(u.Scheme, "http")) {
		return &url.URL{}, key + " must be an absolute http(s) URL: " + raw // never nil: callers read the URL beside the message
	}
	if strings.EqualFold(u.Scheme, "http") && !development {
		return u, fmt.Sprintf("%s is plain http, which is allowed only when APP_ENV is local or test; session cookies would travel in clear text", key)
	}
	return u, ""
}
