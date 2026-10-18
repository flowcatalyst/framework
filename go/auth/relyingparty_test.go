package auth

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/fwtest"
)

const (
	testClientID = "app-client"
	testRedirect = "http://127.0.0.1:9/auth/callback"
)

var start = time.Date(2026, 10, 13, 8, 0, 0, 0, time.UTC)

// rpFixture is a relying party against a real test provider.
type rpFixture struct {
	t        *testing.T
	clock    *fwtest.Clock
	provider *authtest.Provider
	rp       *relyingParty
}

func newRP(t *testing.T, secret string) *rpFixture {
	t.Helper()
	clock := fwtest.NewClock(start)
	provider := authtest.Start(clock)
	t.Cleanup(provider.Close)
	provider.Client(testClientID, secret, testRedirect)
	p, err := newPlatform(provider.Issuer(), 5*time.Second, clock, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	return &rpFixture{t: t, clock: clock, provider: provider, rp: &relyingParty{
		platform: p, clientID: testClientID, clientSecret: secret, redirectURI: testRedirect, scope: "openid profile email", clock: clock,
	}}
}

// login runs authorize -> code -> exchange and returns the id_token and the nonce used.
func (f *rpFixture) login() (idToken, nonce string, ex exchange) {
	f.t.Helper()
	ctx := context.Background()
	l := loginData{state: random(32), nonce: random(32), codeVerifier: random(32), returnTo: "/", createdAt: f.clock.Now()}
	loc, err := f.rp.authorizeURL(ctx, l)
	if err != nil {
		f.t.Fatalf("authorizeURL: %v", err)
	}
	noRedirect := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := noRedirect.Get(loc)
	if err != nil || resp == nil {
		f.t.Fatal(err)
	}
	resp.Body.Close()
	back, err := url.Parse(resp.Header.Get("Location"))
	if err != nil || back == nil || back.Query().Get("code") == "" {
		f.t.Fatalf("authorize answered %d %q", resp.StatusCode, resp.Header.Get("Location"))
	}
	if back.Query().Get("state") != l.state {
		f.t.Fatalf("state not echoed")
	}
	ex, err = f.rp.exchange(ctx, back.Query().Get("code"), l.codeVerifier)
	if err != nil {
		f.t.Fatalf("exchange: %v", err)
	}
	return ex.idToken, l.nonce, ex
}

func (f *rpFixture) refused(reason string) {
	f.t.Helper()
	idToken, nonce, ex := f.login()
	if ex.idToken == "" {
		f.t.Fatalf("%s: the exchange was refused: %s", reason, ex.refused)
	}
	v, err := f.rp.verify(context.Background(), idToken, nonce)
	if err != nil {
		f.t.Fatalf("%s: %v", reason, err)
	}
	if v.reason != reason {
		f.t.Fatalf("reason = %q, want %q", v.reason, reason)
	}
}

func (f *rpFixture) accepted() verified {
	f.t.Helper()
	idToken, nonce, _ := f.login()
	v, err := f.rp.verify(context.Background(), idToken, nonce)
	if err != nil || v.reason != "" {
		f.t.Fatalf("expected a valid id_token: %v %q", err, v.reason)
	}
	return v
}

func TestAuthorizeURLHasTheContractParametersInOrder(t *testing.T) {
	f := newRP(t, "")
	f.rp.theme = "acme"
	l := loginData{state: "S", nonce: "N", codeVerifier: random(32)}
	loc, err := f.rp.authorizeURL(context.Background(), l)
	if err != nil {
		t.Fatal(err)
	}
	u, err := url.Parse(loc)
	if err != nil || u == nil {
		t.Fatal(err)
	}
	want := "response_type=code&client_id=app-client&redirect_uri=" + url.QueryEscape(testRedirect) +
		"&scope=openid+profile+email&state=S&nonce=N&code_challenge=" + challenge(l.codeVerifier) +
		"&code_challenge_method=S256&client=acme"
	if u.RawQuery != want {
		t.Fatalf("query\n got %s\nwant %s", u.RawQuery, want)
	}
	if len(l.codeVerifier) != 43 || len(random(32)) != 43 {
		t.Fatal("verifier and handles are 43 characters")
	}
}

func TestPKCEChallengeKnownVector(t *testing.T) {
	// RFC 7636 appendix B
	if got := challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"); got != "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM" {
		t.Fatalf("challenge = %s", got)
	}
}

func TestIDTokenHappyPathPublicAndConfidential(t *testing.T) {
	for _, secret := range []string{"", "s3cret"} {
		f := newRP(t, secret)
		v := f.accepted()
		if v.principal.ID != "prn_USER1" || v.principal.Name != "Ada Lovelace" || v.principal.Tier != "ANCHOR" {
			t.Fatalf("principal %+v", v.principal)
		}
		if !v.authTime.Equal(start) {
			t.Fatalf("authTime %v", v.authTime)
		}
		form := f.provider.LastTokenForm()
		if _, has := form["client_secret"]; has {
			t.Fatal("a secret must never be in the form body")
		}
		if secret == "" && f.provider.LastTokenAuthorization() != "" {
			t.Fatal("a PUBLIC client sends no secret at all")
		}
		if secret != "" && !strings.HasPrefix(f.provider.LastTokenAuthorization(), "Basic ") {
			t.Fatal("a CONFIDENTIAL client uses HTTP Basic")
		}
		if form["code_verifier"] == "" || form["redirect_uri"] != testRedirect || form["grant_type"] != "authorization_code" {
			t.Fatalf("form %v", form)
		}
	}
}

func TestIDTokenRefusals(t *testing.T) {
	now := start.Unix()
	cases := []struct {
		name   string
		setup  func(p *authtest.Provider)
		reason string
	}{
		{"alg none", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningNone) }, "alg"},
		{"HS256 with the public key", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningHS256WithPublicKey) }, "alg"},
		{"RS512 header", func(p *authtest.Provider) { p.IDTokenHeaderChanges(map[string]any{"alg": "RS512"}) }, "alg"},
		{"lower-case rs256", func(p *authtest.Provider) { p.IDTokenHeaderChanges(map[string]any{"alg": "rs256"}) }, "alg"},
		{"no alg", func(p *authtest.Provider) { p.IDTokenHeaderChanges(map[string]any{"alg": nil}) }, "alg"},
		{"crit", func(p *authtest.Provider) { p.IDTokenHeaderChanges(map[string]any{"crit": []any{"exp"}}) }, "crit"},
		{"no kid", func(p *authtest.Provider) { p.IDTokenHeaderChanges(map[string]any{"kid": nil}) }, "kid"},
		{"unknown kid", func(p *authtest.Provider) { p.IDTokenHeaderChanges(map[string]any{"kid": "not-published"}) }, "unknown_kid"},
		{"jku and x5u are never fetched", func(p *authtest.Provider) {
			p.IDTokenHeaderChanges(map[string]any{"kid": "attacker", "jku": "http://127.0.0.1:1/jwks", "x5u": "http://127.0.0.1:1/x"})
		}, "unknown_kid"},
		{"other key", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningOtherKey) }, "signature"},
		{"tampered signature", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningTamperedSignature) }, "signature"},
		{"tampered payload", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningTamperedPayload) }, "signature"},
		{"other issuer", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"iss": "https://evil.example"}) }, "iss"},
		{"issuer with a trailing slash", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"iss": p.Issuer() + "/"}) }, "iss"},
		{"other audience", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"aud": "other-client"}) }, "aud"},
		{"the issuer as audience", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"aud": p.Issuer()}) }, "aud"},
		{"numeric audience", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"aud": 42}) }, "aud"},
		{"two audiences and no azp", func(p *authtest.Provider) {
			p.IDTokenChanges(map[string]any{"aud": []any{testClientID, "other"}, "azp": nil})
		}, "azp"},
		{"azp is not us", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"azp": "other"}) }, "azp"},
		{"expired", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"exp": now - 61}) }, "expired"},
		{"no exp", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"exp": nil}) }, "expired"},
		{"exp is text", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"exp": "tomorrow"}) }, "expired"},
		{"nbf in the future", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"nbf": now + 61}) }, "not_yet_valid"},
		{"iat in the future", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"iat": now + 61}) }, "iat"},
		{"wrong nonce", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"nonce": random(32)}) }, "nonce"},
		{"no nonce", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"nonce": nil}) }, "nonce"},
		{"empty sub", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"sub": ""}) }, "sub"},
		{"an access token is never an id_token", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"token_use": "api"}) }, "token_use"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newRP(t, "")
			c.setup(f.provider)
			f.refused(c.reason)
		})
	}
}

func TestIDTokenAllowanceIsSixtySecondsExactly(t *testing.T) {
	f := newRP(t, "")
	now := start.Unix()
	f.provider.IDTokenChanges(map[string]any{"exp": now - 59, "nbf": now + 60, "iat": now + 60})
	f.accepted()
	f.provider.IDTokenChanges(map[string]any{"exp": now - 60})
	f.refused("expired")
}

func TestIDTokenClaimsBecomeThePrincipal(t *testing.T) {
	f := newRP(t, "")
	f.provider.SignInAs(map[string]any{
		"sub": "prn_ADA", "tier": "CLIENT", "client_id": "clt_1", "clients": []any{"clt_1:acme"},
		"applications": []any{"app_1:orders"}, "roles": []any{"orders:clerk", 7}, "type": "SERVICE",
	})
	v := f.accepted()
	p := v.principal
	if p.ID != "prn_ADA" || p.Tier != "CLIENT" || p.HomeClient != "clt_1" || p.Kind.String() != "SERVICE" {
		t.Fatalf("%+v", p)
	}
	if len(p.Roles) != 1 || p.Roles[0] != "orders:clerk" {
		t.Fatalf("a role that is not text is dropped: %v", p.Roles)
	}
	if id, ok := p.HomeClientIdentifier(); !ok || id != "acme" {
		t.Fatalf("home client identifier %q %v", id, ok)
	}
	if len(p.Scopes) != 0 {
		t.Fatal("an id_token principal carries no scopes")
	}
}

func TestTokenEndpointAnswers(t *testing.T) {
	f := newRP(t, "")
	f.provider.TokenStatus(400)
	_, _, ex := f.login()
	if ex.refused != "invalid_grant" || ex.idToken != "" {
		t.Fatalf("a 4xx is a refusal with the platform's error: %+v", ex)
	}
	f.provider.TokenStatus(503)
	l := loginData{state: "S", nonce: "N", codeVerifier: random(32)}
	if _, err := f.rp.exchange(context.Background(), "code", l.codeVerifier); err == nil {
		t.Fatal("a 5xx is the platform being unavailable")
	}
}

func TestSigningKeyRotation(t *testing.T) {
	f := newRP(t, "")
	f.accepted()
	f.provider.RotateKey()
	f.clock.Advance(2 * time.Minute)
	f.accepted()
}

func TestEndSessionURL(t *testing.T) {
	f := newRP(t, "")
	ctx := context.Background()
	got := f.rp.endSessionURL(ctx, "https://app.example/signed-out")
	want := f.provider.Issuer() + "/auth/oidc/session/end?client_id=app-client&post_logout_redirect_uri=" +
		url.QueryEscape("https://app.example/signed-out")
	if got != want {
		t.Fatalf("\n got %s\nwant %s", got, want)
	}
	if f.rp.endSessionURL(ctx, "") != f.provider.Issuer()+"/auth/oidc/session/end?client_id=app-client" {
		t.Fatal("without a post-logout redirect only client_id is sent")
	}
	f2 := newRP(t, "")
	f2.provider.DiscoveryChanges(map[string]any{"end_session_endpoint": nil})
	if f2.rp.endSessionURL(ctx, "") != "" {
		t.Fatal("no end_session_endpoint: nothing")
	}
}

func TestDiscoveryIsRefusedWhenTampered(t *testing.T) {
	cases := []struct {
		name    string
		changes func(p *authtest.Provider) map[string]any
	}{
		{"issuer mismatch", func(*authtest.Provider) map[string]any { return map[string]any{"issuer": "https://evil.example"} }},
		{"token endpoint off origin", func(*authtest.Provider) map[string]any {
			return map[string]any{"token_endpoint": "http://127.0.0.1:1/oauth/token"}
		}},
		{"jwks off origin", func(*authtest.Provider) map[string]any {
			return map[string]any{"jwks_uri": "https://evil.example/keys"}
		}},
		{"no authorization endpoint", func(*authtest.Provider) map[string]any { return map[string]any{"authorization_endpoint": nil} }},
		{"authorization endpoint not absolute", func(*authtest.Provider) map[string]any {
			return map[string]any{"authorization_endpoint": "/oauth/authorize"}
		}},
		{"no S256", func(*authtest.Provider) map[string]any {
			return map[string]any{"code_challenge_methods_supported": []any{"plain"}}
		}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			f := newRP(t, "")
			f.provider.DiscoveryChanges(c.changes(f.provider))
			_, err := f.rp.authorizeURL(context.Background(), loginData{state: "S", nonce: "N", codeVerifier: random(32)})
			if err == nil {
				t.Fatal("a refused discovery document must make sign-in unavailable")
			}
			// a failed read is not kept: the next login tries again
			f.provider.DiscoveryChanges(map[string]any{})
			if _, err := f.rp.authorizeURL(context.Background(), loginData{state: "S", nonce: "N", codeVerifier: random(32)}); err != nil {
				t.Fatalf("a failed read must not be kept: %v", err)
			}
		})
	}
}

func TestDiscoveryAcceptsATrailingSlashIssuer(t *testing.T) {
	f := newRP(t, "")
	p, err := newPlatform(f.provider.Issuer()+"/", time.Second, f.clock, nil, true)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := p.discoveryDoc(context.Background()); err != nil {
		t.Fatal(err)
	}
}
