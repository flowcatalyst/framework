// Package auth is sessions and login for an application that is its own backend-for-frontend
// (sessions-and-login contract, docs/contracts/sessions-and-login.md): an OIDC relying party of the
// FlowCatalyst platform (authorization code flow, PKCE S256, state, nonce, RS256-only id_token
// checks, a JWKS cache), the endpoints /auth/login, /auth/callback, /auth/session and
// /auth/logout, server-side sessions in a [Store] (memory and cookie here; authdb and authredis
// in sub-packages), the platform's F16 CSRF rules and login-state binding, 401 versus 302, client
// selection and permission mapping in code, platform bearer tokens ([PlatformTokens], usable on
// its own), and test support ([Testing] here, authtest.Provider for a test identity provider).
//
// The tokens never reach the browser and the session keeps none (contract section 6.4). Behaviour
// is code (options on [Build]); only the connection and secret settings are configuration
// (auth.issuer, auth.client-id, auth.client-secret, auth.public-base-url, auth.cookie-keys).
package auth
