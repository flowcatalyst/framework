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
	field       *fieldRuntime // nil when field workers are not enabled
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
	if c.field != nil && isDPoP(r) {
		return c.authenticateField(r, strings.TrimSpace(authorization[len("DPoP "):]))
	}
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

// isDPoP reports whether the request uses the DPoP authorization scheme (any case).
func isDPoP(r *http.Request) bool {
	a := r.Header.Get("Authorization")
	return len(a) > len("DPoP ") && strings.EqualFold(a[:len("DPoP ")], "DPoP ")
}

// authenticateField is step 1b of contract section 7.1 (amended by section 15): a field access token
// with its DPoP proof. The answers: 401 with WWW-Authenticate DPoP error="invalid_token", 401
// USE_DPOP_NONCE with error="use_dpop_nonce", 403 PIN_CHANGE_REQUIRED for a PIN-change-only token,
// and 503 when the keys or the replay cache cannot be read.
func (c *ContextResolver) authenticateField(r *http.Request, token string) (Authenticated, error) {
	st := stateOf(r)
	invalid := func() (Authenticated, error) {
		st.setHeader("WWW-Authenticate", `DPoP error="invalid_token"`)
		return Authenticated{}, unauthenticated()
	}
	proofs := r.Header.Values("DPoP")
	if len(proofs) > 1 {
		c.log.Debug("refused a field credential", "reason", "invalid_proof")
		return invalid()
	}
	proof := ""
	if len(proofs) == 1 {
		proof = proofs[0]
	}
	switch res := c.field.tokens.Verify(r.Context(), token, proof, r.Method, r.URL.EscapedPath()).(type) {
	case FieldValid:
		return fieldAuthenticated(res.Principal, c.field.permissions)
	case FieldInvalid:
		c.log.Debug("refused a field credential", "reason", res.Reason)
		if res.Reason == "pcr" {
			return Authenticated{}, httpfw.Forbidden("PIN_CHANGE_REQUIRED", "the worker must change the PIN before using this application")
		}
		return invalid()
	case FieldNonceRequired:
		st.setHeader("WWW-Authenticate", `DPoP error="use_dpop_nonce"`)
		return Authenticated{}, httpfw.NewError(http.StatusUnauthorized, "USE_DPOP_NONCE", "the proof needs a current DPoP-Nonce; retry with the one in the response")
	case FieldUnavailable:
		c.log.Warn("a field credential could not be checked", "reason", res.Reason)
		return Authenticated{}, httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", "tokens cannot be checked right now; try again")
	}
	return invalid()
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
