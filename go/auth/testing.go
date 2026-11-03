package auth

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"time"

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
type Testing struct {
	sessions *Sessions
	field    *FieldTokens
}

// Testing returns the test helper of the module, or an error unless APP_ENV is "test".
func (m *Module) Testing() (*Testing, error) {
	if m.environment != "test" {
		return nil, errors.New("auth.Testing is allowed only when APP_ENV is 'test' (it is '" + m.environment + "')")
	}
	return &Testing{sessions: m.sessions, field: m.field}, nil
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

// FieldTokenMinter signs field access tokens for tests: authtest.Provider.
type FieldTokenMinter interface {
	// FieldToken signs a token with the claims set over its defaults (nil removes a default).
	FieldToken(claims map[string]any) string
}

// FieldProofSigner is a test device: authtest.FieldDevice.
type FieldProofSigner interface {
	// JKT is the thumbprint of the device key, the token's cnf.jkt.
	JKT() string
	// ProofFor returns a valid DPoP proof for a request.
	ProofFor(method, url, nonce, token string) string
}

// FieldRequest returns the Authorization and DPoP headers of a field-worker request to a route under
// test (contract section 15.8): a token for f (zero fields get test defaults; the application is the
// module's), bound to the device's key, and a proof for method and path with a current application
// nonce. Field workers must be enabled with [FieldWorkers].
//
//	h, _ := testing.FieldRequest(provider, device, "POST", "/api/gate/open", auth.FieldPrincipal{})
func (t *Testing) FieldRequest(minter FieldTokenMinter, device FieldProofSigner, method, path string, f FieldPrincipal) (http.Header, error) {
	if t.field == nil {
		return nil, errors.New("auth.Testing.FieldRequest: field workers are not enabled (auth.FieldWorkers)")
	}
	orElse := func(v, d string) string {
		if v == "" {
			return d
		}
		return v
	}
	now := t.field.clock.Now()
	authTime := f.AuthTime
	if authTime.IsZero() {
		authTime = now
	}
	amr := f.AMR
	if len(amr) == 0 {
		amr = []string{"pin"}
	}
	token := minter.FieldToken(map[string]any{
		"iss": t.field.platform.issuer, "aud": orElse(f.AppCode, t.field.appCode),
		"sub": orElse(f.WorkerID, "fwu_TEST1"), "worker_code": orElse(f.WorkerCode, "W001"), "name": orElse(f.Name, "Test Worker"),
		"client_id": orElse(f.ClientID, "clt_TEST"), "client_identifier": orElse(f.ClientIdentifier, "test-client"),
		"device_id": orElse(f.DeviceID, "fwd_TEST1"), "device_mode": orElse(f.DeviceMode, "PERSONAL"),
		"sid": orElse(f.SessionID, "fws_TEST1"), "amr": amr, "auth_time": authTime.Unix(),
		"iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(10 * time.Minute).Unix(),
		"cnf": map[string]any{"jkt": device.JKT()},
	})
	target, _, _ := strings.Cut(path, "?")
	h := http.Header{}
	h.Set("Authorization", "DPoP "+token)
	h.Set("DPoP", device.ProofFor(method, t.field.baseURL+target, t.field.Nonce(), token))
	return h, nil
}

// FieldNonce returns a current application nonce, as the DPoP-Nonce header of a first 401 would.
func (t *Testing) FieldNonce() (string, error) {
	if t.field == nil {
		return "", errors.New("auth.Testing.FieldNonce: field workers are not enabled (auth.FieldWorkers)")
	}
	return t.field.Nonce(), nil
}
