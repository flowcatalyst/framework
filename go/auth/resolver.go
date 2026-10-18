package auth

import (
	"log/slog"
	"net/http"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// ContextResolver is the real httpfw.ContextResolver (contract section 7.1): a platform bearer
// token wins and never falls back to the cookie; else the session cookie; else 401
// UNAUTHENTICATED, which the module's middleware turns into a 302 for an HTML navigation.
type ContextResolver struct {
	sessions    *Sessions
	bearer      *PlatformTokens // nil when bearer tokens are not accepted
	authorities *authorities
	log         *slog.Logger
}

func unauthenticated() error { return httpfw.Unauthenticated("authentication is required") }

// Resolve implements httpfw.ContextResolver.
func (c *ContextResolver) Resolve(r *http.Request) (fw.Context, error) {
	a, err := c.authenticate(r)
	if err != nil {
		return fw.Context{}, err
	}
	stateOf(r).setAuthenticated(&a)
	return c.authorities.context(a, r)
}

func (c *ContextResolver) authenticate(r *http.Request) (Authenticated, error) {
	authorization := r.Header.Get("Authorization")
	if c.bearer != nil && authorization != "" {
		if len(authorization) < 7 || !strings.EqualFold(authorization[:7], "Bearer ") {
			return Authenticated{}, unauthenticated()
		}
		switch res := c.bearer.Verify(r.Context(), strings.TrimSpace(authorization[7:])).(type) {
		case Valid:
			return c.authorities.authenticate(res.Token.Principal, r, SourceBearer, res.Token.ExpiresAt, time.Time{}, time.Time{})
		case Invalid:
			c.log.Debug("refused a bearer token", "reason", res.Reason)
			return Authenticated{}, unauthenticated()
		case Unavailable:
			c.log.Warn("a bearer token could not be checked", "reason", res.Reason)
			return Authenticated{}, httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", "tokens cannot be checked right now; try again")
		}
	}
	session, err := c.sessions.Current(r)
	if err != nil {
		return Authenticated{}, err
	}
	if session == nil {
		return Authenticated{}, unauthenticated()
	}
	return *session, nil
}

// Require is route middleware that answers 403 FORBIDDEN unless the Context holds the permission
// (fw.Context.HasPermission). Use it on a route that requires a context:
//
//	rt.HandleContext("POST /api/v1/ingestions", create, httpfw.With(auth.Require("ingestor:ingestion:manage")))
func Require(permission string) httpfw.Middleware {
	p, err := fw.ParsePermission(permission)
	if err != nil {
		panic("auth.Require: " + err.Error())
	}
	return func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			if !httpfw.ContextOf(r).HasPermission(p) {
				return httpfw.Forbidden("FORBIDDEN", "this needs the permission "+permission)
			}
			return next(w, r)
		}
	}
}
