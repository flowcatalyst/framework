package httpclient

import (
	"context"
	"errors"
	"fmt"
	"net/url"
	"strings"
)

// Credential gives a secret value when asked, on every attempt, so a rotated value is picked up
// and nothing is kept in a string field that might be printed. An error is reduced to its type
// name in the request's error (it can quote the secret).
type Credential func(ctx context.Context) (string, error)

// Static is a Credential that always gives s.
func Static(s string) Credential { return func(context.Context) (string, error) { return s, nil } }

// Auth is how a service authenticates, applied to every attempt (http-client contract section 4).
// Build one with NoAuth, Bearer, BearerRefreshing, Basic, APIKey or OAuthClientCredentials.
//
//sumtype:decl
type Auth interface{ auth() }

type (
	noAuth     struct{}
	bearerAuth struct {
		token          Credential
		onUnauthorized func()
	}
	basicAuth struct {
		user     string
		password Credential
	}
	headerAuth struct {
		name  string
		value Credential
	}
	oauthAuth struct{ cc ClientCredentials }
)

func (noAuth) auth()     {}
func (bearerAuth) auth() {}
func (basicAuth) auth()  {}
func (headerAuth) auth() {}
func (oauthAuth) auth()  {}

// NoAuth sends nothing.
func NoAuth() Auth { return noAuth{} }

// Bearer sends "Authorization: Bearer <token>", the credential asked on every attempt (for example
// the platform client's access token).
func Bearer(token Credential) Auth { return bearerAuth{token: token} }

// BearerRefreshing is Bearer; on a 401 onUnauthorized runs (clear a cached token) and the request
// is sent once more, outside the retry count.
func BearerRefreshing(token Credential, onUnauthorized func()) Auth {
	return bearerAuth{token: token, onUnauthorized: onUnauthorized}
}

// Basic sends "Authorization: Basic ...". The user cannot contain ':'.
func Basic(user string, password Credential) (Auth, error) {
	if strings.Contains(user, ":") {
		return nil, errors.New("a basic-auth user cannot contain ':'")
	}
	return basicAuth{user: user, password: password}, nil
}

// APIKey sends an API-key header with the credential as its value.
func APIKey(name string, value Credential) (Auth, error) {
	if err := checkHeaderName(name); err != nil {
		return nil, err
	}
	return headerAuth{name: name, value: value}, nil
}

// OAuthClientCredentials authenticates with an OAuth 2 client-credentials grant: the token is
// fetched with the same engine, cached until expires_in minus a skew of min(30s, expires_in/10)
// (5 minutes without expires_in), refreshed by one request for all concurrent callers, and dropped
// after a 401, which sends the request once more with a new token for every method.
func OAuthClientCredentials(cc ClientCredentials) Auth { return oauthAuth{cc: cc} }

// GrantMethod is how the client id and secret travel to the token endpoint.
type GrantMethod int

const (
	// GrantPost sends client_id and client_secret in the form body (the FlowCatalyst platform's
	// way; the default).
	GrantPost GrantMethod = iota
	// GrantBasic sends them in an Authorization: Basic header.
	GrantBasic
)

// ClientCredentials is an OAuth 2 client-credentials grant. Build it with
// [NewClientCredentials].
type ClientCredentials struct {
	tokenURL *url.URL
	clientID string
	secret   Credential
	scope    string
	audience string
	method   GrantMethod
}

// ClientCredentialsOption configures [NewClientCredentials].
type ClientCredentialsOption func(*ClientCredentials)

// Scope sets the scope.
func Scope(s string) ClientCredentialsOption { return func(c *ClientCredentials) { c.scope = s } }

// Audience sets the audience.
func Audience(a string) ClientCredentialsOption { return func(c *ClientCredentials) { c.audience = a } }

// Method sets how the client authenticates to the token endpoint.
func Method(m GrantMethod) ClientCredentialsOption {
	return func(c *ClientCredentials) { c.method = m }
}

// NewClientCredentials checks the token URL (https, or http to localhost, with a host and no user
// info) and the client id (not blank).
func NewClientCredentials(tokenURL *url.URL, clientID string, secret Credential, opts ...ClientCredentialsOption) (ClientCredentials, error) {
	c := ClientCredentials{tokenURL: tokenURL, clientID: clientID, secret: secret}
	for _, o := range opts {
		o(&c)
	}
	secure := tokenURL != nil && (tokenURL.Scheme == "https" || tokenURL.Scheme == "http" && isLoopback(tokenURL.Hostname()))
	if !secure || tokenURL.Hostname() == "" || tokenURL.User != nil {
		return ClientCredentials{}, errors.New("the token URL must be https (or http to localhost), with a host and no user info")
	}
	if strings.TrimSpace(clientID) == "" {
		return ClientCredentials{}, errors.New("clientId must not be blank")
	}
	if secret == nil {
		return ClientCredentials{}, errors.New("a client secret credential is required")
	}
	if c.method != GrantPost && c.method != GrantBasic {
		return ClientCredentials{}, fmt.Errorf("unknown grant method %d", c.method)
	}
	return c, nil
}

func isLoopback(host string) bool {
	switch strings.ToLower(host) {
	case "localhost", "127.0.0.1", "::1":
		return true
	}
	return false
}

// String never shows the secret.
func (c ClientCredentials) String() string {
	return "ClientCredentials[" + c.tokenURL.Hostname() + ", " + c.clientID + ", ****]"
}
