// Package authtest is test support for the auth package (sessions-and-login contract section 9):
// [Provider], a real HTTP identity provider shaped like the FlowCatalyst platform that can be told
// to misbehave. It is a library package (not a _test file) so applications can use it in their own
// tests; the auth package's own test helper for authenticated requests is auth.Testing.
package authtest

import (
	"crypto"
	"crypto/hmac"
	"crypto/rand"
	"crypto/rsa"
	"crypto/sha256"
	"crypto/x509"
	"encoding/base64"
	"encoding/json/v2"
	"fmt"
	"maps"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// Signing is how the next id_tokens are signed.
type Signing int

const (
	// SigningNormal is RS256 with the published key.
	SigningNormal Signing = iota
	// SigningNone is alg none with an empty signature.
	SigningNone
	// SigningHS256WithPublicKey is HS256 with the public key's encoded bytes as the HMAC secret:
	// algorithm confusion.
	SigningHS256WithPublicKey
	// SigningOtherKey is RS256 with a key the provider does not publish, under the published kid.
	SigningOtherKey
	// SigningTamperedSignature is RS256, then one signature byte flipped.
	SigningTamperedSignature
	// SigningTamperedPayload is RS256, then the payload replaced by another one (the signature of
	// the original).
	SigningTamperedPayload
)

var b64 = base64.RawURLEncoding

type client struct{ id, secret, redirectURI string }

type grant struct{ clientID, redirectURI, challenge, nonce, scope string }

// Provider is a real OIDC provider for tests: discovery, JWKS, /oauth/authorize (signs the
// configured user in at once), /oauth/token (single-use codes, exact redirect_uri, PKCE S256,
// public and confidential clients) and /auth/oidc/session/end (which redirects to a post_logout_redirect_uri on the origin of the client's registered redirect URI).
type Provider struct {
	server *httptest.Server
	clock  fw.Clock

	mu               sync.Mutex
	clients          map[string]client
	codes            map[string]grant
	key              *rsa.PrivateKey
	keyID            string
	down             bool
	user             map[string]any
	idTokenChanges   map[string]any
	headerChanges    map[string]any
	discoveryChanges map[string]any
	signing          Signing
	authorizeError   string
	tokenStatus      int
	lastTokenForm    map[string]string
	lastTokenAuth    string

	keyFetches       atomic.Int32
	discoveryFetches atomic.Int32
	tokenRequests    atomic.Int32
	rotations        atomic.Int32
}

// Start starts a provider on a free local port. Share the application's clock so times line up.
func Start(clock fw.Clock) *Provider {
	p := &Provider{
		clock: clock, clients: map[string]client{}, codes: map[string]grant{},
		key: newKey(), keyID: "key-1", user: defaultUser(), tokenStatus: http.StatusOK,
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/.well-known/openid-configuration", p.discovery)
	mux.HandleFunc("/.well-known/jwks.json", p.jwks)
	mux.HandleFunc("/oauth/authorize", p.authorize)
	mux.HandleFunc("/oauth/token", p.token)
	mux.HandleFunc("/auth/oidc/session/end", p.sessionEnd)
	p.server = httptest.NewServer(mux)
	return p
}

// Issuer is the issuer, which is also the base URL: http://127.0.0.1:<port>.
func (p *Provider) Issuer() string { return p.server.URL }

// Close stops the provider.
func (p *Provider) Close() { p.server.Close() }

// Client registers a login client. secret is "" for a PUBLIC client; redirectURI is the only one
// accepted.
func (p *Provider) Client(clientID, secret, redirectURI string) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.clients[clientID] = client{id: clientID, secret: secret, redirectURI: redirectURI}
}

// SignInAs sets the claims of the user who signs in at /oauth/authorize, merged over sub
// prn_USER1, type USER, tier ANCHOR, clients ["*"] and the rest of the defaults.
func (p *Provider) SignInAs(claims map[string]any) {
	merged := defaultUser()
	maps.Copy(merged, claims)
	p.mu.Lock()
	p.user = merged
	p.mu.Unlock()
}

// IDTokenChanges sets claims of the next id_tokens, or removes one when the value is nil.
func (p *Provider) IDTokenChanges(changes map[string]any) {
	p.set(func() { p.idTokenChanges = copyMap(changes) })
}

// IDTokenHeaderChanges sets header members of the next id_tokens, or removes one when nil.
func (p *Provider) IDTokenHeaderChanges(changes map[string]any) {
	p.set(func() { p.headerChanges = copyMap(changes) })
}

// IDTokenSigning sets how the next id_tokens are signed.
func (p *Provider) IDTokenSigning(s Signing) { p.set(func() { p.signing = s }) }

// DiscoveryChanges sets members of the discovery document, or removes one when nil.
func (p *Provider) DiscoveryChanges(changes map[string]any) {
	p.set(func() { p.discoveryChanges = copyMap(changes) })
}

// AuthorizeError makes /oauth/authorize redirect with this error ("" for a normal sign-in).
func (p *Provider) AuthorizeError(e string) { p.set(func() { p.authorizeError = e }) }

// TokenStatus is the status /oauth/token answers with: 200 is normal, 4xx answers invalid_grant,
// 5xx server_error.
func (p *Provider) TokenStatus(status int) { p.set(func() { p.tokenStatus = status }) }

// RotateKey replaces the signing key with a new one under a new kid.
func (p *Provider) RotateKey() {
	k := newKey()
	n := p.rotations.Add(1)
	p.set(func() { p.key, p.keyID = k, fmt.Sprintf("key-r%d", n) })
}

// Down makes every endpoint answer 503, or work again.
func (p *Provider) Down(v bool) { p.set(func() { p.down = v }) }

// KeyFetches is how often the key set was read.
func (p *Provider) KeyFetches() int { return int(p.keyFetches.Load()) }

// DiscoveryFetches is how often discovery was read.
func (p *Provider) DiscoveryFetches() int { return int(p.discoveryFetches.Load()) }

// TokenRequests is how often the token endpoint was called.
func (p *Provider) TokenRequests() int { return int(p.tokenRequests.Load()) }

// LastTokenForm is the last token request's form.
func (p *Provider) LastTokenForm() map[string]string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return copyStrings(p.lastTokenForm)
}

// LastTokenAuthorization is the last token request's Authorization header, "" when none.
func (p *Provider) LastTokenAuthorization() string {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.lastTokenAuth
}

// AccessToken is a platform access token (token_use api, audience the issuer) valid for an hour,
// with the given claims set over the usual ones (nil removes one).
func (p *Provider) AccessToken(changes map[string]any) string {
	now := p.clock.Now()
	claims := map[string]any{
		"iss": p.Issuer(), "aud": p.Issuer(), "sub": "prn_SERVICE1", "type": "SERVICE", "tier": "ANCHOR",
		"token_use": "api", "scope": "", "clients": []any{"*"}, "azp": "svc-client",
		"iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(time.Hour).Unix(),
	}
	apply(claims, changes)
	return p.sign(p.header(), claims, SigningNormal)
}

// Sign signs any claims with the published key under its kid.
func (p *Provider) Sign(claims map[string]any) string {
	return p.sign(p.header(), claims, SigningNormal)
}

func (p *Provider) set(f func()) {
	p.mu.Lock()
	defer p.mu.Unlock()
	f()
}

// ---- endpoints ------------------------------------------------------------------------------

func (p *Provider) isDown() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.down
}

func (p *Provider) discovery(w http.ResponseWriter, _ *http.Request) {
	p.discoveryFetches.Add(1)
	base := p.Issuer()
	doc := map[string]any{
		"issuer": base, "authorization_endpoint": base + "/oauth/authorize", "token_endpoint": base + "/oauth/token",
		"jwks_uri": base + "/.well-known/jwks.json", "end_session_endpoint": base + "/auth/oidc/session/end",
		"response_types_supported": []any{"code"}, "code_challenge_methods_supported": []any{"S256"},
		"id_token_signing_alg_values_supported": []any{"RS256"},
	}
	p.mu.Lock()
	apply(doc, p.discoveryChanges)
	p.mu.Unlock()
	p.writeJSON(w, http.StatusOK, doc)
}

// sessionEnd ends the platform session. Like the platform it sends the browser on to
// post_logout_redirect_uri, but only for a registered client and an address on the origin of that
// client's redirect URI (the test double's stand-in for the registered post-logout URIs); anything
// else gets the plain answer.
func (p *Provider) sessionEnd(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	p.mu.Lock()
	c, known := p.clients[q.Get("client_id")]
	p.mu.Unlock()
	if back, err := url.Parse(q.Get("post_logout_redirect_uri")); known && !p.isDown() && err == nil && back.IsAbs() {
		if registered, err := url.Parse(c.redirectURI); err == nil && back.Scheme == registered.Scheme && back.Host == registered.Host {
			redirectTo(w, back.String())
			return
		}
	}
	p.writeJSON(w, http.StatusOK, map[string]any{"message": "Session ended"})
}

func (p *Provider) jwks(w http.ResponseWriter, _ *http.Request) {
	p.keyFetches.Add(1)
	p.mu.Lock()
	pub, kid := p.key.PublicKey, p.keyID
	p.mu.Unlock()
	p.writeJSON(w, http.StatusOK, map[string]any{"keys": []any{map[string]any{
		"kty": "RSA", "use": "sig", "alg": "RS256", "kid": kid,
		"n": b64.EncodeToString(pub.N.Bytes()), "e": b64.EncodeToString(bigE(pub.E)),
	}}})
}

func bigE(e int) []byte {
	var b []byte
	for ; e > 0; e >>= 8 {
		b = append([]byte{byte(e & 0xff)}, b...)
	}
	return b
}

func (p *Provider) authorize(w http.ResponseWriter, r *http.Request) {
	if p.isDown() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	q := r.URL.Query()
	p.mu.Lock()
	cl, known := p.clients[q.Get("client_id")]
	authErr := p.authorizeError
	p.mu.Unlock()
	redirect, state := q.Get("redirect_uri"), q.Get("state")
	if !known || cl.redirectURI != redirect || state == "" {
		p.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_request"})
		return
	}
	if authErr != "" {
		redirectTo(w, redirect+"?error="+url.QueryEscape(authErr)+"&error_description="+url.QueryEscape("no <script>")+"&state="+url.QueryEscape(state))
		return
	}
	if q.Get("response_type") != "code" || q.Get("code_challenge") == "" || q.Get("code_challenge_method") != "S256" {
		redirectTo(w, redirect+"?error=invalid_request&state="+url.QueryEscape(state))
		return
	}
	code := fmt.Sprintf("code-%d-%s", p.clock.Now().UnixNano(), randomText(12))
	p.mu.Lock()
	p.codes[code] = grant{clientID: cl.id, redirectURI: redirect, challenge: q.Get("code_challenge"), nonce: q.Get("nonce"), scope: q.Get("scope")}
	p.mu.Unlock()
	redirectTo(w, redirect+"?code="+url.QueryEscape(code)+"&state="+url.QueryEscape(state))
}

func (p *Provider) token(w http.ResponseWriter, r *http.Request) {
	p.tokenRequests.Add(1)
	if p.isDown() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	if err := r.ParseForm(); err != nil {
		p.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_request"})
		return
	}
	form := map[string]string{}
	for k := range r.PostForm {
		form[k] = r.PostForm.Get(k)
	}
	auth := r.Header.Get("Authorization")
	p.mu.Lock()
	p.lastTokenForm, p.lastTokenAuth = form, auth
	status := p.tokenStatus
	p.mu.Unlock()
	if status >= 500 {
		p.writeJSON(w, status, map[string]any{"error": "server_error"})
		return
	}
	if status >= 400 {
		p.writeJSON(w, status, map[string]any{"error": "invalid_grant", "error_description": "nope"})
		return
	}
	clientID := form["client_id"]
	presented, hasSecret := form["client_secret"]
	if rest, ok := strings.CutPrefix(auth, "Basic "); ok {
		raw, err := base64.StdEncoding.DecodeString(rest)
		id, secret, found := strings.Cut(string(raw), ":")
		if err != nil || !found {
			p.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_request"})
			return
		}
		basicID, _ := url.QueryUnescape(id)
		if clientID != "" && clientID != basicID {
			p.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_request"})
			return
		}
		clientID = basicID
		presented, _ = url.QueryUnescape(secret)
		hasSecret = true
	}
	p.mu.Lock()
	cl, known := p.clients[clientID]
	g, hasGrant := p.codes[form["code"]]
	delete(p.codes, form["code"])
	user := copyMap(p.user)
	idChanges, hdrChanges, signing := copyMap(p.idTokenChanges), copyMap(p.headerChanges), p.signing
	p.mu.Unlock()
	if !known || (cl.secret == "" && hasSecret) || (cl.secret != "" && cl.secret != presented) {
		p.writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "invalid_client"})
		return
	}
	if form["grant_type"] != "authorization_code" || !hasGrant || g.clientID != clientID ||
		g.redirectURI != form["redirect_uri"] || challengeOf(form["code_verifier"]) != g.challenge {
		p.writeJSON(w, http.StatusBadRequest, map[string]any{"error": "invalid_grant"})
		return
	}
	now := p.clock.Now()
	claims := map[string]any{
		"iss": p.Issuer(), "aud": clientID, "azp": clientID, "exp": now.Add(5 * time.Minute).Unix(),
		"iat": now.Unix(), "auth_time": now.Unix(),
	}
	if g.nonce != "" {
		claims["nonce"] = g.nonce
	}
	maps.Copy(claims, user)
	apply(claims, idChanges)
	header := p.header()
	apply(header, hdrChanges)
	idToken := p.sign(header, claims, signing)
	access := copyMap(user)
	access["iss"], access["aud"], access["token_use"], access["exp"] = p.Issuer(), p.Issuer(), "identity", now.Add(time.Hour).Unix()
	p.writeJSON(w, http.StatusOK, map[string]any{
		"access_token": p.sign(p.header(), access, SigningNormal), "token_type": "Bearer", "expires_in": 3600,
		"scope": g.scope, "id_token": idToken,
	})
}

// ---- signing --------------------------------------------------------------------------------

func (p *Provider) header() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	return map[string]any{"alg": "RS256", "typ": "JWT", "kid": p.keyID}
}

func (p *Provider) sign(header, claims map[string]any, how Signing) string {
	p.mu.Lock()
	key := p.key
	p.mu.Unlock()
	switch how {
	case SigningNone:
		h := copyMap(header)
		h["alg"] = "none"
		return part(h) + "." + part(claims) + "."
	case SigningHS256WithPublicKey:
		h := copyMap(header)
		h["alg"] = "HS256"
		signed := part(h) + "." + part(claims)
		pub, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
		if err != nil {
			panic(err)
		}
		mac := hmac.New(sha256.New, pub)
		mac.Write([]byte(signed))
		return signed + "." + b64.EncodeToString(mac.Sum(nil))
	case SigningOtherKey:
		return rs256(header, claims, newKey())
	case SigningTamperedSignature:
		parts := strings.Split(rs256(header, claims, key), ".")
		sig, err := b64.DecodeString(parts[2])
		if err != nil || len(sig) == 0 {
			panic("authtest: cannot tamper with an empty signature")
		}
		sig[len(sig)/2] ^= 1
		return parts[0] + "." + parts[1] + "." + b64.EncodeToString(sig)
	case SigningTamperedPayload:
		parts := strings.Split(rs256(header, claims, key), ".")
		changed := copyMap(claims)
		changed["roles"] = []any{"platform:admin"}
		return parts[0] + "." + part(changed) + "." + parts[2]
	case SigningNormal:
	}
	return rs256(header, claims, key)
}

func rs256(header, claims map[string]any, key *rsa.PrivateKey) string {
	signed := part(header) + "." + part(claims)
	sum := sha256.Sum256([]byte(signed))
	sig, err := rsa.SignPKCS1v15(rand.Reader, key, crypto.SHA256, sum[:])
	if err != nil {
		panic(err)
	}
	return signed + "." + b64.EncodeToString(sig)
}

func part(m map[string]any) string {
	b, err := json.Marshal(m)
	if err != nil {
		panic(err)
	}
	return b64.EncodeToString(b)
}

// ---- helpers --------------------------------------------------------------------------------

func defaultUser() map[string]any {
	return map[string]any{
		"sub": "prn_USER1", "type": "USER", "tier": "ANCHOR", "name": "Ada Lovelace", "email": "ada@example.test",
		"clients": []any{"*"}, "applications": []any{"*"}, "roles": []any{},
	}
}

func apply(target, changes map[string]any) {
	for k, v := range changes {
		if v == nil {
			delete(target, k)
		} else {
			target[k] = v
		}
	}
}

func copyMap(m map[string]any) map[string]any {
	out := make(map[string]any, len(m))
	maps.Copy(out, m)
	return out
}

func copyStrings(m map[string]string) map[string]string {
	out := make(map[string]string, len(m))
	maps.Copy(out, m)
	return out
}

func challengeOf(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return b64.EncodeToString(sum[:])
}

func randomText(n int) string {
	b := make([]byte, n)
	if _, err := rand.Read(b); err != nil {
		panic(err)
	}
	return b64.EncodeToString(b)
}

func newKey() *rsa.PrivateKey {
	k, err := rsa.GenerateKey(rand.Reader, 2048)
	if err != nil {
		panic(err)
	}
	return k
}

func (p *Provider) writeJSON(w http.ResponseWriter, status int, body map[string]any) {
	if p.isDown() {
		w.WriteHeader(http.StatusServiceUnavailable)
		return
	}
	b, err := json.Marshal(body)
	if err != nil {
		panic(err)
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_, _ = w.Write(b)
}

// redirectTo answers 302 with a Location (the test provider redirects to the registered URI only).
func redirectTo(w http.ResponseWriter, location string) {
	w.Header().Set("Location", location)
	w.WriteHeader(http.StatusFound)
}
