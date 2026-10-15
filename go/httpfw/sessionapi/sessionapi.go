// Package sessionapi is the document side of the browser session endpoints (sessions-and-login
// contract section 4.5, OpenAPI contract section 8): the two typed operations GET /auth/session and
// POST /auth/logout with their fixed components, and the security schemes the session resolver
// contributes. Declare adds them to an application's OpenAPI document, so the SPA's client is
// generated from it; conformance/openapi/auth.json is its vector.
//
// The handlers belong to the sessions and login milestone (G5b), which binds these operations to
// the real session store. Until then [Unavailable] binds both to a 501 answer, so an application
// that documents the endpoints starts and says plainly that they are not built.
package sessionapi

import (
	"context"
	"net/http"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/openapi"
)

// SessionCookie is the production name of the session cookie, documented whatever the
// environment.
const SessionCookie = "__Host-fw_session"

// AuthTier is the tier of the principal's token.
type AuthTier string

// The tiers the platform issues.
const (
	TierAnchor  AuthTier = "ANCHOR"
	TierPartner AuthTier = "PARTNER"
	TierClient  AuthTier = "CLIENT"
)

// EnumValues implements openapi.EnumValuer.
func (AuthTier) EnumValues() []string { return []string{"ANCHOR", "PARTNER", "CLIENT"} }

// PrincipalKind says whether the principal is a person or a service account.
type PrincipalKind string

// The kinds.
const (
	KindUser    PrincipalKind = "USER"
	KindService PrincipalKind = "SERVICE"
)

// EnumValues implements openapi.EnumValuer.
func (PrincipalKind) EnumValues() []string { return []string{"USER", "SERVICE"} }

// AuthPrincipal is who is signed in.
type AuthPrincipal struct {
	ID    string        `json:"id"`
	Kind  PrincipalKind `json:"kind"`
	Name  *string       `json:"name,omitzero"`
	Email *string       `json:"email,omitzero"`
}

// AuthCsrf says how unsafe requests are protected: Mode is always "fetch-metadata" and JSONBodies
// always true.
type AuthCsrf struct {
	Mode       string `json:"mode"`
	JSONBodies bool   `json:"jsonBodies"`
}

// AuthSession is the answer of GET /auth/session: AuthSessionAuthenticated or AuthSessionAnonymous.
type AuthSession interface{ isAuthSession() }

// AuthSessionAuthenticated is the answer for a signed-in browser.
type AuthSessionAuthenticated struct {
	Authenticated bool          `json:"authenticated"`
	Principal     AuthPrincipal `json:"principal"`
	Tier          *AuthTier     `json:"tier,omitzero"`
	Client        string        `json:"client"`
	Clients       []string      `json:"clients"`
	Applications  []string      `json:"applications"`
	Roles         []string      `json:"roles"`
	Permissions   []string      `json:"permissions"`
	AuthTime      time.Time     `json:"authTime"`
	ExpiresAt     time.Time     `json:"expiresAt"`
	IdleExpiresAt time.Time     `json:"idleExpiresAt"`
	Csrf          AuthCsrf      `json:"csrf"`
}

// AuthSessionAnonymous is the answer when nobody is signed in.
type AuthSessionAnonymous struct {
	Authenticated bool   `json:"authenticated"`
	LoginURL      string `json:"loginUrl"`
}

func (AuthSessionAuthenticated) isAuthSession() {}
func (AuthSessionAnonymous) isAuthSession()     {}

// AuthLogout is the answer of POST /auth/logout; EndSessionURL is absent when the provider has no
// end-session endpoint.
type AuthLogout struct {
	LoggedOut     bool    `json:"loggedOut"`
	EndSessionURL *string `json:"endSessionUrl,omitzero"`
}

// The operations. Both are public: they answer without a session.
var (
	// Session is GET /auth/session.
	Session = httpfw.Get[AuthSession]("/auth/session").
		ID("getAuthSession").Summary("Read the browser session").Tag("auth").Build()

	// Logout is POST /auth/logout.
	Logout = httpfw.PostNoBody[AuthLogout]("/auth/logout").
		ID("logout").Summary("Sign out of the application").Tag("auth").
		Error(403, "CROSS_ORIGIN_REQUEST", "the request did not come from this application's pages").
		Error(415, "UNSUPPORTED_MEDIA_TYPE", "a request body must be JSON").Build()
)

// Declare adds the two operations, their fixed components and the security schemes the session
// resolver contributes (the cookie, and a bearer token when bearer is true) to the document.
func Declare(web *httpfw.Declared, bearer bool) {
	web.Operations(Session, Logout)
	web.Schema(openapi.TypeOf[AuthSession](), openapi.OneOf(openapi.TypeOf[AuthSessionAuthenticated](), openapi.TypeOf[AuthSessionAnonymous]()))
	web.Property(openapi.TypeOf[AuthSessionAuthenticated](), "authenticated", openapi.Bool().ConstBool(true))
	web.Property(openapi.TypeOf[AuthSessionAnonymous](), "authenticated", openapi.Bool().ConstBool(false))
	web.Property(openapi.TypeOf[AuthCsrf](), "mode", openapi.String().ConstString("fetch-metadata"))
	web.Property(openapi.TypeOf[AuthCsrf](), "jsonBodies", openapi.Bool().ConstBool(true))
	web.Property(openapi.TypeOf[AuthLogout](), "loggedOut", openapi.Bool().ConstBool(true))
	web.Security("auth", "session", openapi.Cookie(SessionCookie))
	if bearer {
		web.Security("auth", "bearer", openapi.Bearer("JWT"))
	}
}

// Unavailable binds both operations to a 501 NOT_IMPLEMENTED answer: sessions and login are the
// G5b milestone. It is the honest placeholder for an application that documents the endpoints
// before they exist.
func Unavailable(rt *httpfw.Routes) {
	rt.Bind(httpfw.Handle(Session, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (AuthSession, error) {
		return nil, notBuilt()
	}))
	rt.Bind(httpfw.Handle(Logout, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (AuthLogout, error) {
		return AuthLogout{}, notBuilt()
	}))
}

func notBuilt() error {
	return httpfw.NewError(http.StatusNotImplemented, "NOT_IMPLEMENTED", "sessions and login are not built in this framework yet")
}
