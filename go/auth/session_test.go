package auth_test

import (
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
)

func signedIn(t *testing.T, a *app) *browser {
	t.Helper()
	b := a.browser()
	if b.signIn("/").status != 302 {
		t.Fatal("sign in")
	}
	return b
}

func logout(b *browser, headers ...string) response {
	return b.send("POST", "/auth/logout", "", headers...)
}

func TestSessionEndpointWithoutSessionClearsAStaleCookie(t *testing.T) {
	a := mustStart(t, config{})
	b := a.browser()
	r := b.get("/auth/session")
	if r.status != 200 || r.body != `{"authenticated":false,"loginUrl":"/auth/login"}` || r.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("%d %s", r.status, r.body)
	}
	b.jar["__Host-fw_session"] = auth.NewHandle()
	stale := b.get("/auth/session")
	if stale.tree()["authenticated"] != false || len(b.jar) != 0 {
		t.Fatalf("%s %v", stale.body, b.jar)
	}
}

func TestSessionEndpointDescribesThePrincipal(t *testing.T) {
	a := mustStart(t, config{})
	a.provider.SignInAs(map[string]any{"roles": []any{"orders:clerk"}})
	b := signedIn(t, a)
	a.clock.Advance(5 * time.Minute)
	r := b.get("/auth/session")
	want := `{"authenticated":true,"principal":{"id":"prn_USER1","kind":"USER","name":"Ada Lovelace","email":"ada@example.test"},` +
		`"tier":"ANCHOR","client":"_platform","clients":["*"],"applications":["*"],"roles":["orders:clerk"],` +
		`"permissions":["orders:thing:create","orders:thing:view"],"authTime":"2026-10-13T08:00:00Z",` +
		`"expiresAt":"2026-10-13T16:00:00Z","idleExpiresAt":"2026-10-13T08:35:00Z",` +
		`"csrf":{"mode":"fetch-metadata","jsonBodies":true}}`
	if r.body != want {
		t.Fatalf("\n got %s\nwant %s", r.body, want)
	}
}

func TestBearerTokenDoesNotMakeASession(t *testing.T) {
	a := mustStart(t, config{})
	r := a.browser().get("/auth/session", "Authorization", "Bearer "+a.provider.AccessToken(nil))
	if r.tree()["authenticated"] != false {
		t.Fatal(r.body)
	}
}

func TestCrossSiteLogoutIsRefusedAndTheSessionLives(t *testing.T) {
	a := mustStart(t, config{})
	b := signedIn(t, a)
	if logout(b, "Sec-Fetch-Site", "cross-site").status != 403 || logout(b).status != 403 {
		t.Fatal("cross-site and header-less logout are refused")
	}
	if b.get("/api/me").status != 200 {
		t.Fatal("the session lives")
	}
}

func TestLogoutRevokesAndNamesTheEndSessionURL(t *testing.T) {
	a := mustStart(t, config{options: []auth.Option{auth.PostLogoutRedirect("/bye")}})
	b := signedIn(t, a)
	handle := b.jar["__Host-fw_session"]
	copy := a.browser()
	copy.jar["__Host-fw_session"] = handle
	r := logout(b, "Sec-Fetch-Site", "same-origin")
	want := a.provider.Issuer() + "/auth/oidc/session/end?client_id=app-client&post_logout_redirect_uri=https%3A%2F%2Fapp.example%2Fbye"
	if r.status != 200 || r.tree()["loggedOut"] != true || r.str("endSessionUrl") != want {
		t.Fatalf("%d %s", r.status, r.body)
	}
	if len(b.jar) != 0 || copy.get("/api/me").status != 401 {
		t.Fatal("the session is revoked")
	}
}

func TestLogoutWithoutSessionAndBodies(t *testing.T) {
	a := mustStart(t, config{})
	r := a.browser().send("POST", "/auth/logout", "")
	if r.status != 200 || !strings.HasPrefix(r.str("endSessionUrl"), a.provider.Issuer()+"/auth/oidc/session/end?client_id=app-client") {
		t.Fatalf("%d %s", r.status, r.body)
	}
	b := signedIn(t, a)
	if b.send("POST", "/auth/logout", "{}", "Sec-Fetch-Site", "same-origin", "Content-Type", "application/json").status != 200 {
		t.Fatal("a JSON body is allowed")
	}
	c := signedIn(t, a)
	if c.send("POST", "/auth/logout", "x", "Sec-Fetch-Site", "same-origin", "Content-Type", "text/plain").status != 415 {
		t.Fatal("a text body is 415")
	}
}

func TestIdleAndAbsoluteTimeouts(t *testing.T) {
	a := mustStart(t, config{})
	b := signedIn(t, a)
	handle := b.jar["__Host-fw_session"]
	a.clock.Advance(29*time.Minute + 59*time.Second)
	if b.get("/api/me").status != 200 {
		t.Fatal("alive just before the idle timeout")
	}
	a.clock.Advance(30 * time.Minute)
	r := b.get("/api/me")
	if r.status != 401 || !strings.Contains(strings.Join(r.cookies(), "|"), "__Host-fw_session=;") {
		t.Fatalf("%d %v", r.status, r.cookies())
	}
	if a.store.Size() != 1 { // only the consumed... no login records remain
		if _, ok, _ := a.store.Read(t.Context(), auth.KindSession, handle); ok {
			t.Fatal("an idle-expired session is deleted")
		}
	}
	b = signedIn(t, a)
	for i := range 23 {
		a.clock.Advance(20 * time.Minute)
		if b.get("/api/me").status != 200 {
			t.Fatalf("busy session dead after %d minutes", (i+1)*20)
		}
	}
	a.clock.Advance(20 * time.Minute)
	if b.get("/api/me").status != 401 {
		t.Fatal("the absolute timeout is 8 hours however busy")
	}
}

func TestTimeoutsAreEnforcedEvenWhenTheStoreWouldKeepTheRecord(t *testing.T) {
	a := mustStart(t, config{})
	now := a.clock.Now()
	mk := func(created, seen, absolute time.Time) string {
		doc := `{"v":1,"principal":{"id":"prn_OLD","kind":"USER"},"clients":["*"],"applications":["*"],"roles":[],` +
			`"authTime":"` + now.Format(time.RFC3339) + `","createdAt":"` + created.Format(time.RFC3339) + `","lastSeenAt":"` + seen.Format(time.RFC3339) +
			`","absoluteExpiresAt":"` + absolute.Format(time.RFC3339) + `"}`
		h, err := a.store.Create(t.Context(), auth.KindSession, []byte(doc), now.Add(24*time.Hour))
		if err != nil {
			t.Fatal(err)
		}
		return h
	}
	idleOld := mk(now, now.Add(-31*time.Minute), now.Add(time.Hour))
	absoluteOld := mk(now.Add(-9*time.Hour), now, now.Add(-time.Second))
	fine := mk(now, now, now.Add(time.Hour))
	get := func(h string) int { return a.do("GET", "/api/me", "", "Cookie", "__Host-fw_session="+h).status }
	if get(idleOld) != 401 || get(absoluteOld) != 401 || get(fine) != 200 {
		t.Fatal("the session code enforces the timeouts")
	}
	if _, ok, _ := a.store.Read(t.Context(), auth.KindSession, idleOld); ok {
		t.Fatal("deleted")
	}
}

func TestCookieSentTwiceIsIgnoredAndQuotedIsTheSame(t *testing.T) {
	a := mustStart(t, config{})
	b := signedIn(t, a)
	h := b.jar["__Host-fw_session"]
	for _, header := range []string{
		"__Host-fw_session=" + h + "; __Host-fw_session=" + auth.NewHandle(),
		"__Host-fw_session=" + auth.NewHandle() + "; __Host-fw_session=" + h,
	} {
		if a.do("GET", "/api/me", "", "Cookie", header).status != 401 {
			t.Fatal("a name sent twice is no cookie")
		}
	}
	if a.do("GET", "/api/me", "", "Cookie", "__Host-fw_session=x", "Cookie", "__Host-fw_session="+h).status != 401 {
		t.Fatal("a name split over two headers is sent twice")
	}
	if a.do("GET", "/api/me", "", "Cookie", `$Version="1"; __Host-fw_session="`+h+`"; $Path="/"`).status != 200 {
		t.Fatal("a quoted value is the same cookie")
	}
}

func TestPlainHttpInTestLosesSecureAndThePrefix(t *testing.T) {
	a := mustStart(t, config{env: map[string]string{"AUTH_PUBLIC_BASE_URL": "http://localhost:8091"}})
	b := &browser{app: a, origin: "http://localhost:8091", jar: map[string]string{}}
	cb := b.signIn("/")
	ok := false
	for _, c := range cb.cookies() {
		ok = ok || (strings.HasPrefix(c, "fw_session=") && strings.HasSuffix(c, "; Path=/; Max-Age=28800; HttpOnly; SameSite=Lax"))
	}
	if !ok || b.jar["fw_session"] == "" || b.jar["__Host-fw_session"] != "" {
		t.Fatalf("%v %v", cb.cookies(), b.jar)
	}
	if b.get("/api/me").status != 200 || b.post("/api/echo", "{}", "Origin", "http://localhost:8091").status != 200 {
		t.Fatal("works over http in test")
	}
}

func TestStartUpRefusals(t *testing.T) {
	_, err := startApp(t, config{env: map[string]string{"AUTH_PUBLIC_BASE_URL": "http://app.example", "APP_ENV": "production"}})
	if err == nil || !strings.Contains(err.Error(), "plain http") {
		t.Fatalf("%v", err)
	}
	_, err = startApp(t, config{env: map[string]string{"APP_ENV": "production"}})
	if err == nil || !strings.Contains(err.Error(), "auth.issuer is plain http") {
		t.Fatalf("%v", err)
	}
	_, err = startApp(t, config{env: map[string]string{"AUTH_PUBLIC_BASE_URL": "https://app.example/ingest"}})
	if err == nil || !strings.Contains(err.Error(), "no path") {
		t.Fatalf("%v", err)
	}
	_, err = startApp(t, config{options: []auth.Option{auth.CookieSessions()}})
	if err == nil || !strings.Contains(err.Error(), "auth.cookie-keys") {
		t.Fatalf("%v", err)
	}
	_, err = startApp(t, config{options: []auth.Option{auth.Scope("openid offline_access")}})
	if err == nil || !strings.Contains(err.Error(), "offline_access") {
		t.Fatalf("%v", err)
	}
	_, err = startApp(t, config{options: []auth.Option{auth.IdleTimeout(9 * time.Hour)}})
	if err == nil || !strings.Contains(err.Error(), "longer than the absolute") {
		t.Fatalf("%v", err)
	}
}

func TestCookieSessionsEndToEnd(t *testing.T) {
	keys, err := auth.GenerateCookieKey("k1")
	if err != nil {
		t.Fatal(err)
	}
	a := mustStart(t, config{env: map[string]string{"AUTH_COOKIE_KEYS": keys}, options: []auth.Option{auth.CookieSessions()}})
	b := signedIn(t, a)
	sealed := b.jar["__Host-fw_session"]
	if !strings.HasPrefix(sealed, "v1.k1.") || a.store.Size() != 0 {
		t.Fatal("the record travels in the cookie")
	}
	if b.get("/api/me").status != 200 {
		t.Fatal("works")
	}
	a.clock.Advance(2 * time.Minute)
	touched := b.get("/api/me")
	if !strings.Contains(strings.Join(touched.cookies(), "|"), "__Host-fw_session=v1.k1.") || b.jar["__Host-fw_session"] == sealed {
		t.Fatal("lastSeenAt write-back reseals")
	}
	copy := a.browser()
	copy.jar["__Host-fw_session"] = b.jar["__Host-fw_session"]
	if logout(b, "Sec-Fetch-Site", "same-origin").status != 200 || copy.get("/api/me").status != 200 {
		t.Fatal("a copy taken before logout keeps working: the documented limit")
	}
	a.clock.Advance(31 * time.Minute)
	if copy.get("/api/me").status != 401 {
		t.Fatal("until it expires")
	}
}

func TestTestingHelper(t *testing.T) {
	a := mustStart(t, config{})
	tst, err := a.module.Testing()
	if err != nil {
		t.Fatal(err)
	}
	cookie, err := tst.Cookie(t.Context(), auth.User("prn_T", "orders:clerk"))
	if err != nil || !strings.HasPrefix(cookie, tst.CookieName()+"=") {
		t.Fatal(cookie, err)
	}
	me := a.do("GET", "/api/me", "", "Cookie", cookie)
	if me.str("principal") != "prn_T" || me.str("principalKind") != "USER" {
		t.Fatal(me.body)
	}
	if a.do("POST", "/api/things", "{}", "Cookie", cookie, "Sec-Fetch-Site", "same-origin", "Content-Type", "application/json").status != 201 {
		t.Fatal("permission from the role")
	}
	cc, _ := tst.Cookie(t.Context(), auth.ClientUser("prn_C", "clt_1", "acme"))
	if a.do("GET", "/api/me", "", "Cookie", cc).str("client") != "acme" {
		t.Fatal("client user")
	}
	local := mustStart(t, config{env: map[string]string{"APP_ENV": "local"}})
	if _, err := local.module.Testing(); err == nil || !strings.Contains(err.Error(), "APP_ENV") {
		t.Fatalf("%v", err)
	}
}

func TestConfigKeysUnderAuthAreChecked(t *testing.T) {
	_, err := startApp(t, config{files: "auth:\n  isuer: https://typo.example.com\n  idle-timeout: 1h\n"})
	if err == nil || !strings.Contains(err.Error(), "auth.issuer") || !strings.Contains(err.Error(), "auth.isuer") {
		t.Fatalf("%v", err)
	}
	if _, err := startApp(t, config{files: "auth:\n  client-id: from-file\n"}); err != nil {
		t.Fatal(err)
	}
}
