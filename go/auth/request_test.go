package auth_test

import (
	"net/http"
	"testing"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
)

func clerkApp(t *testing.T, c config) (*app, *browser) {
	t.Helper()
	a := mustStart(t, c)
	a.provider.SignInAs(map[string]any{"roles": []any{"orders:clerk"}})
	b := a.browser()
	if b.signIn("/").status != 302 {
		t.Fatal("sign in")
	}
	return a, b
}

func postAs(b *browser, path, contentType, body string, headers ...string) response {
	h := headers
	if contentType != "" {
		h = append([]string{"Content-Type", contentType}, h...)
	}
	return b.send("POST", path, body, h...)
}

func TestCSRFOnCookieRequests(t *testing.T) {
	_, b := clerkApp(t, config{})
	for _, site := range []string{"cross-site", "same-site", "bogus"} {
		r := postAs(b, "/api/things", "application/json", "{}", "Sec-Fetch-Site", site)
		if r.status != 403 || r.str("error") != "CROSS_ORIGIN_REQUEST" {
			t.Errorf("%s: %d %s", site, r.status, r.body)
		}
	}
	for origin, want := range map[string]int{
		"https://evil.example": 403, "null": 403, "https://app.example.evil": 403, "http://app.example": 403,
		"https://app.example": 201, "https://APP.example:443": 201,
	} {
		if got := postAs(b, "/api/things", "application/json", "{}", "Origin", origin).status; got != want {
			t.Errorf("Origin %s: %d, want %d", origin, got, want)
		}
	}
	if r := postAs(b, "/api/things", "application/json", "{}"); r.status != 403 {
		t.Errorf("neither header: %d", r.status)
	}
	if postAs(b, "/api/things", "application/json", "{}", "Sec-Fetch-Site", "cross-site", "Origin", "https://app.example").status != 403 {
		t.Error("Fetch Metadata wins over a matching Origin")
	}
	for _, ct := range []string{"application/x-www-form-urlencoded", "text/plain", "multipart/form-data; boundary=x"} {
		r := postAs(b, "/api/things", ct, "a=b", "Sec-Fetch-Site", "same-origin")
		if r.status != 415 || r.str("error") != "UNSUPPORTED_MEDIA_TYPE" {
			t.Errorf("%s: %d", ct, r.status)
		}
	}
	if postAs(b, "/api/things", "", "a=b", "Sec-Fetch-Site", "same-origin").status != 415 {
		t.Error("a body without a content type")
	}
	if postAs(b, "/api/things", "application/merge-patch+json", "{}", "Sec-Fetch-Site", "same-origin").status != 201 ||
		postAs(b, "/api/things", "application/json; charset=utf-8", "{}", "Sec-Fetch-Site", "none").status != 201 {
		t.Error("json media types")
	}
	if postAs(b, "/api/echo", "", "", "Sec-Fetch-Site", "same-origin").status != 200 {
		t.Error("a request without a body needs no content type")
	}
	if b.get("/api/me", "Sec-Fetch-Site", "cross-site").status != 200 {
		t.Error("safe methods are not checked")
	}
}

func TestBearerRequestsAreExemptAndTheCookieIsIgnored(t *testing.T) {
	a, b := clerkApp(t, config{})
	token := a.provider.AccessToken(map[string]any{"sub": "prn_SVC", "scope": "orders:thing:create"})
	if postAs(b, "/api/things", "text/plain", "x", "Authorization", "Bearer "+token, "Sec-Fetch-Site", "cross-site").status != 201 {
		t.Fatal("bearer is exempt from CSRF")
	}
	me := b.get("/api/me", "Authorization", "Bearer "+token)
	if me.str("principal") != "prn_SVC" || me.str("principalKind") != "SERVICE" || me.str("source") != "BEARER" {
		t.Fatal(me.body)
	}
}

func TestBadBearerNeverFallsBack(t *testing.T) {
	a, b := clerkApp(t, config{})
	if b.get("/api/me", "Authorization", "Bearer not-a-token").status != 401 ||
		b.get("/api/me", "Authorization", "Bearer "+a.provider.AccessToken(map[string]any{"token_use": "identity"})).status != 401 ||
		b.get("/api/me", "Authorization", "Basic YTpi").status != 401 {
		t.Fatal("a bad bearer is 401")
	}
	if b.get("/api/me").status != 200 {
		t.Fatal("the cookie still works without Authorization")
	}
	me := a.browser().get("/api/me", "Authorization", "bearer "+a.provider.AccessToken(map[string]any{"scope": "orders:thing:view x:y"}))
	got := me.tree()["permissions"].([]any)
	if me.status != 200 || len(got) != 2 || got[0] != "orders:thing:view" || got[1] != "x:y" {
		t.Fatalf("%s", me.body)
	}
}

func TestKeysUnreadableIs503AndBearerCanBeOff(t *testing.T) {
	a := mustStart(t, config{})
	token := a.provider.AccessToken(nil)
	a.provider.Down(true)
	r := a.browser().get("/api/me", "Authorization", "Bearer "+token)
	if r.status != 503 || r.str("error") != "UNAVAILABLE" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	off := mustStart(t, config{declare: []auth.DeclareOption{auth.NoBearerTokens()}})
	if off.browser().get("/api/me", "Authorization", "Bearer "+off.provider.AccessToken(nil)).status != 401 {
		t.Fatal("bearer off")
	}
}

func TestContextPermissionsAndApplication(t *testing.T) {
	a, b := clerkApp(t, config{options: []auth.Option{auth.Application(fw.MustApplicationCode("orders"))}})
	me := b.get("/api/me")
	perms := me.tree()["permissions"].([]any)
	if len(perms) != 2 || perms[0] != "orders:thing:create" || me.str("application") != "orders" {
		t.Fatal(me.body)
	}
	plain := mustStart(t, config{})
	pb := plain.browser()
	pb.signIn("/")
	r := postAs(pb, "/api/things", "application/json", "{}", "Sec-Fetch-Site", "same-origin")
	if r.status != 403 || r.str("error") != "FORBIDDEN" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	_ = a
}

func TestSelectedClientMustBeOneThePrincipalMayActFor(t *testing.T) {
	sel := func(_ auth.Principal, r *http.Request) (fw.ClientID, error) {
		if v := r.Header.Get("X-Client"); v != "" {
			return fw.ParseClientID(v)
		}
		return auth.PlatformClient, nil
	}
	a := mustStart(t, config{options: []auth.Option{auth.Clients(sel)}})
	a.provider.SignInAs(map[string]any{"tier": "PARTNER", "clients": []any{"clt_1:acme", "clt_2:globex"}})
	b := a.browser()
	b.signIn("/")
	if b.get("/api/me", "X-Client", "acme").str("client") != "acme" || b.get("/api/me", "X-Client", "clt_2").str("client") != "clt_2" {
		t.Fatal("acme and clt_2 are allowed")
	}
	r := b.get("/api/me", "X-Client", "initech")
	if r.status != 403 || r.str("error") != "CLIENT_NOT_PERMITTED" || b.get("/api/me", "X-Client", "clt").status != 403 {
		t.Fatalf("%d %s", r.status, r.body)
	}
}

func TestDefaultClientSelection(t *testing.T) {
	a := mustStart(t, config{})
	anon := a.browser()
	client := a.provider.AccessToken(map[string]any{"tier": "CLIENT", "clients": []any{"clt_1:acme"}})
	partner := a.provider.AccessToken(map[string]any{"tier": "PARTNER", "clients": []any{"clt_1:acme", "clt_2:globex"}})
	bare := a.provider.AccessToken(map[string]any{"tier": "CLIENT", "clients": []any{"clt_1"}})
	for token, want := range map[string]string{client: "acme", partner: "_platform", bare: "_platform", a.provider.AccessToken(nil): "_platform"} {
		if got := anon.get("/api/me", "Authorization", "Bearer "+token).str("client"); got != want {
			t.Errorf("client %q, want %q", got, want)
		}
	}
	a.provider.SignInAs(map[string]any{"tier": "CLIENT", "client_id": "clt_9", "clients": []any{"clt_9:initech"}})
	b := a.browser()
	b.signIn("/")
	if b.get("/api/me").str("client") != "initech" {
		t.Fatal("the home client's identifier")
	}
}

func TestRedirectOrJSONFor401(t *testing.T) {
	a := mustStart(t, config{})
	anon := a.browser()
	r := anon.get("/page?a=1&b=%2F", "Sec-Fetch-Mode", "navigate", "Accept", "text/html")
	if r.status != 302 || r.header.Get("Location") != "/auth/login?returnTo=%2Fpage%3Fa%3D1%26b%3D%252F" {
		t.Fatalf("%d %q", r.status, r.header.Get("Location"))
	}
	if anon.get("/page", "Accept", "text/html,application/xhtml+xml").status != 302 || anon.send("HEAD", "/page", "", "Sec-Fetch-Mode", "navigate").status != 302 {
		t.Fatal("older browsers and HEAD")
	}
	fetch := anon.get("/api/me", "Sec-Fetch-Mode", "cors", "Accept", "text/html")
	if fetch.status != 401 || fetch.str("error") != "UNAUTHENTICATED" {
		t.Fatal(fetch.body)
	}
	if anon.get("/api/me").status != 401 || anon.get("/api/me", "Accept", "application/json").status != 401 ||
		anon.send("POST", "/api/echo", "{}", "Sec-Fetch-Mode", "navigate", "Accept", "text/html", "Content-Type", "application/json").status != 401 ||
		anon.get("/page", "Sec-Fetch-Mode", "navigate", "Authorization", "Bearer x").status != 401 {
		t.Fatal("only GET/HEAD navigations without Authorization are redirected")
	}
	if anon.get("/public", "Sec-Fetch-Mode", "navigate").status != 302 || anon.get("/public", "Sec-Fetch-Mode", "cors").status != 401 {
		t.Fatal("a handler's own 401 is redirected for a navigation too")
	}
}
