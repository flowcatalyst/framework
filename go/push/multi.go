package push

import (
	"crypto/ed25519"
	"errors"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/internal/pushrow"
)

// Multi-application hosts (push contract 17.16): one host, such as a platform push gateway, serves
// the topics of many applications. Each application's rules, ticket keys and limits come from
// providers that may change at any time; the module reads them for every subscribe, listen and
// publish and keeps none of them.

// DefaultAppMaxStreams is the most streams one application may have open on an instance when
// neither the [AppLimitProvider] nor [DefaultAppLimits] says otherwise.
const DefaultAppMaxStreams = 20_000

// RuleProvider gives the topic rules of an application (push contract 6.3), by application code.
// An application it does not know has none, so its topics are denied. Rules returns a snapshot the
// module does not change; it is called for every subscribe and must be quick (a cache the host
// refreshes when the rules change).
type RuleProvider interface {
	Rules(application string) []Rule
}

// RuleProviderFunc adapts a function to [RuleProvider].
type RuleProviderFunc func(application string) []Rule

// Rules calls f(application).
func (f RuleProviderFunc) Rules(application string) []Rule { return f(application) }

// TicketKeyResolver gives the public key an application signs its tickets with, by the ticket's
// issuer (the application code) and key id. It reports false for an issuer or a kid it does not
// know, and the ticket then grants nothing. It is called for every ticket and must be quick.
type TicketKeyResolver interface {
	PublicKey(issuer, kid string) (ed25519.PublicKey, bool)
}

// TicketKeyResolverFunc adapts a function to [TicketKeyResolver].
type TicketKeyResolverFunc func(issuer, kid string) (ed25519.PublicKey, bool)

// PublicKey calls f(issuer, kid).
func (f TicketKeyResolverFunc) PublicKey(issuer, kid string) (ed25519.PublicKey, bool) {
	return f(issuer, kid)
}

// AppLimits are the limits of one application; a field that is zero or less takes the default.
type AppLimits struct {
	// MaxStreams is the most streams of the application open on one instance. A stream whose
	// granted topics span several applications counts against each of them.
	MaxStreams int
	// MaxDataBytes is the largest push data of the application, in bytes of JSON, at most
	// [MaxDataBytes]. It replaces push.max-data-bytes for the application's topics; the default is
	// push.max-data-bytes.
	MaxDataBytes int
}

// AppLimitProvider gives the limits of an application, by application code. It is called for every
// subscribe, listen and publish and must be quick.
type AppLimitProvider interface {
	AppLimits(application string) AppLimits
}

// AppLimitProviderFunc adapts a function to [AppLimitProvider].
type AppLimitProviderFunc func(application string) AppLimits

// AppLimits calls f(application).
func (f AppLimitProviderFunc) AppLimits(application string) AppLimits { return f(application) }

// multiApp is the multi-application mode of a host.
type multiApp struct {
	rules    RuleProvider
	keys     TicketKeyResolver
	limits   AppLimitProvider // nil: every application has the defaults
	defaults AppLimits
}

// MultiOption configures [MultiApplication].
type MultiOption func(*multiApp)

// AppLimitsFrom gives the per-application limits, read for every subscribe, listen and publish.
func AppLimitsFrom(p AppLimitProvider) MultiOption { return func(m *multiApp) { m.limits = p } }

// DefaultAppLimits sets the limits of an application the [AppLimitProvider] gives none for (and of
// every application without a provider). Default: [DefaultAppMaxStreams] streams, and
// push.max-data-bytes.
func DefaultAppLimits(l AppLimits) MultiOption { return func(m *multiApp) { m.defaults = l } }

// MultiApplication makes the host serve many applications (push contract 17.16). A topic is granted
// by a rule of its own application only (rules.Rules(topic's first segment)); a ticket is verified
// with the key keys gives for its issuer and kid, and grants topics of its issuer's application
// only; tickets of several issuers compose in one subscribe. An application the providers do not
// know has its topics denied, never an error for the whole subscribe. Per-application stream limits
// apply, and [Pushes.Publish] accepts a push for any application, its data checked against the
// application's [AppLimits].
//
// In this mode the host's own rules (Declared.Topic, Declared.PublicTopic) are a start-up problem:
// the providers are the only source. push.ticket.key is used only to mint tickets with
// [Pushes.Ticket] and to serve GET {base}/push/keys; declare the module with [OptionalTicketKey]
// for a host that mints nothing, and the keys endpoint then lists no key.
func MultiApplication(rules RuleProvider, keys TicketKeyResolver, opts ...MultiOption) Option {
	return func(o *options) {
		m := &multiApp{rules: rules, keys: keys, defaults: AppLimits{MaxStreams: DefaultAppMaxStreams}}
		for _, f := range opts {
			f(m)
		}
		o.multi = m
	}
}

var errNoProviders = errors.New("push.MultiApplication needs a RuleProvider and a TicketKeyResolver")

// limitsOf returns the effective limits of an application.
func (m *multiApp) limitsOf(app string) AppLimits {
	var l AppLimits
	if m.limits != nil {
		l = m.limits.AppLimits(app)
	}
	if l.MaxStreams <= 0 {
		l.MaxStreams = m.defaults.MaxStreams
	}
	if l.MaxStreams <= 0 {
		l.MaxStreams = DefaultAppMaxStreams
	}
	if l.MaxDataBytes <= 0 {
		l.MaxDataBytes = m.defaults.MaxDataBytes
	}
	return l
}

// rulesOf returns the rules of an application that are the application's: a rule of another
// application's pattern, whatever the provider gave, is left out.
func (m *multiApp) rulesOf(app string) []Rule {
	var out []Rule
	for _, r := range m.rules.Rules(app) {
		if r.pat.segs != nil && r.pat.application() == app {
			out = append(out, r)
		}
	}
	return out
}

// applicationOf returns a topic's application, its first segment.
func applicationOf(topic string) string {
	app, _, _ := strings.Cut(topic, ":")
	return app
}

// applicationsOf returns the distinct applications of topics, in first-seen order.
func applicationsOf(topics []string) []string {
	var out []string
	for _, t := range topics {
		app := applicationOf(t)
		if !pushrow.IsApplication(app) {
			continue
		}
		if !slices.Contains(out, app) {
			out = append(out, app)
		}
	}
	return out
}

// appCap is an application's stream limit, resolved before the hub's lock is taken.
type appCap struct {
	app string
	max int
}

// appCaps resolves the stream limits of the applications of topics; nil on a single-application
// host.
func (p *Pushes) appCaps(topics []string) []appCap {
	if p.multi == nil {
		return nil
	}
	apps := applicationsOf(topics)
	caps := make([]appCap, len(apps))
	for i, a := range apps {
		caps[i] = appCap{app: a, max: p.multi.limitsOf(a).MaxStreams}
	}
	return caps
}

// maxDataFor returns the largest data a push on topic may carry.
func (p *Pushes) maxDataFor(topic string) int {
	if p.multi == nil {
		return p.cfg.maxData
	}
	n := p.multi.limitsOf(applicationOf(topic)).MaxDataBytes
	if n <= 0 {
		return p.cfg.maxData
	}
	return min(n, pushrow.MaxDataBytes)
}
