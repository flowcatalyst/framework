package push

import (
	"net/http"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// Credentials are who a subscribe request authenticated as.
type Credentials struct {
	// Context is the request's Context, as the application's resolver makes it.
	Context fw.Context
	// ExpiresAt is when the credential (session, access token) stops being valid; zero when it is
	// not known. The stream token expires no later.
	ExpiresAt time.Time
	// ProofKey is the thumbprint of the DPoP key the request proved it holds (a field token's
	// cnf.jkt), "" when none: a ticket bound to a key (cnf) needs it.
	ProofKey string
	// Device and Session are the field device and login family, for revocation; "" otherwise.
	Device, Session string
}

// Authenticator authenticates a subscribe request the application's usual way (push contract
// 6.1). present is false when the request carries no credentials (it may still subscribe with
// tickets); an error is answered as it is (401, 403 for a CSRF refusal, 503).
type Authenticator interface {
	Authenticate(r *http.Request) (creds Credentials, present bool, err error)
}

// AuthenticatorFunc adapts a function to [Authenticator].
type AuthenticatorFunc func(r *http.Request) (Credentials, bool, error)

// Authenticate calls f(r).
func (f AuthenticatorFunc) Authenticate(r *http.Request) (Credentials, bool, error) { return f(r) }

// ClientAuthenticator is an [Authenticator] that also takes the client the subscriber asked for
// (the subscribe body's optional client member, push contract 17.16). A host whose credentials can
// act for several clients implements it: client is "" when the body names none, and the
// authenticator then answers 400 VALIDATION (httpfw.Validation) for a credential with several
// clients, or uses the only one; when it names one, the Context it returns is for that client, or
// the answer is 403 CLIENT_FORBIDDEN (httpfw.Forbidden) for a credential that cannot use it. The
// module checks once more that the Context's client is the one named. When the authenticator
// implements this interface, the module calls AuthenticateClient instead of Authenticate.
type ClientAuthenticator interface {
	Authenticator
	AuthenticateClient(r *http.Request, client string) (creds Credentials, present bool, err error)
}

// ClientAuthenticatorFunc adapts a function to [ClientAuthenticator]; used as an [Authenticator] it
// is called with no client named.
type ClientAuthenticatorFunc func(r *http.Request, client string) (Credentials, bool, error)

// Authenticate calls f(r, "").
func (f ClientAuthenticatorFunc) Authenticate(r *http.Request) (Credentials, bool, error) {
	return f(r, "")
}

// AuthenticateClient calls f(r, client).
func (f ClientAuthenticatorFunc) AuthenticateClient(r *http.Request, client string) (Credentials, bool, error) {
	return f(r, client)
}

// AuthModule authenticates with the auth module (sessions-and-login contract): a bearer token, a
// field token with its DPoP proof, or the session cookie with its CSRF rule. A request with none of
// them is unauthenticated, not refused. Its credentials have one client, so a client named in the
// subscribe body must be it (403 CLIENT_FORBIDDEN otherwise).
func AuthModule(m *auth.Module) Authenticator {
	return AuthenticatorFunc(func(r *http.Request) (Credentials, bool, error) {
		if !m.CarriesCredentials(r) {
			return Credentials{}, false, nil
		}
		cx, err := m.Resolver().Resolve(r)
		if err != nil {
			return Credentials{}, true, err
		}
		if c := httpfw.CorrelationID(r); !c.IsZero() {
			cx = cx.WithCorrelation(c)
		}
		creds := Credentials{Context: cx}
		if a, ok := auth.Current(r); ok {
			creds.ExpiresAt = a.ExpiresAt
			if a.Field != nil {
				creds.ProofKey, creds.Device, creds.Session = a.Field.KeyThumbprint, a.Field.DeviceID, a.Field.SessionID
			}
		}
		return creds, true, nil
	})
}
