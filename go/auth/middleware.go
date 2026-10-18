package auth

import (
	"errors"
	"net/http"
	"net/url"
	"strings"

	"github.com/flowcatalyst/framework/go/httpfw"
)

// isNavigation is an HTML navigation (contract 7.4): GET or HEAD, no Authorization, and
// Sec-Fetch-Mode navigate or, without that header, an Accept naming text/html.
func isNavigation(r *http.Request) bool {
	if r.Method != http.MethodGet && r.Method != http.MethodHead {
		return false
	}
	if len(r.Header.Values("Authorization")) > 0 {
		return false
	}
	if mode := r.Header.Values("Sec-Fetch-Mode"); len(mode) > 0 {
		return strings.EqualFold(strings.TrimSpace(mode[0]), "navigate")
	}
	return strings.Contains(strings.ToLower(r.Header.Get("Accept")), "text/html")
}

func isLoginRoute(r *http.Request) bool {
	return r.URL.Path == "/auth/login" || r.URL.Path == "/auth/callback"
}

// loginURL is where an unauthenticated navigation is sent: /auth/login?returnTo=<path and query>.
func loginURL(r *http.Request) string {
	target := r.URL.EscapedPath()
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}
	return "/auth/login?returnTo=" + url.QueryEscape(target)
}

// cookieWriter writes the cookies the session code asked for before the response begins.
type cookieWriter struct {
	http.ResponseWriter
	state *requestState
	begun bool
}

func (c *cookieWriter) flush() {
	if c.begun {
		return
	}
	for _, v := range c.state.takeCookies() {
		c.ResponseWriter.Header().Add("Set-Cookie", v)
	}
}

func (c *cookieWriter) WriteHeader(status int) {
	c.flush()
	c.begun = true
	c.ResponseWriter.WriteHeader(status)
}

func (c *cookieWriter) Write(b []byte) (int, error) {
	if !c.begun {
		c.WriteHeader(http.StatusOK)
	}
	return c.ResponseWriter.Write(b)
}

// Unwrap lets http.ResponseController reach the real writer.
func (c *cookieWriter) Unwrap() http.ResponseWriter { return c.ResponseWriter }

// middleware is global middleware installed by the module: it gives each request its state, writes
// the cookies the session code asked for (also on error answers), turns a 401 into a 302 to
// /auth/login for an HTML navigation (contract 7.4), and gives a rate-limited navigation to a
// sign-in route to the login failure handler (contract 4.1 and section 8).
func (rt *routes) middleware(next httpfw.HandlerFunc) httpfw.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) error {
		r, st := withState(r)
		cw := &cookieWriter{ResponseWriter: w, state: st}
		err := next(cw, r)
		if err != nil && !cw.begun {
			if se, ok := errors.AsType[httpfw.ServiceError](err); ok {
				switch {
				case se.Status() == http.StatusUnauthorized && isNavigation(r):
					cw.Header().Set("Cache-Control", "no-store")
					httpfw.Redirect(cw, loginURL(r))
					err = nil
				case se.Status() == http.StatusTooManyRequests && isLoginRoute(r) && isNavigation(r):
					// the rate limiter answered a browser navigation to a sign-in route
					for _, h := range []string{"Retry-After", "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset"} {
						cw.Header().Del(h)
					}
					cw.Header().Set("Cache-Control", "no-store")
					err = rt.failures(cw, r, LoginFailure{
						Status: http.StatusTooManyRequests, Code: "RATE_LIMITED", Message: "too many sign-in attempts; wait a minute and try again",
					})
				}
			}
		}
		cw.flush()
		return err
	}
}
