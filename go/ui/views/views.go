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
//		return errors.Join(p.Inner("#rows", rows), p.Flash(kit.FlashSuccess, "Saved.", ""))
//	})
//
// The package is plain net/http and templ: it does not import the framework's lifecycle or router,
// so an application that is not built on the framework can use it. Everything is rendered into a
// buffer before anything is written, so an error or a panic while rendering sends nothing partial.
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
	"github.com/flowcatalyst/framework/go/ui/kit"
)

// DefaultLoginParam is the query parameter the sign-in page takes the return path in
// (sessions-and-login contract 4.1).
const DefaultLoginParam = "returnTo"

// Views renders pages and answers. It is immutable after [New] and safe for concurrent use.
type Views struct {
	assets       *assets.Assets
	titleSuffix  string
	shell        func(*http.Request) kit.AppShellProps
	sessionExpr  string
	csp          string
	flashes      map[string]kit.FlashMessageProps
	plainCookies bool
}

type config struct {
	titleSuffix  string
	shell        func(*http.Request) kit.AppShellProps
	loginPath    string
	loginParam   string
	hashes       []string
	flashes      map[string]kit.FlashMessageProps
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

// WithFlashes registers the messages a flash key stands for (contract section 9). Keys are
// [a-z0-9-]{1,64}; the cookie carries only a key, so no request data is ever rendered as a message.
func WithFlashes(messages map[string]kit.FlashMessageProps) Option {
	return func(c *config) { c.flashes = messages }
}

// WithPlainHTTPCookies lets the flash cookie go without Secure on a request that arrived over plain
// HTTP. Only for an application running in local or test; without this option the cookie is always
// Secure.
func WithPlainHTTPCookies() Option { return func(c *config) { c.plainCookies = true } }

var (
	loginPathPattern  = regexp.MustCompile(`^/[A-Za-z0-9/_-]*$`)
	loginParamPattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*$`)
)

// New builds the views for an assets instance: the stylesheet, Datastar and the sprite the
// documents reference. It fails on a malformed login path or parameter, script hash or flash.
func New(a *assets.Assets, opts ...Option) (*Views, error) {
	if a == nil {
		return nil, errors.New("views: no assets")
	}
	var c config
	for _, o := range opts {
		o(&c)
	}
	csp, err := CSP(c.hashes...)
	if err != nil {
		return nil, err
	}
	if err := checkFlashes(c.flashes); err != nil {
		return nil, err
	}
	v := &Views{
		assets: a, titleSuffix: c.titleSuffix, shell: c.shell, csp: csp,
		flashes: c.flashes, plainCookies: c.plainCookies,
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
	flashes []kit.FlashMessageProps
	scripts []string
}

// WithStatus sets the status of the full document (a re-rendered form is 422). A Datastar answer is
// always 200, whatever the status.
func WithStatus(status int) PageOption { return func(c *pageConfig) { c.status = status } }

// WithPageFlashes renders messages into the document's flash stack, after the one a flash cookie
// carried. A Datastar answer ignores them; patch a message with [Patches.Flash].
func WithPageFlashes(messages ...kit.FlashMessageProps) PageOption {
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

// context is the request's context with the sprite URL the kit's icons reference.
func (v *Views) context(r *http.Request) context.Context {
	return kit.WithSpriteURL(r.Context(), v.assets.SpriteURL())
}

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
	attrs, choice := htmlAttrs(r, v.shell != nil)
	flashes, hadFlashCookie := v.takeFlash(r)
	p := documentProps{
		title:          title + v.titleSuffix,
		htmlAttrs:      attrs,
		cssURL:         v.assets.URL("kit.css"),
		jsURL:          v.assets.URL("datastar.js"),
		sessionExpired: v.sessionExpr,
		flashes:        append(flashes, c.flashes...),
	}
	seen := []string{"datastar.js"} // a script named twice (or Datastar again) is included once, in order
	for _, s := range c.scripts {
		if !slices.Contains(seen, s) {
			seen = append(seen, s)
			p.scripts = append(p.scripts, v.assets.URL(s))
		}
	}
	if v.shell != nil {
		props := v.shell(r)
		if props.Theme == "" {
			props.Theme = choice
		}
		p.shell = &props
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
	return fn(&Patches{sse: sse})
}
