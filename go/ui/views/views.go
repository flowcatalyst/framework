// Package views is the page layer of the server-rendered UI (contract server-rendered-ui.md
// sections 5 to 10): one page template answers a browser navigation with a full HTML document and a
// Datastar request with an element patch of the same content, so a link, a form post and a live
// update all use one template.
//
//	v, err := views.New(a,
//		views.WithTitleSuffix(" - Orders"),
//		views.WithShell(shellFor),
//		views.WithLogin("/auth/login", ""))
//	...
//	err = v.Page(w, r, "Orders", ordersPage(listing))   // document, or a patch of #fw-page
//	err = v.Stream(w, r, func(p *views.Patches) error { // a Datastar answer of several patches
//		return errors.Join(p.Inner("#rows", rows), p.Flash(look.FlashSuccess, "Saved.", ""))
//	})
//
// The package is plain net/http and templ: it does not import the framework's lifecycle or router,
// so an application that is not built on the framework can use it. Everything is rendered into a
// buffer before anything is written, so an error or a panic while rendering sends nothing partial.
//
// Documents are rendered through a [look.Look], the component kit: this module's kit by default, or a
// copy of it kept under the application's own import path ([WithLook], [WithShellComponent],
// [WithStylesheets]). With none of those options the output is the module's kit's, byte for byte.
package views

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net/http"
	"regexp"
	"slices"
	"strings"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/i18n"
	"github.com/flowcatalyst/framework/go/ui/kit"
	"github.com/flowcatalyst/framework/go/ui/look"
)

// DefaultLoginParam is the query parameter the sign-in page takes the return path in
// (sessions-and-login contract 4.1).
const DefaultLoginParam = "returnTo"

// Views renders pages and answers. It is immutable after [New] and safe for concurrent use.
type Views struct {
	assets       *assets.Assets
	look         look.Look
	titleSuffix  string
	shell        func(*http.Request) templ.Component
	stylesheets  []string
	sessionExpr  string
	csp          string
	flashes      map[string]look.Flash
	plainCookies bool
	locales      *i18n.Resolver
	bundle       *i18n.Bundle
}

type config struct {
	locales      *i18n.Config
	look         look.Look
	lookSet      bool
	titleSuffix  string
	shell        func(*http.Request) kit.AppShellProps
	shellComp    func(*http.Request) templ.Component
	stylesheets  []string
	loginPath    string
	loginParam   string
	hashes       []string
	imageSources []string
	flashes      map[string]look.Flash
	plainCookies bool
}

// Option adjusts [New].
type Option func(*config)

// WithTitleSuffix appends text to every page title, for example " - Orders".
func WithTitleSuffix(suffix string) Option { return func(c *config) { c.titleSuffix = suffix } }

// WithShell wraps documents in the app shell (sidebar, user menu, top bar) built from the request.
// The theme is filled in from the cookies when the props leave it empty. Nothing in the module names
// an application path: every link in the shell comes from these props.
func WithShell(shell func(*http.Request) kit.AppShellProps) Option {
	return func(c *config) { c.shell = shell }
}

// WithShellComponent wraps documents in the application's own shell: fn returns, for a request, the
// component that wraps the page; its children are the flash stack and the page. Use it with a copy of
// the kit ([WithLook]) whose shell is not [kit.AppShell]. It cannot be combined with [WithShell]
// ([New] fails). The look's HTMLAttrs are asked for the shell's attributes too. When fn returns nil
// for a request, that document is rendered without a shell (the look's Main).
func WithShellComponent(fn func(*http.Request) templ.Component) Option {
	return func(c *config) { c.shellComp = fn }
}

// WithLook renders documents, flash messages and patches with this component kit instead of the
// module's ([kit.Look]): the stylesheet, the attributes of <html>, the wrapper of a document without a
// shell, the flash stack and the flash messages. A nil look is an error at [New].
func WithLook(l look.Look) Option {
	return func(c *config) {
		c.look = l
		c.lookSet = true
	}
}

// WithStylesheets links further stylesheets, by asset name, after the look's own, in the order given
// and once each. Each must be a CSS asset, otherwise [New] fails. Without it the document links the
// look's stylesheet alone.
func WithStylesheets(names ...string) Option {
	return func(c *config) { c.stylesheets = append(c.stylesheets, names...) }
}

// WithLogin sends a user whose session expired (a Datastar action answered 401) to the local path
// login, with the current path and query percent-encoded in the query parameter param (empty means
// [DefaultLoginParam], "returnTo"). An application with its own sign-in may name the parameter.
// path is a local path of letters, digits, "/", "_" and "-"; param starts with a letter and has
// letters, digits and "_".
func WithLogin(path, param string) Option {
	return func(c *config) { c.loginPath, c.loginParam = path, param }
}

// WithScriptHash allows an inline script of the application by its hash (sha256-, sha384- or
// sha512- followed by base64, without quotes). The module itself uses none.
func WithScriptHash(hashes ...string) Option {
	return func(c *config) { c.hashes = append(c.hashes, hashes...) }
}

// WithImageSources lets documents load images from other hosts, for example a map's tile server:
// each source is added to the policy's img-src after 'self' data: (contract section 6 rule 1,
// amended 2026-11-12). A source is an https origin, https://host or https://host:port, optionally
// with one leading wildcard label (https://*.example.org), with nothing after the host or port;
// anything else is an error at [New] (see [CSPWithImageSources]). Nothing else in the policy changes.
func WithImageSources(sources ...string) Option {
	return func(c *config) { c.imageSources = append(c.imageSources, sources...) }
}

// WithFlashes registers the messages a flash key stands for (contract section 9). Keys are
// [a-z0-9-]{1,64}; the cookie carries only a key, so no request data is ever rendered as a message.
func WithFlashes(messages map[string]look.Flash) Option {
	return func(c *config) { c.flashes = messages }
}

// WithLocales resolves the locale of every request the views render (contract
// i18n.md section 3) when no [i18n.Middleware] did: the document's lang and the
// kit's texts follow it, and the config's bundle (the application's catalogs over
// the kit's) is used. Without it, and without the middleware, pages are English.
func WithLocales(cfg i18n.Config) Option { return func(c *config) { c.locales = &cfg } }

// WithPlainHTTPCookies lets the flash cookie go without Secure on a request that arrived over plain
// HTTP. Only for an application running in local or test; without this option the cookie is always
// Secure.
func WithPlainHTTPCookies() Option { return func(c *config) { c.plainCookies = true } }

var (
	loginPathPattern  = regexp.MustCompile(`^/[A-Za-z0-9/_-]*$`)
	loginParamPattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*$`)
)

// New builds the views for an assets instance: the stylesheet, Datastar and the sprite the
// documents reference. It fails on a malformed login path or parameter, script hash, image source or
// flash.
func New(a *assets.Assets, opts ...Option) (*Views, error) {
	if a == nil {
		return nil, errors.New("views: no assets")
	}
	var c config
	for _, o := range opts {
		o(&c)
	}
	csp, err := CSPWithImageSources(c.hashes, c.imageSources)
	if err != nil {
		return nil, err
	}
	if err := checkFlashes(c.flashes); err != nil {
		return nil, err
	}
	l := look.Look(kit.Look{})
	if c.lookSet {
		if c.look == nil {
			return nil, errors.New("views: WithLook was given a nil look")
		}
		l = c.look
	}
	if c.shell != nil && c.shellComp != nil {
		return nil, errors.New("views: WithShell and WithShellComponent cannot both be set")
	}
	if err := checkStylesheets(a, l.Stylesheet(), c.stylesheets); err != nil {
		return nil, err
	}
	v := &Views{
		assets: a, look: l, titleSuffix: c.titleSuffix, shell: shellComponent(c), csp: csp,
		flashes: c.flashes, plainCookies: c.plainCookies,
		stylesheets: stylesheetNames(l.Stylesheet(), c.stylesheets),
	}
	if c.locales != nil {
		v.locales, v.bundle = i18n.NewResolver(*c.locales), c.locales.Bundle
	}
	if c.loginPath != "" || c.loginParam != "" {
		param := c.loginParam
		if param == "" {
			param = DefaultLoginParam
		}
		if !loginPathPattern.MatchString(c.loginPath) || !ds.IsLocalPath(c.loginPath) {
			return nil, fmt.Errorf("views: login path %q must be a local path matching /[A-Za-z0-9/_-]* (one leading slash)", c.loginPath)
		}
		if !loginParamPattern.MatchString(param) {
			return nil, fmt.Errorf("views: login parameter %q must match [A-Za-z][A-Za-z0-9_]*", param)
		}
		v.sessionExpr = sessionExpiredExpr(c.loginPath, param)
	}
	return v, nil
}

// shellComponent is the shell of the configuration: the application's own, the kit's [kit.AppShell]
// built from the props (the theme filled in from the cookies when the props leave it empty), or nil.
func shellComponent(c config) func(*http.Request) templ.Component {
	if c.shellComp != nil {
		return c.shellComp
	}
	props := c.shell
	if props == nil {
		return nil
	}
	return func(r *http.Request) templ.Component {
		p := props(r)
		if p.Theme == "" {
			p.Theme, _ = kit.ThemeFromRequest(r)
		}
		return kit.AppShell(p)
	}
}

// checkStylesheets fails unless the look's stylesheet and every extra one is a CSS asset.
func checkStylesheets(a *assets.Assets, lookSheet string, extra []string) error {
	names := a.Names()
	for _, s := range append([]string{lookSheet}, extra...) {
		if !strings.HasSuffix(s, ".css") || !slices.Contains(names, s) {
			return fmt.Errorf("views: stylesheet %q is not a CSS asset (assets: %s)", s, strings.Join(names, ", "))
		}
	}
	return nil
}

// stylesheetNames is the look's stylesheet followed by the extra ones, each once, in order.
func stylesheetNames(lookSheet string, extra []string) []string {
	out := []string{lookSheet}
	for _, s := range extra {
		if !slices.Contains(out, s) {
			out = append(out, s)
		}
	}
	return out
}

// sessionExpiredExpr is the body's datastar-fetch handler: a Datastar action answered 401 (the
// session expired while the page was open) navigates to the sign-in page, which returns here after.
// Every other failed request is the flash stack's fallback.
func sessionExpiredExpr(path, param string) string {
	return "evt.detail.type === 'error' && evt.detail.argsRaw.status === '401' && window.location.assign('" +
		path + "?" + param + "=' + encodeURIComponent(location.pathname + location.search))"
}

// PageOption adjusts one [Views.Page] call.
type PageOption func(*pageConfig)

type pageConfig struct {
	status  int
	flashes []look.Flash
	scripts []string
}

// WithStatus sets the status of the full document (a re-rendered form is 422). A Datastar answer is
// always 200, whatever the status.
func WithStatus(status int) PageOption { return func(c *pageConfig) { c.status = status } }

// WithPageFlashes renders messages into the document's flash stack, after the one a flash cookie
// carried. A Datastar answer ignores them; patch a message with [Patches.Flash].
func WithPageFlashes(messages ...look.Flash) PageOption {
	return func(c *pageConfig) { c.flashes = append(c.flashes, messages...) }
}

// WithScripts lists the island scripts the page uses, by asset name ("fc-code-editor.js"); each
// becomes a <script type="module"> in the head. Only the assets' JavaScript files can be named.
func WithScripts(names ...string) PageOption {
	return func(c *pageConfig) { c.scripts = append(c.scripts, names...) }
}

func (v *Views) pageConfig(opts []PageOption) (pageConfig, error) {
	c := pageConfig{status: http.StatusOK}
	for _, o := range opts {
		o(&c)
	}
	if c.status < 200 || c.status > 599 || c.status == http.StatusNoContent || c.status == http.StatusNotModified {
		return c, fmt.Errorf("views: %d is not a status a document can have", c.status)
	}
	names := v.assets.Names()
	for _, s := range c.scripts {
		if !strings.HasSuffix(s, ".js") || !slices.Contains(names, s) {
			return c, fmt.Errorf("views: script %q is not a JavaScript asset (assets: %s)", s, strings.Join(names, ", "))
		}
	}
	return c, nil
}

// Context is the request's context as the views render with it: what the look adds (the module's kit:
// the sprite URL its icons reference), and the resolved locale and bundle (the [i18n.Middleware]'s, else those of
// [WithLocales]). An application translating its own texts in a handler uses [i18n.T] with it.
func (v *Views) Context(r *http.Request) context.Context {
	ctx := v.look.Context(r.Context(), v.assets)
	if v.locales != nil && !i18n.Resolved(ctx) {
		ctx = i18n.WithLocale(ctx, v.locales.Resolve(r))
		if v.bundle != nil {
			ctx = i18n.WithBundle(ctx, v.bundle)
		}
	}
	return ctx
}

func (v *Views) context(r *http.Request) context.Context { return v.Context(r) }

// Page answers with a page: the full document for a navigation, a patch of #fw-page for a request
// with Datastar-Request: true. title is without the configured suffix. It returns an error (and
// writes nothing) when rendering fails or an option is invalid; a component that panics panics
// before anything is written.
func (v *Views) Page(w http.ResponseWriter, r *http.Request, title string, content templ.Component, opts ...PageOption) error {
	c, err := v.pageConfig(opts)
	if err != nil {
		return err
	}
	if ds.IsDatastar(r) {
		return v.Stream(w, r, func(p *Patches) error { return p.Page(content) })
	}
	return v.document(w, r, c, title, content)
}

func (v *Views) document(w http.ResponseWriter, r *http.Request, c pageConfig, title string, content templ.Component) error {
	// The shell is asked first: a request it gives no shell for is rendered, attributes included, as a
	// document without one.
	var shell templ.Component
	if v.shell != nil {
		shell = v.shell(r)
	}
	flashes, hadFlashCookie := v.takeFlash(r, v.context(r))
	p := documentProps{
		look:           v.look,
		title:          title + v.titleSuffix,
		htmlAttrs:      v.look.HTMLAttrs(r, shell != nil),
		shell:          shell,
		jsURL:          v.assets.URL("datastar.js"),
		sessionExpired: v.sessionExpr,
		flashes:        append(flashes, c.flashes...),
	}
	for _, s := range v.stylesheets {
		p.cssURLs = append(p.cssURLs, v.assets.URL(s))
	}
	seen := []string{"datastar.js"} // a script named twice (or Datastar again) is included once, in order
	for _, s := range c.scripts {
		if !slices.Contains(seen, s) {
			seen = append(seen, s)
			p.scripts = append(p.scripts, v.assets.URL(s))
		}
	}
	var buf bytes.Buffer
	if err := document(p, wrapPage(content)).Render(v.context(r), &buf); err != nil {
		return fmt.Errorf("views: render %q: %w", title, err)
	}
	h := w.Header()
	h.Set("Content-Type", "text/html; charset=utf-8")
	h.Set("Content-Security-Policy", v.csp)
	h.Set("Cross-Origin-Opener-Policy", "same-origin")
	h.Set("Permissions-Policy", "camera=(), microphone=(), geolocation=()")
	h.Set("X-Content-Type-Options", "nosniff")
	h.Set("Cache-Control", "private, no-cache")
	if hadFlashCookie {
		w.Header().Add("Set-Cookie", v.flashCookie(r, "", 0))
	}
	w.WriteHeader(c.status)
	_, err := w.Write(buf.Bytes())
	return err
}

// Stream answers a Datastar request with the patches fn writes: text/event-stream, status 200 and no
// Content-Security-Policy (it is not a document). Each patch is rendered before it is written; an
// error fn returns ends the stream (the status line is already sent, so it can only be logged by
// the caller).
func (v *Views) Stream(w http.ResponseWriter, r *http.Request, fn func(*Patches) error) error {
	sse := ds.NewSSE(w, r.WithContext(v.context(r)))
	return fn(&Patches{sse: sse, look: v.look})
}
