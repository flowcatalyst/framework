package main

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"fmt"
	"io"
	"maps"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/examples/orders-demo/web"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
)

const demoToken = "demo-token-for-tests"

type site struct {
	base string
	run  *fw.Running
}

func startWeb(t *testing.T, extra map[string]string) *site {
	t.Helper()
	env := map[string]string{
		"APP_ENV":                          "test",
		"DATABASE_INSTANCES_MAIN_URL":      fwtest.NewDatabase(t).URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
		"HTTP_HOST":                        "127.0.0.1",
		"HTTP_PORT":                        "0",
		"ORDERS_DEMO_TOKEN":                demoToken,
	}
	maps.Copy(env, extra)
	app := newApp(fw.WithEnvironment(env), fw.WithConfigDir("config"), fw.WithOutput(io.Discard, io.Discard))
	d := declare(app)
	var srv *httpfw.Server
	inner := buildWeb(d, deps{})
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		s, err := inner(ctx, b)
		if hs, ok := s.(*httpfw.Server); ok {
			srv = hs
		}
		return s, err
	})
	if err != nil {
		t.Fatal(err)
	}
	if srv == nil {
		t.Fatal("no server was built")
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return &site{base: fmt.Sprintf("http://127.0.0.1:%d", srv.Port()), run: run}
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

func (s *site) api(t *testing.T, method, path, body string) answer {
	t.Helper()
	h := []string{"Authorization", "Bearer " + demoToken}
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

	if a := s.call(t, "GET", "/api/orders", ""); a.status != 401 || a.body != `{"error":"UNAUTHENTICATED","message":"a bearer token is required"}` {
		t.Errorf("without a token: %d %s", a.status, a.body)
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

func TestSessionEndpointsAnswer501UntilTheyAreBuilt(t *testing.T) {
	t.Parallel()
	s := startWeb(t, nil)
	for _, c := range []struct{ method, path string }{{"GET", "/auth/session"}, {"POST", "/auth/logout"}} {
		a := s.call(t, c.method, c.path, "")
		if a.status != 501 || !strings.Contains(a.body, `"NOT_IMPLEMENTED"`) {
			t.Errorf("%s %s: %d %s", c.method, c.path, a.status, a.body)
		}
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
	app := newApp(fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	d := declare(app)
	doc, problems := d.web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("the document cannot be written: %v", problems)
	}
	if !bytes.Equal(doc, web.OpenAPI()) {
		t.Fatal("web/openapi.json is stale: run `go run ./examples/orders-demo openapi --out examples/orders-demo/web/openapi.json` from go/")
	}
	vector, err := os.ReadFile(filepath.Join("..", "..", "..", "conformance", "openapi", "orders-demo.json"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(doc, vector) {
		t.Fatal("the document differs from conformance/openapi/orders-demo.json")
	}
}

func TestServingNeedsTheDemoToken(t *testing.T) {
	t.Parallel()
	env := map[string]string{
		"APP_ENV": "test", "DATABASE_INSTANCES_MAIN_URL": fwtest.NewDatabase(t).URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres", "DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
		"HTTP_PORT": "0",
	}
	app := newApp(fw.WithEnvironment(env), fw.WithConfigDir("config"), fw.WithOutput(io.Discard, io.Discard))
	_, err := app.Start(t.Context(), buildWeb(declare(app), deps{}))
	if err == nil || !strings.Contains(err.Error(), "orders.demo-token is required") {
		t.Fatalf("got %v", err)
	}
}

func fs_ReadFile(name string) ([]byte, error) { return os.ReadFile(filepath.Join("web", name)) }
