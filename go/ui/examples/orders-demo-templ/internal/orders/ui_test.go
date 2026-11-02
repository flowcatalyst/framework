package orders_test

import (
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.org/x/net/html"
	"golang.org/x/net/html/atom"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/examples/orders-demo-templ/internal/orders"
	"github.com/flowcatalyst/framework/go/ui/views"
)

var now = time.Date(2026, 10, 16, 12, 0, 0, 0, time.UTC)

const xss = "<script>alert(1)</script><img src=x onerror=alert(2)>"

type rig struct {
	t       *testing.T
	store   *orders.Store
	handler http.Handler
}

func newRig(t *testing.T) *rig {
	t.Helper()
	a, err := assets.Load()
	if err != nil {
		t.Fatal(err)
	}
	v, err := views.New(a, views.WithTitleSuffix(" - Orders demo"), views.WithShell(orders.Shell))
	if err != nil {
		t.Fatal(err)
	}
	store := orders.Seeded(now)
	rt := httpfw.NewRoutes("orders-ui")
	orders.New(v, store, fwtest.NewClock(now)).Register(rt)
	d, err := httpfw.Compile([]*httpfw.Routes{rt})
	if err != nil {
		t.Fatal(err)
	}
	return &rig{t: t, store: store, handler: d}
}

type answer struct {
	*httptest.ResponseRecorder
	t *testing.T
}

func (a answer) body() string { return a.Body.String() }

func (a answer) doc() *html.Node {
	a.t.Helper()
	d, err := html.Parse(strings.NewReader(a.body()))
	if err != nil {
		a.t.Fatal(err)
	}
	return d
}

func (g *rig) do(method, target, body string, headers ...string) answer {
	g.t.Helper()
	var rd io.Reader
	if body != "" || method == http.MethodPost {
		rd = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, target, rd)
	for i := 0; i < len(headers); i += 2 {
		if strings.EqualFold(headers[i], "Host") {
			req.Host = headers[i+1]
			continue
		}
		req.Header.Set(headers[i], headers[i+1])
	}
	rec := httptest.NewRecorder()
	g.handler.ServeHTTP(rec, req)
	return answer{rec, g.t}
}

func (g *rig) get(target string, headers ...string) answer {
	return g.do("GET", target, "", headers...)
}

func (g *rig) datastarGet(target string) answer { return g.get(target, "Datastar-Request", "true") }

func form(pairs ...string) string {
	v := url.Values{}
	for i := 0; i < len(pairs); i += 2 {
		v.Set(pairs[i], pairs[i+1])
	}
	return v.Encode()
}

func (g *rig) post(path, body string, datastar bool) answer {
	h := []string{"Content-Type", "application/x-www-form-urlencoded"}
	if datastar {
		h = append(h, "Datastar-Request", "true")
	}
	return g.do("POST", path, body, h...)
}

func sel(t *testing.T, root *html.Node, selector string) []*html.Node {
	t.Helper()
	return selectAll(t, root, selector)
}

func count(t *testing.T, root *html.Node, selector string, want int) {
	t.Helper()
	if got := len(sel(t, root, selector)); got != want {
		t.Errorf("%q matched %d elements, want %d", selector, got, want)
	}
}

func text(t *testing.T, root *html.Node, selector string) string {
	t.Helper()
	return textOf(sel(t, root, selector)...)
}

func equal(t *testing.T, what, got, want string) {
	t.Helper()
	if got != want {
		t.Errorf("%s = %q, want %q", what, got, want)
	}
}

func contains(t *testing.T, what, got string, wants ...string) {
	t.Helper()
	for _, w := range wants {
		if !strings.Contains(got, w) {
			t.Errorf("%s does not contain %q:\n%s", what, w, got)
		}
	}
}

func lacks(t *testing.T, what, got string, nots ...string) {
	t.Helper()
	for _, w := range nots {
		if strings.Contains(got, w) {
			t.Errorf("%s contains %q:\n%s", what, w, got)
		}
	}
}

func status(t *testing.T, a answer, want int) {
	t.Helper()
	if a.Code != want {
		t.Fatalf("status = %d, want %d\n%s", a.Code, want, a.body())
	}
}

func listAll(s *orders.Store, q string) orders.Listing { return s.List(q, "", "created", true, 1, 100) }

func TestTheListIsAFullPageWithToolbarTableAndPager(t *testing.T) {
	g := newRig(t)
	r := g.get("/orders")
	status(t, r, 200)
	d := r.doc()
	equal(t, "title", text(t, d, "title"), "Orders - Orders demo")
	equal(t, "h1", text(t, d, "h1.fc-page-title"), "Orders")
	count(t, d, "form#orders-list input[type=search][name=q]", 1)
	count(t, d, "tbody#orders-rows tr", 10)
	contains(t, "sorted column", text(t, d, "th[aria-sort=descending]"), "Placed")
	equal(t, "page report", text(t, d, "nav.fc-paginator .fc-page-report"), "Showing 1 to 10 of 23 orders")
	count(t, d, "aside#fc-sidebar", 1)
	count(t, d, "dialog#new-order", 1)
	count(t, d, "dialog#cancel-order", 1)
	count(t, d, "aside#order-drawer[role=complementary]", 1)
}

func TestSearchStatusSortAndPagingComeFromTheQueryAndSurviveInEveryLink(t *testing.T) {
	g := newRig(t)
	d := g.get("/orders?q=a&status=PAID&sort=total&dir=asc&rows=5").doc()
	names := sel(t, d, "tbody#orders-rows tr td:first-child a")
	if len(names) == 0 {
		t.Fatal("no rows")
	}
	for _, a := range names {
		contains(t, "customer", strings.ToLower(textOf(a)), "a")
	}
	for _, c := range sel(t, d, "tbody#orders-rows tr td:nth-child(2)") {
		equal(t, "status cell", textOf(c), "Paid")
	}
	contains(t, "sorted column", text(t, d, "th[aria-sort=ascending]"), "Total")
	equal(t, "selected filter", attrOf(sel(t, d, "select#filter-status option[selected]")[0], "value"), "PAID")
	equal(t, "search box", attrOf(sel(t, d, "input[name=q]")[0], "value"), "a")
	sortLink := attrOf(sel(t, d, "th a")[0], "href")
	contains(t, "sort link", sortLink, "q=a", "status=PAID", "rows=5")
	contains(t, "clear", text(t, d, "a.fc-btn-text"), "Clear All")

	page2 := g.get("/orders?rows=5&page=2").doc()
	equal(t, "current page", text(t, page2, "nav.fc-paginator [aria-current=page]"), "2")
	count(t, page2, "tbody#orders-rows tr", 5)
	equal(t, "clamped page", text(t, g.get("/orders?page=999&rows=5").doc(), "nav.fc-paginator [aria-current=page]"), "5")
}

func TestAnEmptyResultShowsTheEmptyStateInsideTheTable(t *testing.T) {
	d := newRig(t).get("/orders?q=nobody-at-all").doc()
	contains(t, "empty state", text(t, d, "tbody#orders-rows .fc-empty"), "No orders match.")
	equal(t, "clear link", attrOf(sel(t, d, "tbody#orders-rows .fc-empty a")[0], "href"), "/orders")
}

func TestUnknownQueryValuesFallBackToDefaultsAndNeverBreakThePage(t *testing.T) {
	r := newRig(t).get("/orders?sort=%27%3B+DROP&dir=x&status=%3Cb%3E&rows=7&page=abc")
	status(t, r, 200)
	count(t, r.doc(), "tbody#orders-rows tr", 10)
}

func TestTheDrawerIsLoadedByTheServerAsAPatchAndAsAFullPageWithoutDatastar(t *testing.T) {
	g := newRig(t)
	sse := g.datastarGet("/orders/ord-0001").body()
	contains(t, "drawer stream", sse, "event: datastar-patch-signals", "selOrder", "ord-0001",
		"data: selector #order-drawer", "data: mode inner", "fc-drawer-title", "Ada Lovelace")

	full := g.get("/orders/ord-0001")
	equal(t, "h1", text(t, full.doc(), "h1.fc-page-title"), "Ada Lovelace")
	equal(t, "breadcrumb", text(t, full.doc(), "nav[aria-label=breadcrumb] [aria-current=page]"), "ord-0001")

	status(t, g.get("/orders/ord-9999"), 404)
	contains(t, "missing record stream", g.datastarGet("/orders/ord-9999").body(), "fc-flash-error")
}

// dialogFromStream is the HTML of the data: elements lines of a stream.
func dialogFromStream(t *testing.T, sse string) *html.Node {
	t.Helper()
	var b strings.Builder
	for line := range strings.Lines(sse) {
		if rest, ok := strings.CutPrefix(strings.TrimRight(line, "\r\n"), "data: elements "); ok {
			b.WriteString(rest)
		}
	}
	nodes, err := html.ParseFragment(strings.NewReader(b.String()), &html.Node{Type: html.ElementNode, Data: "body", DataAtom: atom.Body})
	if err != nil {
		t.Fatal(err)
	}
	root := &html.Node{Type: html.DocumentNode}
	for _, n := range nodes {
		root.AppendChild(n)
	}
	return root
}

func TestAValidationFailureAnswersWithTheFormAndItsErrorsNotAnErrorStatus(t *testing.T) {
	g := newRig(t)
	r := g.post("/orders", form("customer", "A", "amount", "-3", "status", "PENDING"), true)
	status(t, r, 200)
	sse := r.body()
	contains(t, "stream", sse, `data: elements <dialog id="new-order"`)
	d := dialogFromStream(t, sse)
	count(t, d, "#new-order-customer[aria-invalid=true][aria-describedby=new-order-customer-err]", 1)
	contains(t, "customer error", text(t, d, "#new-order-customer-err[role=alert]"), "at least 2")
	contains(t, "amount error", text(t, d, "#new-order-amount-err"), "greater than zero")
	count(t, d, "dialog#new-order[open]", 1)
	equal(t, "kept value", attrOf(sel(t, d, "#new-order-customer")[0], "value"), "A")
	if n := g.store.List("", "", "created", true, 1, 100).Total; n != 23 {
		t.Errorf("orders = %d, want 23", n)
	}
}

func TestANativePostWithErrorsRendersThePageAgainWithStatus422(t *testing.T) {
	r := newRig(t).post("/orders", form("customer", "", "amount", "x", "status", "PENDING"), false)
	status(t, r, 422)
	d := r.doc()
	count(t, d, "dialog#new-order[open]", 1)
	if text(t, d, "#new-order-customer-err") == "" {
		t.Error("no customer error")
	}
	equal(t, "h1", text(t, d, "h1.fc-page-title"), "Orders")
}

func TestACreateThroughDatastarReplacesTheDialogReloadsTheListAndFlashes(t *testing.T) {
	g := newRig(t)
	sse := g.post("/orders", form("customer", "Linus Torvalds", "amount", "1234.50", "status", "PAID"), true).body()
	if n := listAll(g.store, "linus").Total; n != 1 {
		t.Errorf("stored orders for linus = %d, want 1", n)
	}
	contains(t, "stream", sse, `data: elements <div id="orders-region">`, "Linus Torvalds", "1,234.50")
	// the dialog is replaced (leaves the top layer), never morphed
	contains(t, "dialog replace", sse, "data: selector #new-order", "data: mode replace")
	contains(t, "flash", sse, "data: selector #flash-stack", "Order created.")
	contains(t, "focus", sse, "document.getElementById(&#39;new-order-open&#39;)?.focus()")
}

func TestANativeCreateRedirectsAndTheFlashTravelsAsAFixedKey(t *testing.T) {
	g := newRig(t)
	r := g.post("/orders", form("customer", "Linus Torvalds", "amount", "10", "status", "PENDING"), false)
	status(t, r, 303)
	equal(t, "Location", r.Header().Get("Location"), "/orders?flash=created")
	equal(t, "flash", text(t, g.get("/orders?flash=created").doc(), "#flash-stack > .fc-flash .fc-flash-summary"), "Order created.")
	// text from a request is never a flash: an unknown key shows nothing
	count(t, g.get("/orders?flash="+url.QueryEscape(xss)).doc(), "#flash-stack > .fc-flash", 0)
}

func TestCancelAsksForConfirmationAndPatchesTheRowOnlyWhenAllowed(t *testing.T) {
	g := newRig(t)
	list := g.get("/orders").doc()
	count(t, list, "#row-ord-0001-act-menu [data-action$='/orders/ord-0001/cancel']", 1)
	ok := g.post("/orders/ord-0001/cancel", "", true)
	contains(t, "cancel stream", ok.body(), `data: elements <tr id="row-ord-0001"`, "Cancelled", "Order cancelled.")
	if o, _ := g.store.Find("ord-0001"); o.Status != "CANCELLED" {
		t.Errorf("status = %s, want CANCELLED", o.Status)
	}
	again := g.post("/orders/ord-0001/cancel", "", true).body()
	contains(t, "second cancel", again, "fc-flash-error")
	lacks(t, "second cancel", again, "<tr")
	equal(t, "Location", g.post("/orders/ord-0002/cancel", "", false).Header().Get("Location"), "/orders?flash=cancelled")
	equal(t, "Location", g.post("/orders/ord-0001/cancel", "", false).Header().Get("Location"), "/orders?flash=not-cancellable")
}

func TestACrossSitePostIsRefused(t *testing.T) {
	g := newRig(t)
	body := form("customer", "Mallory", "amount", "5", "status", "PAID")
	ct := []string{"Content-Type", "application/x-www-form-urlencoded"}
	status(t, g.do("POST", "/orders", body, append(ct, "Sec-Fetch-Site", "cross-site")...), 403)
	status(t, g.do("POST", "/orders", body, append(ct, "Origin", "https://evil.example", "Host", "app.example")...), 403)
	if n := listAll(g.store, "mallory").Total; n != 0 {
		t.Errorf("stored orders for mallory = %d, want 0", n)
	}
	status(t, g.do("POST", "/orders", body, append(ct, "Sec-Fetch-Site", "same-origin")...), 303)
}

func TestACustomerNameFromTheUserIsEscapedInTheListTheDrawerAndTheStream(t *testing.T) {
	g := newRig(t)
	o := g.store.Add(xss, "PENDING", 500, now)
	list := g.get("/orders?q=script").doc()
	count(t, list, "tbody#orders-rows script, tbody#orders-rows img", 0)
	equal(t, "name", text(t, list, "tbody#orders-rows td:first-child a"), xss)
	for _, n := range sel(t, list, "[data-action]") {
		if a := attrOf(n, "data-action"); !strings.HasPrefix(a, "/orders/ord-") {
			t.Errorf("data-action %q", a)
		}
	}
	drawer := g.datastarGet("/orders/" + o.ID).body()
	lacks(t, "drawer", drawer, "<script>", "<img src=x")
	equal(t, "h1", text(t, g.get("/orders/"+o.ID).doc(), "h1"), xss)
	created := g.post("/orders", form("customer", xss+" Inc", "amount", "9", "status", "PAID"), true).body()
	lacks(t, "created stream", created, "<script>", "<img src=x")
	failed := g.post("/orders", form("customer", xss, "amount", "0", "status", "PAID"), false).doc()
	count(t, failed, "script:not([src]), img", 0)
	equal(t, "kept value", attrOf(sel(t, failed, "#new-order-customer")[0], "value"), xss)
}

func TestTheRowLinksUseAPlainHrefWithTheIdInADataAttributeNotInTheExpression(t *testing.T) {
	d := newRig(t).get("/orders").doc()
	link := sel(t, d, "tbody#orders-rows td:first-child a")[0]
	equal(t, "href", attrOf(link, "href"), "/orders/ord-0001")
	equal(t, "data-id", attrOf(link, "data-id"), "ord-0001")
	click := attrOf(link, "data-on:click__prevent")
	equal(t, "click handler", click, orders.OpenOrder)
	lacks(t, "click handler", click, "ord-0001")
}
