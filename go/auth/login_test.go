package auth_test

import (
	"context"
	"encoding/base64"
	"errors"
	"maps"
	"net/url"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
)

func TestCallbackInAnotherBrowserIsRefusedAndTheRightBrowserCanFinish(t *testing.T) {
	a := mustStart(t, config{})
	alice := a.browser()
	callback := alice.startSignIn("/")
	mallory := a.browser()
	r := mallory.get(mallory.local(callback))
	if r.status != 400 || r.str("error") != "LOGIN_NOT_STARTED_HERE" ||
		r.str("message") != "This sign-in was not started in this browser. Start the sign-in again." {
		t.Fatalf("%d %s", r.status, r.body)
	}
	if len(mallory.jar) != 0 {
		t.Fatal("nothing is set for the wrong browser")
	}
	if ok := alice.get(alice.local(callback)); ok.status != 302 || alice.jar["__Host-fw_session"] == "" {
		t.Fatalf("the browser that started it can finish: %d", ok.status)
	}
}

func TestChangedStateAndBindingFromAnotherLogin(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	cb := b.startSignIn("/")
	other := strings.Repeat("a", 43)
	r := b.get("/auth/callback?code=" + query(t, cb)["code"] + "&state=" + other)
	if r.status != 400 || r.str("error") != "LOGIN_NOT_STARTED_HERE" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	// the first login's record under the second state's cookie name
	b2 := a.browser()
	first := b2.startSignIn("/")
	firstState := query(t, first)["state"]
	var firstName, firstHandle string
	for n, v := range b2.jar {
		firstName, firstHandle = n, v
	}
	_ = firstState
	second := b2.startSignIn("/")
	for n := range b2.jar {
		if n != firstName {
			b2.jar[n] = firstHandle
		}
	}
	r = b2.get(b2.local(second))
	if r.status != 400 || r.str("error") != "INVALID_STATE" || b2.jar["__Host-fw_session"] != "" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

func TestReplayedCallbackIsRefused(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	cb := b.startSignIn("/")
	before := maps.Clone(b.jar)
	if b.get(b.local(cb)).status != 302 {
		t.Fatal("first callback")
	}
	replay := a.browser()
	maps.Copy(replay.jar, before)
	r := replay.get(replay.local(cb))
	if r.status != 400 || r.str("error") != "INVALID_STATE" || replay.jar["__Host-fw_session"] != "" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

func TestMissingOrMalformedStateOrCode(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	for _, u := range []string{"/auth/callback?code=x", "/auth/callback?code=x&state=short"} {
		if r := b.get(u); r.str("error") != "INVALID_CALLBACK" {
			t.Fatalf("%s: %s", u, r.body)
		}
	}
	cb := b.startSignIn("/")
	state := query(t, cb)["state"]
	r := b.get("/auth/callback?state=" + state)
	if r.status != 400 || r.str("error") != "INVALID_CALLBACK" {
		t.Fatal(r.body)
	}
	if len(b.jar) != 0 {
		t.Fatalf("the binding cookie is cleared: %v", b.jar)
	}
}

func TestPlatformErrorIsLoginRefusedWithoutEchoingTheDescription(t *testing.T) {
	a := mustStart(t, config{})
	a.provider.AuthorizeError("access_denied")
	b := a.browser()
	r := b.get(b.local(b.startSignIn("/")))
	if r.status != 400 || r.str("error") != "LOGIN_REFUSED" || r.str("details", "error") != "access_denied" || strings.Contains(r.body, "script") {
		t.Fatalf("%d %s", r.status, r.body)
	}
	if len(b.jar) != 0 {
		t.Fatal(b.jar)
	}
}

func TestRefusedCodeAndPlatformFailure(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	a.provider.TokenStatus(400)
	r := b.get(b.local(b.startSignIn("/")))
	if r.status != 400 || r.str("error") != "LOGIN_FAILED" || r.str("details", "error") != "invalid_grant" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	a.provider.TokenStatus(500)
	r = b.get(b.local(b.startSignIn("/")))
	if r.status != 502 || r.str("error") != "PLATFORM_UNAVAILABLE" || b.jar["__Host-fw_session"] != "" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

func TestIDTokenRefusalsThroughTheCallback(t *testing.T) {
	cases := []struct {
		name   string
		setup  func(p *authtest.Provider)
		reason string
	}{
		{"alg none", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningNone) }, "alg"},
		{"hs256 confusion", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningHS256WithPublicKey) }, "alg"},
		{"tampered payload", func(p *authtest.Provider) { p.IDTokenSigning(authtest.SigningTamperedPayload) }, "signature"},
		{"wrong audience", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"aud": "other"}) }, "aud"},
		{"wrong nonce", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"nonce": "zzz"}) }, "nonce"},
		{"access token as id token", func(p *authtest.Provider) { p.IDTokenChanges(map[string]any{"token_use": "api"}) }, "token_use"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			a := mustStart(t, config{})
			c.setup(a.provider)
			b := a.browser()
			r := b.get(b.local(b.startSignIn("/")))
			if r.status != 400 || r.str("error") != "INVALID_ID_TOKEN" || r.str("details", "reason") != c.reason || strings.Contains(r.body, "eyJ") {
				t.Fatalf("%d %s", r.status, r.body)
			}
			if b.jar["__Host-fw_session"] != "" || b.get("/api/me").status != 401 {
				t.Fatal("no session may be created")
			}
		})
	}
}

func TestLoginWhenDiscoveryIsUnreadableIs502AndRetries(t *testing.T) {
	a := mustStart(t, config{})
	a.provider.Down(true)
	r := a.browser().get("/auth/login")
	if r.status != 502 || r.str("error") != "PLATFORM_UNAVAILABLE" || r.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	a.provider.Down(false)
	if a.browser().get("/auth/login").status != 302 {
		t.Fatal("the next login tries again")
	}
	for _, ch := range []map[string]any{
		{"issuer": "https://evil.example"},
		{"token_endpoint": "https://evil.example/t"},
		{"jwks_uri": "https://evil.example/k"},
		{"code_challenge_methods_supported": []any{"plain"}},
	} {
		b := mustStart(t, config{})
		b.provider.DiscoveryChanges(ch)
		if b.browser().get("/auth/login").status != 502 {
			t.Fatalf("%v", ch)
		}
	}
}

func TestUnsafeReturnToIsReplacedByTheDefaultLanding(t *testing.T) {
	a := mustStart(t, config{})
	for _, bad := range []string{"//evil.example/x", "/\\evil.example", "https://evil.example/", "javascript:alert(1)", "/a\nb", "x", "/" + strings.Repeat("a", 2048)} {
		r := a.browser().signIn(bad)
		if r.status != 302 || r.header.Get("Location") != "/" {
			t.Errorf("%q: %d %q", bad[:min(len(bad), 20)], r.status, r.header.Get("Location"))
		}
	}
	if got := a.browser().signIn("/ingestions?page=2").header.Get("Location"); got != "/ingestions?page=2" {
		t.Fatal(got)
	}
}

func TestLoginRoutesAreRateLimitedPerAddress(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	for range 20 {
		if b.get("/auth/login").status != 302 {
			t.Fatal("within the budget")
		}
	}
	if b.get("/auth/login").status != 429 || b.get("/auth/callback?state=x").status != 429 {
		t.Fatal("over the budget")
	}
	if b.get("/auth/session").status != 200 {
		t.Fatal("/auth/session is not limited")
	}
}

func TestSigningInEndToEndAsAPublicClient(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	login := b.get("/auth/login?returnTo=%2Forders")
	if login.status != 302 || login.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("%d", login.status)
	}
	authorize := login.header.Get("Location")
	if !strings.HasPrefix(authorize, a.provider.Issuer()+"/oauth/authorize?") {
		t.Fatal(authorize)
	}
	want := []string{"response_type", "client_id", "redirect_uri", "scope", "state", "nonce", "code_challenge", "code_challenge_method"}
	if got := orderedKeys(t, authorize); strings.Join(got, ",") != strings.Join(want, ",") {
		t.Fatalf("%v", got)
	}
	q := query(t, authorize)
	if q["redirect_uri"] != origin+"/auth/callback" || q["scope"] != "openid profile email" || q["code_challenge_method"] != "S256" || q["client_id"] != clientID {
		t.Fatalf("%v", q)
	}
	if len(q["state"]) != 43 || len(q["nonce"]) != 43 || q["state"] == q["nonce"] {
		t.Fatal("state and nonce are 43 distinct characters")
	}
	if len(login.cookies()) == 0 {
		t.Fatal("no binding cookie")
	}
	setLogin := login.cookies()[0]
	for _, part := range []string{"__Host-fw_login_", "; Path=/", "; Max-Age=600", "; HttpOnly", "; SameSite=Lax", "; Secure"} {
		if !strings.Contains(setLogin, part) {
			t.Errorf("binding cookie %q lacks %q", setLogin, part)
		}
	}
	if strings.Contains(setLogin, "Domain") {
		t.Error("no Domain")
	}
	cb := b.get(b.local(b.atProvider(authorize)))
	if cb.status != 302 || cb.header.Get("Location") != "/orders" || cb.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("%d %q", cb.status, cb.header.Get("Location"))
	}
	var session, cleared bool
	for _, c := range cb.cookies() {
		if strings.HasPrefix(c, "__Host-fw_session=") {
			session = len(c) > 0 && strings.HasSuffix(c, "; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax; Secure")
		}
		if strings.HasPrefix(c, "__Host-fw_login_") && strings.Contains(c, "Max-Age=0") {
			cleared = true
		}
	}
	if !session || !cleared {
		t.Fatalf("cookies %v", cb.cookies())
	}
	form := a.provider.LastTokenForm()
	if _, has := form["client_secret"]; has || form["grant_type"] != "authorization_code" || form["redirect_uri"] != origin+"/auth/callback" {
		t.Fatalf("%v", form)
	}
	if a.provider.LastTokenAuthorization() != "" {
		t.Fatal("a public client sends no secret")
	}
	me := b.get("/api/me")
	if me.status != 200 || me.str("principal") != "prn_USER1" || me.str("client") != "_platform" || me.str("source") != "SESSION" {
		t.Fatalf("%d %s", me.status, me.body)
	}
}

func TestConfidentialClientUsesBasic(t *testing.T) {
	a := mustStart(t, config{env: map[string]string{"AUTH_CLIENT_SECRET": "s3cret/with:chars"}, options: nil})
	b := a.browser()
	if b.signIn("/").status != 302 {
		t.Fatal("sign in")
	}
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte("app-client:s3cret%2Fwith%3Achars"))
	if a.provider.LastTokenAuthorization() != want {
		t.Fatalf("%q", a.provider.LastTokenAuthorization())
	}
}

func TestTwoLoginsInFlightAndRotation(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	first, second := b.startSignIn("/a"), b.startSignIn("/b")
	if b.get(b.local(second)).header.Get("Location") != "/b" || b.get(b.local(first)).header.Get("Location") != "/a" {
		t.Fatal("both complete")
	}
	old := b.jar["__Host-fw_session"]
	b.signIn("/")
	if fresh := b.jar["__Host-fw_session"]; fresh == old {
		t.Fatal("rotation gives a new handle")
	}
	thief := a.browser()
	thief.jar["__Host-fw_session"] = old
	if thief.get("/api/me").status != 401 {
		t.Fatal("the old session is deleted")
	}
}

func TestLoginThemeAndRedirectFailureHandler(t *testing.T) {
	a := mustStart(t, config{options: []auth.Option{auth.LoginTheme("acme")}})
	if query(t, a.browser().get("/auth/login").header.Get("Location"))["client"] != "acme" {
		t.Fatal("client parameter")
	}
	h, err := auth.RedirectFailuresTo("/signed-out")
	if err != nil {
		t.Fatal(err)
	}
	a = mustStart(t, config{options: []auth.Option{auth.FailureHandler(h)}})
	a.provider.AuthorizeError("access_denied")
	b := a.browser()
	r := b.get(b.local(b.startSignIn("/")))
	if r.status != 302 || r.header.Get("Location") != "/signed-out?error=LOGIN_REFUSED" || len(b.jar) != 0 {
		t.Fatalf("%d %q", r.status, r.header.Get("Location"))
	}
	_ = url.QueryEscape
}

// flakyStore fails Take (the callback's first store call) while broken is set.
type flakyStore struct {
	*auth.MemoryStore
	broken *bool
}

func (s flakyStore) Take(ctx context.Context, kind auth.Kind, handle string) ([]byte, bool, error) {
	if *s.broken {
		return nil, false, errors.New("store down")
	}
	return s.MemoryStore.Take(ctx, kind, handle)
}

func TestCallbackClearsTheBindingEvenWhenTheStoreFails(t *testing.T) {
	broken := false
	a := mustStart(t, config{store: func(context.Context, *fw.Boot) (auth.Store, error) {
		return flakyStore{MemoryStore: auth.NewMemoryStore(fwtest.NewClock(begin)), broken: &broken}, nil
	}})
	b := a.browser()
	callback := b.startSignIn("/")
	broken = true
	r := b.get(b.local(callback))
	if r.status != 500 {
		t.Fatalf("%d %s", r.status, r.body)
	}
	for name := range b.jar {
		if strings.Contains(name, "login") {
			t.Fatalf("the binding cookie %s survived a 500", name)
		}
	}
}
