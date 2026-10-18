package auth

import (
	"context"
	"errors"

	"github.com/flowcatalyst/framework/go/fw"
)

// Testing makes authenticated requests in an application's tests (contract section 9): it creates a
// real session in the application's configured store and returns the Cookie header, so a test
// exercises the real routes, resolver, CSRF rules and permission mapping without an identity
// provider. Get one with [Module.Testing]; it is refused unless APP_ENV is "test".
//
//	cookie, _ := testing.Cookie(ctx, auth.User("prn_1", "ingestor:operator"))
//	req.Header.Set("Cookie", cookie)
//	req.Header.Set("Sec-Fetch-Site", "same-origin") // for an unsafe method
type Testing struct{ sessions *Sessions }

// Testing returns the test helper of the module, or an error unless APP_ENV is "test".
func (m *Module) Testing() (*Testing, error) {
	if m.environment != "test" {
		return nil, errors.New("auth.Testing is allowed only when APP_ENV is 'test' (it is '" + m.environment + "')")
	}
	return &Testing{sessions: m.sessions}, nil
}

// Cookie signs a principal in: a new session in the configured store. The result is the Cookie
// header value, "<name>=<handle>".
func (t *Testing) Cookie(ctx context.Context, p Principal) (string, error) {
	handle, err := t.sessions.manager.createSession(ctx, p, t.sessions.manager.clock.Now())
	if err != nil {
		return "", err
	}
	return t.sessions.cookies.sessionName() + "=" + handle, nil
}

// CookieName is the session cookie's name: __Host-fw_session, or fw_session over http.
func (t *Testing) CookieName() string { return t.sessions.cookies.sessionName() }

// User is an anchor user who may act for every client and holds roles ("<application>:<role>").
func User(id string, roles ...string) Principal {
	return Principal{
		ID: id, Kind: fw.PrincipalUser, Name: "Test User", Email: id + "@example.test", Tier: "ANCHOR",
		Clients: []string{"*"}, Applications: []string{"*"}, Roles: nonNil(roles), Scopes: []string{},
	}
}

// ClientUser is a user of one client: clientID is the client's platform id (for example clt_1) and
// identifier the client's identifier, which becomes the Context's client.
func ClientUser(id, clientID, identifier string, roles ...string) Principal {
	return Principal{
		ID: id, Kind: fw.PrincipalUser, Name: "Test User", Email: id + "@example.test", Tier: "CLIENT", HomeClient: clientID,
		Clients: []string{clientID + ":" + identifier}, Applications: []string{"*"}, Roles: nonNil(roles), Scopes: []string{},
	}
}
