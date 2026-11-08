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

// AuthModule authenticates with the auth module (sessions-and-login contract): a bearer token, a
// field token with its DPoP proof, or the session cookie with its CSRF rule. A request with none of
// them is unauthenticated, not refused.
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
