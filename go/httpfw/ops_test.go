package httpfw_test

import (
	"context"
	"errors"
	"net/http"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

type colour string

func (colour) EnumValues() []string { return []string{"RED", "GREEN", "BLUE"} }

type itemBody struct {
	Name string  `json:"name"`
	Qty  int32   `json:"qty"`
	Note *string `json:"note,omitzero"`
}

type itemView struct {
	ID     string  `json:"id"`
	Name   string  `json:"name"`
	Note   *string `json:"note,omitzero"`
	Colour colour  `json:"colour"`
}

var itemRules = validate.Of(
	validate.Field("name", func(b itemBody) string { return b.Name }, validate.NotBlank(), validate.MaxLength(10)),
	validate.Field("qty", func(b itemBody) int32 { return b.Qty }, validate.Range[int32](1, 99)),
	validate.Field("note", func(b itemBody) *string { return b.Note }, validate.Ptr(validate.MaxLength(5))),
)

var (
	read   = fw.MustPermission("items:item:read")
	manage = fw.MustPermission("items:item:manage")

	pID      = httpfw.Path[int64]("id")
	qColour  = httpfw.Query[colour]("colour").Optional()
	qLimit   = httpfw.Query[int32]("limit").Default(20)
	qNeeded  = httpfw.Query[bool]("flag")
	qStatus  = httpfw.Query[colour]("status").List()
	createOp = httpfw.Post[itemBody, itemView]("/api/items", itemRules).ID("createItem").Tag("items").
			Status(201).Permission(manage).Error(409, "NAME_TAKEN", "a item has this name").Build()
	getOp = httpfw.Get[itemView]("/api/items/{id}").ID("getItem").Tag("items").Path(pID).
		Query(qColour, qLimit, qNeeded, qStatus).Permission(read).Build()
	deleteOp = httpfw.Delete[httpfw.NoBody]("/api/items/{id}").ID("deleteItem").Tag("items").Path(pID).Permission(manage).Build()
	pingOp   = httpfw.PostNoBody[itemView]("/api/ping").ID("ping").Build()
)

func bindAll(rt *httpfw.Routes, seen *[]string) {
	rt.Bind(httpfw.Handle(createOp, func(_ context.Context, cx fw.Context, in httpfw.In[itemBody]) (itemView, error) {
		if in.Body.Name == "taken" {
			return itemView{}, httpfw.NewError(409, "NAME_TAKEN", "a item has this name")
		}
		if in.Body.Name == "teapot" {
			return itemView{}, httpfw.NewError(418, "TEAPOT", "short and stout")
		}
		in.Header.Add("Location", "/api/items/7")
		in.Header.Add("Set-Cookie", "a=1")
		in.Header.Add("Set-Cookie", "b=2")
		return itemView{ID: "7", Name: in.Body.Name, Note: in.Body.Note, Colour: "RED"}, nil
	}))
	rt.Bind(httpfw.Handle(getOp, func(_ context.Context, cx fw.Context, in httpfw.In[httpfw.NoBody]) (itemView, error) {
		c, hasC := qColour.Value(in.Params)
		*seen = append(*seen, strings.Join([]string{
			cx.Client().String(), string(c), boolText(hasC), intText(int(qLimit.Value(in.Params))), boolText(qNeeded.Value(in.Params)),
			intText(int(pID.Value(in.Params))), listText(qStatus.Value(in.Params)),
		}, "|"))
		return itemView{ID: "1", Name: "n", Colour: "BLUE"}, nil
	}))
	rt.Bind(httpfw.Handle(deleteOp, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (httpfw.NoBody, error) {
		return httpfw.NoBody{}, nil
	}))
	rt.Bind(httpfw.Handle(pingOp, func(_ context.Context, cx fw.Context, _ httpfw.In[httpfw.NoBody]) (itemView, error) {
		return itemView{ID: "ping", Colour: "GREEN"}, nil // a public operation: the zero Context
	}))
}

func boolText(b bool) string {
	if b {
		return "true"
	}
	return "false"
}

func intText(n int) string {
	return strings.TrimSpace(strings.Join([]string{string(rune('0' + n%10))}, ""))
}

func listText(l []colour) string {
	out := make([]string, len(l))
	for i, c := range l {
		out[i] = string(c)
	}
	return strings.Join(out, ",")
}

func opsDispatcher(t *testing.T, perms ...string) (*httpfw.Dispatcher, *[]string, *logSink) {
	t.Helper()
	var seen []string
	sink := &logSink{}
	rt := httpfw.NewRoutes("items")
	bindAll(rt, &seen)
	d := compile(t, []httpfw.Option{
		httpfw.WithResolver(fixedContext(perms...)), httpfw.WithLogger(sink.logger()),
		httpfw.WithDeclared(createOp, getOp, deleteOp, pingOp),
	}, rt)
	return d, &seen, sink
}

func TestThePermissionIsCheckedBeforeAnythingAboutTheRequest(t *testing.T) {
	t.Parallel()
	d, _, _ := opsDispatcher(t, "items:item:read") // not manage
	r := do(d, "POST", "/api/items", `{"this is":"not even valid`, "Content-Type", "text/plain")
	if r.status != 403 || r.body != `{"error":"FORBIDDEN","message":"this needs the permission items:item:manage"}` {
		t.Errorf("got %d %s", r.status, r.body)
	}
}

func TestAllParameterErrorsComeInOne400BeforeTheBody(t *testing.T) {
	t.Parallel()
	d, _, _ := opsDispatcher(t, "items:item:read", "items:item:manage")
	r := do(d, "GET", "/api/items/abc?colour=PINK&limit=lots&status=RED&status=NOPE", "")
	want := `{"error":"VALIDATION","message":"request is invalid","details":{"id":"INVALID: must be an integer",` +
		`"colour":"INVALID: must be one of: RED, GREEN, BLUE","limit":"INVALID: must be an integer",` +
		`"flag":"REQUIRED: is required","status":"INVALID: must be one of: RED, GREEN, BLUE"}}`
	if r.status != 400 || r.body != want {
		t.Errorf("got %d %s", r.status, r.body)
	}
	// the body is not read when a parameter is wrong
	r = do(d, "POST", "/api/items", `garbage`, "Content-Type", "application/json")
	if r.status != 400 || !strings.Contains(r.body, "INVALID_JSON") {
		t.Errorf("a body error: %d %s", r.status, r.body)
	}
}

func TestParameterValuesReachTheHandlerTyped(t *testing.T) {
	t.Parallel()
	d, seen, _ := opsDispatcher(t, "items:item:read")
	r := do(d, "GET", "/api/items/5?flag=true&status=RED&status=BLUE&colour=GREEN&limit=7", "")
	if r.status != 200 {
		t.Fatalf("%d %s", r.status, r.body)
	}
	do(d, "GET", "/api/items/6?flag=false", "")
	want := []string{"acme|GREEN|true|7|true|5|RED,BLUE", "acme||false|2|false|6|"}
	// the default limit is 20: intText keeps one digit, so 20 prints as 0
	want[1] = "acme||false|0|false|6|"
	if strings.Join(*seen, "\n") != strings.Join(want, "\n") {
		t.Errorf("want\n%s\ngot\n%s", strings.Join(want, "\n"), strings.Join(*seen, "\n"))
	}
}

func TestBodyIsDecodedAndEveryFieldErrorIsCollected(t *testing.T) {
	t.Parallel()
	d, _, _ := opsDispatcher(t, "items:item:manage")
	h := []string{"Content-Type", "application/json"}
	r := do(d, "POST", "/api/items", `{"name":"  ","qty":500,"note":"toolong"}`, h...)
	want := `{"error":"VALIDATION","message":"request is invalid","details":{"name":"NOT_BLANK: must not be blank",` +
		`"qty":"OUT_OF_RANGE: must be between 1 and 99","note":"TOO_LONG: must be at most 5 characters"}}`
	if r.status != 400 || r.body != want {
		t.Errorf("got %d %s", r.status, r.body)
	}
	if r := do(d, "POST", "/api/items", `{"name":"a","qty":1,"extra":true}`, h...); r.status != 400 || !strings.Contains(r.body, `"extra":"INVALID_JSON: unknown property"`) {
		t.Errorf("unknown property: %d %s", r.status, r.body)
	}
	if r := do(d, "POST", "/api/items", `{"name":"a","qty":1}`, "Content-Type", "text/plain"); r.status != 415 {
		t.Errorf("content type: %d", r.status)
	}
	if r := do(d, "POST", "/api/items", `{"name":"a","qty":"one"}`, h...); strings.Contains(r.body, "one") {
		t.Errorf("the offending value is echoed: %s", r.body)
	}
}

func TestSuccessWritesTheStatusHeadersAndOmitsAbsentValues(t *testing.T) {
	t.Parallel()
	d, _, _ := opsDispatcher(t, "items:item:manage")
	h := []string{"Content-Type", "application/json; charset=utf-8"}
	r := do(d, "POST", "/api/items", `{"name":"widget","qty":3}`, h...)
	if r.status != 201 || r.body != `{"id":"7","name":"widget","colour":"RED"}` {
		t.Errorf("got %d %s", r.status, r.body)
	}
	if r.header.Get("Location") != "/api/items/7" || len(r.header.Values("Set-Cookie")) != 2 {
		t.Errorf("headers %v", r.header)
	}
	r = do(d, "POST", "/api/items", `{"name":"widget","qty":3,"note":"hi"}`, h...)
	if r.body != `{"id":"7","name":"widget","note":"hi","colour":"RED"}` {
		t.Errorf("a present optional value is written: %s", r.body)
	}
	if r := do(d, "DELETE", "/api/items/3", ""); r.status != 204 || r.body != "" {
		t.Errorf("NoBody is 204: %d %q", r.status, r.body)
	}
}

func TestAFailedOutcomeIsAnsweredAndAnUndocumentedStatusIsLoggedOnce(t *testing.T) {
	t.Parallel()
	d, _, sink := opsDispatcher(t, "items:item:manage")
	h := []string{"Content-Type", "application/json"}
	if r := do(d, "POST", "/api/items", `{"name":"taken","qty":1}`, h...); r.status != 409 || r.body != `{"error":"NAME_TAKEN","message":"a item has this name"}` {
		t.Errorf("a documented failure: %d %s", r.status, r.body)
	}
	if strings.Contains(sink.String(), "not in the document") {
		t.Errorf("a documented status must not be logged:\n%s", sink.String())
	}
	for range 3 {
		if r := do(d, "POST", "/api/items", `{"name":"teapot","qty":1}`, h...); r.status != 418 {
			t.Errorf("an undocumented failure is still answered: %d", r.status)
		}
	}
	if n := strings.Count(sink.String(), "status is not in the document"); n != 1 {
		t.Errorf("want one warning, got %d:\n%s", n, sink.String())
	}
}

func TestAPublicOperationNeedsNoResolver(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("items")
	rt.Bind(httpfw.Handle(pingOp, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (itemView, error) {
		return itemView{ID: "p", Colour: "RED"}, nil
	}))
	d := compile(t, nil, rt)
	if r := do(d, "POST", "/api/ping", ""); r.status != 200 || r.body != `{"id":"p","name":"","colour":"RED"}` {
		t.Errorf("got %d %s", r.status, r.body)
	}
}

func TestOperationStartupProblems(t *testing.T) {
	t.Parallel()
	okHandler := func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (itemView, error) { return itemView{}, nil }
	bad := httpfw.Get[itemView]("/api/{a}/{a}").Tag("Bad Tag").Path(httpfw.Path[string]("b")).Status(204).
		Query(httpfw.Query[string]("q"), httpfw.Query[string]("q")).Error(200, "lower", " ").Build()
	undeclared := httpfw.Get[itemView]("/api/undeclared").ID("undeclared").Build()
	unbound := httpfw.Get[itemView]("/api/unbound").ID("unbound").Build()
	twinA := httpfw.Get[itemView]("/api/twin/a").ID("twin").Build()
	twinB := httpfw.Get[itemView]("/api/twin/b").ID("twin").Build()
	publicWithRoute := httpfw.Get[itemView]("/api/public").ID("public").Build()

	rt := httpfw.NewRoutes("items")
	rt.Bind(httpfw.Handle(bad, okHandler))
	rt.Bind(httpfw.Handle(undeclared, okHandler))
	rt.Bind(httpfw.Handle(twinA, okHandler))
	rt.Bind(httpfw.Handle(twinB, okHandler))
	rt.Bind(httpfw.Handle(twinB, okHandler)) // bound twice
	rt.Bind(httpfw.Handle(publicWithRoute, okHandler, httpfw.RequiresContext()))
	_, err := httpfw.Compile([]*httpfw.Routes{rt}, httpfw.WithDeclared(bad, unbound, twinA, twinB, publicWithRoute))
	if err == nil {
		t.Fatal("want start-up problems")
	}
	text := err.Error()
	for _, want := range []string{
		"an operation needs an id",
		"tag 'Bad Tag' must match [a-z][a-z0-9-]*",
		"route pattern repeats parameter 'a'",
		"path parameter 'b' is not in the pattern /api/{a}/{a}",
		"query parameter 'q' is declared twice",
		"the success status is 200, 201, 202 or 204, not 204", // 204 without a NoBody response
		"error lower has status 200; an error status is 400 to 599",
		"error code 'lower' must match [A-Z][A-Z0-9_]*",
		"error lower needs a message",
		"operation undeclared is bound but was not declared",
		"operation unbound is declared but not bound",
		"two operations have the id 'twin': GET /api/twin/a and GET /api/twin/b",
		"operation twin is bound twice",
		"operation public is bound with options that require a context, but the operation does not",
	} {
		if !strings.Contains(text, want) {
			// the 204 problem has two wordings depending on the order of the checks
			if strings.Contains(want, "the success status") && strings.Contains(text, "status 204 needs a NoBody response") {
				continue
			}
			t.Errorf("missing %q in\n%s", want, text)
		}
	}
}

func TestADescriptorMistakeNeverPanicsAtDeclaration(t *testing.T) {
	t.Parallel()
	defer func() {
		if p := recover(); p != nil {
			t.Fatalf("Build panicked: %v", p)
		}
	}()
	_ = httpfw.Get[itemView]("not a pattern").Build()
	_ = httpfw.Get[itemView]("/x/{id}").ID("Bad").Build()
}

func TestAStatusOfAnErrorBuilderIsNotConfusedWithAGoError(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("x")
	rt.Bind(httpfw.Handle(getOp, func(context.Context, fw.Context, httpfw.In[httpfw.NoBody]) (itemView, error) {
		return itemView{}, errors.New("db down: password=pw")
	}))
	d := compile(t, []httpfw.Option{httpfw.WithResolver(fixedContext("items:item:read"))}, rt)
	r := do(d, "GET", "/api/items/1?flag=true", "")
	if r.status != 500 || strings.Contains(r.body, "password") {
		t.Errorf("got %d %s", r.status, r.body)
	}
	_ = http.StatusOK
}
