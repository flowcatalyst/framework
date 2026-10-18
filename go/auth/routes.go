package auth

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"regexp"
	"slices"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
)

var statePattern = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)

// routes are /auth/login, /auth/callback, /auth/session and /auth/logout (contract section 4).
type routes struct {
	rp             *relyingParty
	manager        *manager
	sessions       *Sessions
	cookies        cookieNames
	returnTo       ReturnToPolicy
	defaultLanding string
	failures       LoginFailureHandler
	postLogout     string // absolute URL, "" for none
	clock          fw.Clock
	log            *slog.Logger
}

func (rt *routes) landing(requested string) string {
	if chosen := rt.returnTo(requested); chosen != "" && isSameOriginPath(chosen) {
		return chosen
	}
	return rt.defaultLanding
}

// ---- GET /auth/login ------------------------------------------------------------------------

func (rt *routes) login(w http.ResponseWriter, r *http.Request) error {
	requested, _, err := httpfw.QueryValue(r, "returnTo")
	if err != nil {
		requested = "" // a bad returnTo must not break signing in
	}
	login := loginData{
		state: random(32), nonce: random(32), codeVerifier: random(32),
		returnTo: rt.landing(requested), createdAt: rt.clock.Now(),
	}
	target, err := rt.rp.authorizeURL(r.Context(), login)
	if err != nil {
		if !errors.Is(err, ErrPlatformUnavailable) {
			return err
		}
		rt.log.Warn("sign-in could not start", "error", err.Error(), "cid", httpfw.CorrelationID(r).String())
		// a browser navigation gets the application's failure answer (contract 4.1.1); an API call keeps the envelope
		if isNavigation(r) {
			return rt.fail(w, r, "", http.StatusBadGateway, "PLATFORM_UNAVAILABLE", "the sign-in service cannot be reached; try again", nil)
		}
		w.Header().Set("Cache-Control", "no-store")
		return httpfw.NewError(http.StatusBadGateway, "PLATFORM_UNAVAILABLE", "the sign-in service cannot be reached; try again")
	}
	handle, err := rt.manager.createLogin(r.Context(), login)
	if err != nil {
		return err
	}
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Add("Set-Cookie", rt.cookies.set(rt.cookies.loginName(login.state), handle, int64(loginLifetime/time.Second)))
	httpfw.Redirect(w, target)
	return nil
}

// ---- GET /auth/callback ---------------------------------------------------------------------

func (rt *routes) callback(w http.ResponseWriter, r *http.Request) error {
	ctx := r.Context()
	state, _, _ := httpfw.QueryValue(r, "state")
	if !statePattern.MatchString(state) {
		return rt.fail(w, r, "", http.StatusBadRequest, "INVALID_CALLBACK", "the sign-in answer is incomplete", nil)
	}
	binding := rt.cookies.loginName(state)
	handle := readCookie(r, binding)
	if handle == "" {
		return rt.fail(w, r, binding, http.StatusBadRequest, "LOGIN_NOT_STARTED_HERE",
			"This sign-in was not started in this browser. Start the sign-in again.", nil)
	}
	login, ok, err := rt.manager.takeLogin(ctx, handle)
	if err != nil {
		w.Header().Add("Set-Cookie", rt.cookies.clear(binding)) // the binding is spent either way
		return err
	}
	if !ok || !same(login.state, state) {
		return rt.fail(w, r, binding, http.StatusBadRequest, "INVALID_STATE", "this sign-in has expired or was already used; start again", nil)
	}
	if platformError, present, _ := httpfw.QueryValue(r, "error"); present {
		code := "invalid"
		if errorCode.MatchString(platformError) {
			code = platformError
		}
		return rt.fail(w, r, binding, http.StatusBadRequest, "LOGIN_REFUSED", "the sign-in was not completed", map[string]string{"error": code})
	}
	code, _, _ := httpfw.QueryValue(r, "code")
	if code == "" || len(code) > 512 {
		return rt.fail(w, r, binding, http.StatusBadRequest, "INVALID_CALLBACK", "the sign-in answer is incomplete", nil)
	}
	ex, err := rt.rp.exchange(ctx, code, login.codeVerifier)
	if err != nil {
		return rt.platformDown(w, r, binding, err)
	}
	if ex.refused != "" {
		return rt.fail(w, r, binding, http.StatusBadRequest, "LOGIN_FAILED", "the sign-in could not be completed", map[string]string{"error": ex.refused})
	}
	v, err := rt.rp.verify(ctx, ex.idToken, login.nonce)
	if err != nil {
		return rt.platformDown(w, r, binding, err)
	}
	if v.reason != "" {
		return rt.fail(w, r, binding, http.StatusBadRequest, "INVALID_ID_TOKEN", "the sign-in answer could not be trusted", map[string]string{"reason": v.reason})
	}
	if previous := readCookie(r, rt.cookies.sessionName()); previous != "" { // rotation
		if err := rt.manager.deleteSession(ctx, previous); err != nil {
			w.Header().Add("Set-Cookie", rt.cookies.clear(binding))
			return err
		}
	}
	session, err := rt.manager.createSession(ctx, v.principal, v.authTime)
	if err != nil {
		w.Header().Add("Set-Cookie", rt.cookies.clear(binding))
		return err
	}
	rt.log.Info("signed in", "principal", v.principal.ID, "cid", httpfw.CorrelationID(r).String())
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Add("Set-Cookie", rt.cookies.set(rt.cookies.sessionName(), session, int64(rt.manager.absolute/time.Second)))
	w.Header().Add("Set-Cookie", rt.cookies.clear(binding))
	httpfw.Redirect(w, login.returnTo)
	return nil
}

func (rt *routes) platformDown(w http.ResponseWriter, r *http.Request, binding string, err error) error {
	if !errors.Is(err, ErrPlatformUnavailable) {
		w.Header().Add("Set-Cookie", rt.cookies.clear(binding))
		return err
	}
	rt.log.Warn("sign-in could not complete", "error", err.Error(), "cid", httpfw.CorrelationID(r).String())
	return rt.fail(w, r, binding, http.StatusBadGateway, "PLATFORM_UNAVAILABLE", "the sign-in service cannot be reached; try again", nil)
}

// fail answers a failed sign-in through the failure handler; every answer of the callback clears
// the binding cookie named by a well-formed state. Failures are logged at INFO with the code and
// the correlation id, never with a token, a code or the state.
func (rt *routes) fail(w http.ResponseWriter, r *http.Request, binding string, status int, code, message string, details map[string]string) error {
	rt.log.Info("sign-in failed", "code", code, "details", details, "cid", httpfw.CorrelationID(r).String())
	w.Header().Set("Cache-Control", "no-store")
	if binding != "" {
		w.Header().Add("Set-Cookie", rt.cookies.clear(binding))
	}
	return rt.failures(w, r, LoginFailure{Status: status, Code: code, Message: message, Details: details})
}

// ---- GET /auth/session (operation getAuthSession) -------------------------------------------

func (rt *routes) session(_ context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (sessionapi.AuthSession, error) {
	in.Header.Set("Cache-Control", "no-store")
	a, err := rt.sessions.Current(in.Request)
	if err != nil {
		return nil, err
	}
	if a == nil {
		return sessionapi.AuthSessionAnonymous{Authenticated: false, LoginURL: "/auth/login"}, nil
	}
	p := a.Principal
	principal := sessionapi.AuthPrincipal{ID: p.ID, Kind: sessionapi.PrincipalKind(p.Kind.String())}
	if p.Name != "" {
		principal.Name = &p.Name
	}
	if p.Email != "" {
		principal.Email = &p.Email
	}
	var tier *sessionapi.AuthTier
	if t := sessionapi.AuthTier(p.Tier); slices.Contains(t.EnumValues(), p.Tier) {
		tier = &t
	}
	perms := slices.Clone(a.Permissions)
	slices.Sort(perms)
	return sessionapi.AuthSessionAuthenticated{
		Authenticated: true, Principal: principal, Tier: tier, Client: a.Client.String(),
		Clients: nonNil(p.Clients), Applications: nonNil(p.Applications), Roles: nonNil(p.Roles), Permissions: nonNil(perms),
		AuthTime: a.AuthTime, ExpiresAt: a.ExpiresAt, IdleExpiresAt: a.IdleExpiresAt,
		Csrf: sessionapi.AuthCsrf{Mode: "fetch-metadata", JSONBodies: true},
	}, nil
}

// ---- POST /auth/logout (operation logout) ---------------------------------------------------

func (rt *routes) logout(_ context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (sessionapi.AuthLogout, error) {
	if handle := readCookie(in.Request, rt.cookies.sessionName()); handle != "" {
		if err := rt.sessions.csrf.Check(in.Request); err != nil {
			return sessionapi.AuthLogout{}, err
		}
		if err := rt.manager.deleteSession(in.Request.Context(), handle); err != nil {
			return sessionapi.AuthLogout{}, err
		}
		// cleared only when this request carried the cookie and passed the CSRF check: a forged
		// cross-site POST (which carries no cookie) must not log anyone out
		in.Header.Add("Set-Cookie", rt.cookies.clear(rt.cookies.sessionName()))
	}
	in.Header.Set("Cache-Control", "no-store")
	out := sessionapi.AuthLogout{LoggedOut: true}
	if end := rt.rp.endSessionURL(in.Request.Context(), rt.postLogout); end != "" {
		out.EndSessionURL = &end
	}
	return out, nil
}
