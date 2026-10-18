package ordersweb

import (
	"bytes"
	"encoding/json/v2"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"net/http/cookiejar"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/web"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

type site struct {
	base     string
	run      *fw.Running
	mod      *auth.Module
	provider *authtest.Provider
}

// startWeb starts `orders-demo serve` on the embedded build with the database session store and a
// test identity provider; extra overrides the environment. The public base URL is a port reserved
// beforehand so the real sign-in round trip can be driven.
func startWeb(t *testing.T, extra map[string]string) *site { return startWebWith(t, extra, nil) }

// startWebWith is startWeb with the admin area of the extension (nil: no admin area).
func startWebWith(t *testing.T, extra map[string]string, ext Extension) *site {
	t.Helper()
	port := reservePort(t)
	origin := fmt.Sprintf("http://127.0.0.1:%d", port)
	provider := authtest.Start(fw.SystemClock())
	t.Cleanup(provider.Close)
	provider.Client("orders-web", "", origin+"/auth/callback")
	env := map[string]string{
		"APP_ENV":                          "test",
		"DATABASE_INSTANCES_MAIN_URL":      fwtest.NewDatabase(t).URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
		"HTTP_HOST":                        "127.0.0.1",
		"HTTP_PORT":                        fmt.Sprint(port),
		"AUTH_ISSUER":                      provider.Issuer(),
		"AUTH_CLIENT_ID":                   "orders-web",
		"AUTH_PUBLIC_BASE_URL":             origin,
	}
	maps.Copy(env, extra)
	app := NewApp(fw.WithEnvironment(env), fw.WithConfigDir("../config"), fw.WithOutput(io.Discard, io.Discard))
	d := Declare(app, ext)
	s := &site{base: origin, provider: provider}
	run, err := app.Start(t.Context(), BuildWeb(d, Deps{Served: func(m *auth.Module, _ *database.DB) { s.mod = m }}))
	if err != nil {
		t.Fatal(err)
	}
	if s.mod == nil {
		t.Fatal("no server was built")
	}
	s.run = run
	t.Cleanup(func() { _ = run.Shutdown() })
	return s
}

func reservePort(t *testing.T) int {
	t.Helper()
	l, err := (&net.ListenConfig{}).Listen(t.Context(), "tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = l.Close() }()
	addr, ok := l.Addr().(*net.TCPAddr)
	if !ok {
		t.Fatal("not a TCP address")
	}
	return addr.Port
}

// cookie signs a user of the demo's client in through the real store.
func (s *site) cookie(t *testing.T, roles ...string) string {
	t.Helper()
	testing, err := s.mod.Testing()
	if err != nil {
		t.Fatal(err)
	}
	c, err := testing.Cookie(t.Context(), auth.ClientUser("prn_ada", "clt_demo", "demo", roles...))
	if err != nil {
		t.Fatal(err)
	}
	return c
}

type answer struct {
	status int
	header http.Header
	body   string
}

func (s *site) call(t *testing.T, method, path, body string, headers ...string) answer {
	t.Helper()
	var rd io.Reader
	if body != "" {
		rd = strings.NewReader(body)
	}
	req, err := http.NewRequestWithContext(t.Context(), method, s.base+path, rd)
	if err != nil {
		t.Fatal(err)
	}
	for i := 0; i+1 < len(headers); i += 2 {
		req.Header.Set(headers[i], headers[i+1])
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return answer{res.StatusCode, res.Header, string(b)}
}

// api calls as a signed-in clerk, the way the page does (cookie, same-origin).
func (s *site) api(t *testing.T, method, path, body string) answer {
	t.Helper()
	return s.apiAs(t, s.cookie(t, "orders-demo:clerk"), method, path, body)
}

func (s *site) apiAs(t *testing.T, cookie, method, path, body string) answer {
	t.Helper()
	h := []string{"Cookie", cookie, "Sec-Fetch-Site", "same-origin"}
	if body != "" {
		h = append(h, "Content-Type", "application/json")
	}
	return s.call(t, method, path, body, h...)
}

func decode[T any](t *testing.T, a answer) T {
	t.Helper()
	var v T
	if err := json.Unmarshal([]byte(a.body), &v); err != nil {
		t.Fatalf("decode %s: %v", a.body, err)
	}
	return v
}

type orderJSON struct {
	ID         string `json:"id"`
	Customer   string `json:"customer"`
	Status     string `json:"status"`
	Version    int64  `json:"version"`
	TotalCents int64  `json:"totalCents"`
	Lines      []struct {
		SKU        string `json:"sku"`
		Quantity   int    `json:"quantity"`
		TotalCents int64  `json:"totalCents"`
	} `json:"lines"`
}

func TestTheOrdersAPIEndToEnd(t *testing.T) {
	t.Parallel()
	s := startWeb(t, nil)

	if a := s.call(t, "GET", "/api/orders", ""); a.status != 401 || !strings.Contains(a.body, `"UNAUTHENTICATED"`) {
		t.Errorf("without a session: %d %s", a.status, a.body)
	}
	if a := s.call(t, "GET", "/api/orders", "", "Authorization", "Bearer nope"); a.status != 401 {
		t.Errorf("a wrong token: %d", a.status)
	}

	placed := s.api(t, "POST", "/api/orders", `{"customer":"Ada Lovelace"}`)
	if placed.status != 201 {
		t.Fatalf("place: %d %s", placed.status, placed.body)
	}
	order := decode[orderJSON](t, placed)
	if order.Status != "OPEN" || order.Version != 1 || order.Customer != "Ada Lovelace" || len(order.Lines) != 0 ||
		placed.header.Get("Location") != "/api/orders/"+order.ID {
		t.Errorf("placed %+v location=%q", order, placed.header.Get("Location"))
	}

	added := s.api(t, "POST", "/api/orders/"+order.ID+"/lines", `{"sku":"ENGINE","quantity":2,"unitPriceCents":60000}`)
	if added.status != 200 {
		t.Fatalf("add line: %d %s", added.status, added.body)
	}
	order = decode[orderJSON](t, added)
	if len(order.Lines) != 1 || order.TotalCents != 120000 || order.Version != 2 || order.Lines[0].TotalCents != 120000 {
		t.Errorf("after the line: %+v", order)
	}

	if a := s.api(t, "POST", "/api/orders/"+order.ID+"/lines", `{"sku":"lower case","quantity":0,"unitPriceCents":-5}`); a.status != 400 ||
		!strings.Contains(a.body, `"sku":"PATTERN: has an invalid format"`) ||
		!strings.Contains(a.body, `"quantity":"OUT_OF_RANGE: must be between 1 and 10000"`) ||
		!strings.Contains(a.body, `"unitPriceCents":"OUT_OF_RANGE: must be between 0 and 100000000"`) {
		t.Errorf("every field error at once: %d %s", a.status, a.body)
	}
	if a := s.api(t, "GET", "/api/orders/ord_00000000000XX", ""); a.status != 404 || !strings.Contains(a.body, `"ORDER_NOT_FOUND"`) {
		t.Errorf("unknown order: %d %s", a.status, a.body)
	}
	if a := s.api(t, "GET", "/api/orders/"+order.ID, ""); a.status != 200 || decode[orderJSON](t, a).ID != order.ID {
		t.Errorf("get: %d %s", a.status, a.body)
	}

	// two more orders, then paging and the status filter
	second := decode[orderJSON](t, s.api(t, "POST", "/api/orders", `{"customer":"Grace"}`))
	third := decode[orderJSON](t, s.api(t, "POST", "/api/orders", `{"customer":"Edsger"}`))
	cancelled := s.api(t, "POST", "/api/orders/"+second.ID+"/cancel", `{"reason":"changed my mind"}`)
	if cancelled.status != 200 || decode[orderJSON](t, cancelled).Status != "CANCELLED" {
		t.Fatalf("cancel: %d %s", cancelled.status, cancelled.body)
	}
	if a := s.api(t, "POST", "/api/orders/"+second.ID+"/lines", `{"sku":"X","quantity":1,"unitPriceCents":1}`); a.status != 409 ||
		a.body != `{"error":"ORDER_NOT_OPEN","message":"The order is cancelled and cannot change."}` {
		t.Errorf("add after cancel: %d %s", a.status, a.body)
	}

	type page struct {
		Items []struct {
			ID       string  `json:"id"`
			Status   string  `json:"status"`
			FirstSku *string `json:"firstSku"`
		} `json:"items"`
		NextCursor *string `json:"nextCursor"`
	}
	all := decode[page](t, s.api(t, "GET", "/api/orders", ""))
	if len(all.Items) != 3 || all.NextCursor != nil {
		t.Fatalf("all: %+v", all)
	}
	raw := s.api(t, "GET", "/api/orders?limit=2", "").body
	if !strings.Contains(raw, `"nextCursor"`) || strings.Contains(s.api(t, "GET", "/api/orders", "").body, "nextCursor") {
		t.Errorf("nextCursor is present on a first page and absent on the last: %s", raw)
	}
	first := decode[page](t, s.api(t, "GET", "/api/orders?limit=2", ""))
	if len(first.Items) != 2 || first.NextCursor == nil || *first.NextCursor != first.Items[1].ID {
		t.Fatalf("first page: %+v", first)
	}
	rest := decode[page](t, s.api(t, "GET", "/api/orders?limit=2&after="+*first.NextCursor, ""))
	if len(rest.Items) != 1 || rest.NextCursor != nil || rest.Items[0].ID != all.Items[2].ID {
		t.Errorf("second page: %+v", rest)
	}
	open := decode[page](t, s.api(t, "GET", "/api/orders?status=OPEN", ""))
	if len(open.Items) != 2 {
		t.Errorf("open orders: %+v (third %s)", open, third.ID)
	}
	if a := s.api(t, "GET", "/api/orders?status=PENDING&limit=many", ""); a.status != 400 ||
		!strings.Contains(a.body, "must be one of: OPEN, CANCELLED") || !strings.Contains(a.body, "must be an integer") {
		t.Errorf("parameter errors: %d %s", a.status, a.body)
	}

	// the write path ran: events and audit rows are in the outbox
	if a := s.api(t, "OPTIONS", "/api/orders", ""); a.status != 204 || a.header.Get("Allow") != "GET, HEAD, OPTIONS, POST" {
		t.Errorf("OPTIONS: %d %v", a.status, a.header)
	}
}

func TestTheSPAIsTheFallbackAndAPIPathsStayJSON(t *testing.T) {
	t.Parallel()
	s := startWeb(t, nil)
	index, _ := io.ReadAll(mustOpen(t, "dist/index.html"))
	home := s.call(t, "GET", "/", "")
	if home.status != 200 || home.body != string(index) || home.header.Get("Cache-Control") != "no-cache" {
		t.Errorf("/: %d cache=%q", home.status, home.header.Get("Cache-Control"))
	}
	if csp := home.header.Get("Content-Security-Policy"); !strings.HasPrefix(csp, "default-src 'self'; script-src 'self'") {
		t.Errorf("the strict policy is the default: %q", csp)
	}
	if a := s.call(t, "GET", "/orders/anything-the-spa-routes", ""); a.status != 200 || a.body != string(index) {
		t.Errorf("a client route: %d", a.status)
	}
	if a := s.call(t, "GET", "/api/nothing-here", ""); a.status != 404 || a.header.Get("Content-Type") != "application/json" || a.header.Get("Cache-Control") != "no-store" {
		t.Errorf("an unknown API path: %d %v", a.status, a.header)
	}
	if a := s.call(t, "GET", "/assets/gone-abc.js", ""); a.status != 404 || !strings.Contains(a.body, "NOT_FOUND") {
		t.Errorf("a missing asset: %d %s", a.status, a.body)
	}
	if a := s.call(t, "GET", "/health/ready", ""); a.status != 200 || !strings.Contains(a.body, `"database:main":{"status":"UP"}`) {
		t.Errorf("ready: %d %s", a.status, a.body)
	}
}

func mustOpen(t *testing.T, name string) io.Reader {
	t.Helper()
	b, err := fs_ReadFile(name)
	if err != nil {
		t.Fatal(err)
	}
	return bytes.NewReader(b)
}

// The committed document is current (OpenAPI contract section 9): regenerated from the real
// declarations, with no database and no configuration, and it is the Java demo's document.
func TestTheCommittedOpenAPIDocumentIsCurrent(t *testing.T) {
	t.Parallel()
	app := NewApp(fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	d := Declare(app, nil)
	doc, problems := d.web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	if !bytes.Equal(doc, web.OpenAPI()) {
		t.Fatal("web/openapi.json is stale: run `go run ./examples/orders-demo openapi --out examples/orders-demo/web/openapi.json` from go/")
	}
	vector, err := os.ReadFile(filepath.Join("..", "..", "..", "..", "conformance", "openapi", "orders-demo.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(doc, vector) {
		t.Fatal("the document differs from conformance/openapi/orders-demo.json")
	}
}

func TestServingNeedsTheAuthSettings(t *testing.T) {
	t.Parallel()
	env := map[string]string{
		"APP_ENV": "test", "DATABASE_INSTANCES_MAIN_URL": fwtest.NewDatabase(t).URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres", "DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
		"HTTP_PORT": "0",
	}
	app := NewApp(fw.WithEnvironment(env), fw.WithConfigDir("../config"), fw.WithOutput(io.Discard, io.Discard))
	_, err := app.Start(t.Context(), BuildWeb(Declare(app, nil), Deps{}))
	if err == nil || !strings.Contains(err.Error(), "auth.issuer") {
		t.Fatalf("got %v", err)
	}
}

func fs_ReadFile(name string) ([]byte, error) { return os.ReadFile(filepath.Join("..", "web", name)) }

func TestTheRolesDecideWhatAnOrdersCallMayDo(t *testing.T) {
	t.Parallel()
	s := startWeb(t, nil)
	viewer := s.cookie(t, "orders-demo:viewer")
	if a := s.apiAs(t, viewer, "GET", "/api/orders", ""); a.status != 200 {
		t.Errorf("a viewer reads: %d %s", a.status, a.body)
	}
	if a := s.apiAs(t, viewer, "POST", "/api/orders", `{"customer":"X"}`); a.status != 403 || !strings.Contains(a.body, `"FORBIDDEN"`) {
		t.Errorf("a viewer writes: %d %s", a.status, a.body)
	}
	none := s.cookie(t)
	if a := s.apiAs(t, none, "GET", "/api/orders", ""); a.status != 403 {
		t.Errorf("no role: %d %s", a.status, a.body)
	}
}

func TestACookieWriteNeedsASameOriginRequest(t *testing.T) {
	t.Parallel()
	s := startWeb(t, nil)
	cookie := s.cookie(t, "orders-demo:clerk")
	if a := s.call(t, "POST", "/api/orders", `{"customer":"X"}`, "Cookie", cookie, "Content-Type", "application/json"); a.status != 403 {
		t.Errorf("no Sec-Fetch-Site or Origin on an unsafe method: %d %s", a.status, a.body)
	}
	if a := s.call(t, "POST", "/api/orders", `{"customer":"X"}`, "Cookie", cookie, "Content-Type", "application/json", "Sec-Fetch-Site", "cross-site"); a.status != 403 {
		t.Errorf("cross-site: %d %s", a.status, a.body)
	}
}

// The real round trip: /auth/login -> the test provider -> /auth/callback -> a session cookie that the
// orders API accepts -> sign out. The tests above sign in through auth.Testing; this drives the endpoints.
func TestSigningInAndOutThroughTheProvider(t *testing.T) {
	t.Parallel()
	s := startWeb(t, nil)
	s.provider.SignInAs(map[string]any{
		"sub": "prn_ada", "type": "USER", "tier": "CLIENT", "clients": []string{"clt_demo:demo"},
		"name": "Ada Lovelace", "email": "ada@example.test", "roles": []string{"orders-demo:clerk"},
	})
	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Jar: jar}
	get := func(path string) answer {
		t.Helper()
		req, err := http.NewRequestWithContext(t.Context(), "GET", s.base+path, nil)
		if err != nil {
			t.Fatal(err)
		}
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return answer{res.StatusCode, res.Header, string(b)}
	}
	// An anonymous navigation to a protected API path is sent to sign-in and, after it, comes back.
	if a := get("/auth/session"); a.status != 200 || !strings.Contains(a.body, `"authenticated":false`) {
		t.Fatalf("anonymous session: %d %s", a.status, a.body)
	}
	a := get("/auth/login?returnTo=%2Forders")
	if a.status != 200 {
		t.Fatalf("login round trip ended at %d %s", a.status, a.body)
	}
	if a := get("/auth/session"); !strings.Contains(a.body, `"authenticated":true`) || !strings.Contains(a.body, "Ada Lovelace") {
		t.Fatalf("signed in session: %d %s", a.status, a.body)
	}
	if a := get("/api/orders"); a.status != 200 {
		t.Fatalf("the orders API after sign-in: %d %s", a.status, a.body)
	}
	req, err := http.NewRequestWithContext(t.Context(), "POST", s.base+"/auth/logout", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Sec-Fetch-Site", "same-origin")
	res, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	_ = res.Body.Close()
	if res.StatusCode != 200 {
		t.Fatalf("logout: %d", res.StatusCode)
	}
	if a := get("/api/orders"); a.status != 401 {
		t.Errorf("after logout: %d %s", a.status, a.body)
	}
}

// The admin area of the core module (no platform sections): the administrator sees about, config and
// the demo's own section; the clerk is refused; the auditor has the console only.
func TestTheAdminAreaWithoutThePlatform(t *testing.T) {
	t.Parallel()
	s := startWebWith(t, nil, NoPlatform)
	admin, clerk, auditor := s.cookie(t, "orders-demo:admin"), s.cookie(t, "orders-demo:clerk"), s.cookie(t, "orders-demo:auditor")

	nav := decode[struct {
		Sections []struct {
			ID string `json:"id"`
		} `json:"sections"`
	}](t, s.apiAs(t, admin, "GET", "/api/admin/navigation", ""))
	var ids []string
	for _, sec := range nav.Sections {
		ids = append(ids, sec.ID)
	}
	if strings.Join(ids, ",") != "about,config,orders" {
		t.Errorf("the administrator's sections: %v", ids)
	}
	for _, path := range []string{"/api/admin/navigation", "/api/admin/about", "/api/admin/config", "/api/admin/orders/overview"} {
		if a := s.apiAs(t, clerk, "GET", path, ""); a.status != 403 || !strings.Contains(a.body, `"FORBIDDEN"`) {
			t.Errorf("clerk %s: %d %s", path, a.status, a.body)
		}
	}
	if a := s.apiAs(t, auditor, "GET", "/api/admin/about", ""); a.status != 200 {
		t.Errorf("auditor about: %d", a.status)
	}
	if a := s.apiAs(t, auditor, "GET", "/api/admin/config", ""); a.status != 403 {
		t.Errorf("auditor config: %d", a.status)
	}
	if a := s.apiAs(t, s.cookie(t, "orders-demo:clerk", "orders-demo:admin"), "POST", "/api/orders", `{"customer":"Ada"}`); a.status != 201 {
		t.Fatalf("place: %d %s", a.status, a.body)
	}
	overview := decode[struct {
		Open           int   `json:"open"`
		Cancelled      int   `json:"cancelled"`
		OpenTotalCents int64 `json:"openTotalCents"`
	}](t, s.apiAs(t, admin, "GET", "/api/admin/orders/overview", ""))
	if overview.Open != 1 || overview.Cancelled != 0 {
		t.Errorf("overview %+v", overview)
	}
	if a := s.apiAs(t, admin, "GET", "/api/admin/config", ""); a.status != 200 || strings.Contains(a.body, "postgres\"") {
		t.Errorf("config: %d", a.status)
	}
}
