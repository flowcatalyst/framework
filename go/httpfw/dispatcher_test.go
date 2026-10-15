package httpfw_test

import (
	"errors"
	"net/http"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/usecase"
)

func echoRoute(name string) httpfw.HandlerFunc {
	return func(w http.ResponseWriter, _ *http.Request) error { return jsonBody(w, `{"route":"`+name+`"}`) }
}

func routes(t *testing.T) *httpfw.Dispatcher {
	t.Helper()
	rt := httpfw.NewRoutes("test")
	rt.Handle("GET /orders/new", echoRoute("new"))
	rt.Handle("GET /orders/{id}", func(w http.ResponseWriter, r *http.Request) error {
		return jsonBody(w, `{"id":"`+r.PathValue("id")+`"}`)
	})
	rt.Handle("POST /orders", echoRoute("create"))
	rt.Handle("GET /dir/", echoRoute("dir-with-slash"))
	rt.Handle("GET /dir", echoRoute("dir"))
	rt.Handle("GET /a/{x}/c", echoRoute("a-x-c"))
	rt.Handle("GET /a/b/{y}", echoRoute("a-b-y"))
	return compile(t, nil, rt)
}

func TestLiteralBeatsParameterAndParametersAreDecodedPerSegment(t *testing.T) {
	t.Parallel()
	d := routes(t)
	for _, c := range []struct{ target, body string }{
		{"/orders/new", `{"route":"new"}`},
		{"/orders/42", `{"id":"42"}`},
		{"/orders/a%20b", `{"id":"a b"}`},
		{"/orders/a%2Fb", `{"id":"a/b"}`},      // a decoded slash stays inside its segment
		{"/orders/%C3%A9", `{"id":"é"}`},       // UTF-8
		{"/%6Frders/7", `{"id":"7"}`},          // literal segments match after decoding
		{"/orders/new?x=1", `{"route":"new"}`}, // the query is not part of the path
	} {
		r := do(d, "GET", c.target, "")
		if r.status != 200 || r.body != c.body {
			t.Errorf("%s: want 200 %s, got %d %s", c.target, c.body, r.status, r.body)
		}
	}
}

func TestOverlappingPatternsWithNeitherMoreSpecificAreBothServed(t *testing.T) {
	t.Parallel()
	// ServeMux refuses this pair; the contract's router does not: the first difference decides
	d := routes(t)
	if r := do(d, "GET", "/a/b/c", ""); r.body != `{"route":"a-x-c"}` && r.body != `{"route":"a-b-y"}` {
		t.Fatalf("got %d %s", r.status, r.body)
	}
	if r := do(d, "GET", "/a/b/c", ""); r.body != `{"route":"a-b-y"}` {
		t.Errorf("the literal at the first differing position (b) should win: %s", r.body)
	}
	if r := do(d, "GET", "/a/z/c", ""); r.body != `{"route":"a-x-c"}` {
		t.Errorf("/a/z/c: %s", r.body)
	}
}

func TestTrailingSlashIsADifferentPathAndNothingRedirects(t *testing.T) {
	t.Parallel()
	d := routes(t)
	if r := do(d, "GET", "/dir/", ""); r.body != `{"route":"dir-with-slash"}` {
		t.Errorf("/dir/: %d %s", r.status, r.body)
	}
	if r := do(d, "GET", "/dir", ""); r.body != `{"route":"dir"}` {
		t.Errorf("/dir: %d %s", r.status, r.body)
	}
	for _, target := range []string{"/orders//7", "/orders/../orders/7", "/./orders/7"} {
		r := do(d, "GET", target, "")
		if r.status == 301 || r.status == 307 || r.status == 308 || r.header.Get("Location") != "" {
			t.Errorf("%s redirected: %d %s", target, r.status, r.header.Get("Location"))
		}
	}
	if r := do(d, "GET", "/nothing/", ""); r.status != 404 {
		t.Errorf("unknown path: %d", r.status)
	}
}

func TestNotFoundAndMethodNotAllowedAndOptions(t *testing.T) {
	t.Parallel()
	d := routes(t)
	r := do(d, "GET", "/nope", "")
	if r.status != 404 || r.body != `{"error":"NOT_FOUND","message":"no route for this path"}` {
		t.Errorf("404: %d %s", r.status, r.body)
	}
	if ct := r.header.Get("Content-Type"); ct != "application/json" {
		t.Errorf("content type %q", ct)
	}
	r = do(d, "DELETE", "/orders/42", "")
	if r.status != 405 || r.header.Get("Allow") != "GET, HEAD, OPTIONS" ||
		r.body != `{"error":"METHOD_NOT_ALLOWED","message":"method not allowed for this path"}` {
		t.Errorf("405: %d allow=%q %s", r.status, r.header.Get("Allow"), r.body)
	}
	r = do(d, "PUT", "/orders", "")
	if r.header.Get("Allow") != "OPTIONS, POST" {
		t.Errorf("a path without GET has no HEAD in Allow: %q", r.header.Get("Allow"))
	}
	r = do(d, "OPTIONS", "/orders", "")
	if r.status != 204 || r.header.Get("Allow") != "OPTIONS, POST" || r.body != "" {
		t.Errorf("OPTIONS: %d allow=%q body=%q", r.status, r.header.Get("Allow"), r.body)
	}
	if r := do(d, "OPTIONS", "/nope", ""); r.status != 404 {
		t.Errorf("OPTIONS on an unknown path: %d", r.status)
	}
}

func TestHeadIsAnsweredFromGetWithTheLengthAndNoBody(t *testing.T) {
	t.Parallel()
	d := routes(t)
	get := do(d, "GET", "/orders/42", "")
	head := do(d, "HEAD", "/orders/42", "")
	if head.status != 200 || head.body != "" {
		t.Fatalf("HEAD: %d %q", head.status, head.body)
	}
	_ = get
	rt := httpfw.NewRoutes("t")
	rt.Handle("GET /sized", func(w http.ResponseWriter, _ *http.Request) error {
		return httpfw.JSON(w, 200, map[string]string{"k": "v"})
	})
	d2 := compile(t, nil, rt)
	if h := do(d2, "HEAD", "/sized", ""); h.header.Get("Content-Length") != "9" || h.body != "" {
		t.Errorf("HEAD of an in-memory body keeps its length: %q body=%q", h.header.Get("Content-Length"), h.body)
	}
}

func TestMalformedPercentEscapeIsAValidationError(t *testing.T) {
	t.Parallel()
	d := routes(t)
	r := do(d, "GET", "/orders/%zz", "")
	if r.status != 400 || !strings.Contains(r.body, `"error":"VALIDATION"`) || !strings.Contains(r.body, "INVALID_ENCODING") {
		t.Errorf("got %d %s", r.status, r.body)
	}
	if r := do(d, "GET", "/orders/%ff", ""); r.status != 400 { // invalid UTF-8
		t.Errorf("invalid UTF-8: %d", r.status)
	}
}

func TestDuplicateRoutesAreStartupProblemsNamingBothRegistrants(t *testing.T) {
	t.Parallel()
	a, b := httpfw.NewRoutes("alpha"), httpfw.NewRoutes("beta")
	a.Handle("GET /x/{id}", echoRoute("a"))
	b.Handle("GET /x/{name}", echoRoute("b")) // parameter names do not make a different shape
	b.Handle("GET /ok", echoRoute("ok"))
	b.Handle("BREW /teapot", echoRoute("t"))
	_, err := httpfw.Compile([]*httpfw.Routes{a, b})
	se, ok := errors.AsType[*fw.StartupError](err)
	if !ok {
		t.Fatalf("want a StartupError, got %v", err)
	}
	text := se.Error()
	for _, want := range []string{"duplicate route: GET /x/{name} (module 'beta') collides with GET /x/{id} (module 'alpha')", "BREW"} {
		if !strings.Contains(text, want) {
			t.Errorf("missing %q in\n%s", want, text)
		}
	}
	if len(se.Problems) != 2 {
		t.Errorf("want 2 problems (collected, not just the first), got %d", len(se.Problems))
	}
}

func TestARouteThatRequiresAContextWithoutAResolverFailsStartup(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("orders")
	rt.HandleContext("GET /secret", func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error { return nil })
	_, err := httpfw.Compile([]*httpfw.Routes{rt})
	if err == nil || !strings.Contains(err.Error(), "route GET /secret requires a context but no ContextResolver is provided") {
		t.Fatalf("got %v", err)
	}
}

func TestMiddlewareOrderAndShortCircuit(t *testing.T) {
	t.Parallel()
	var trace []string
	mark := func(name string) httpfw.Middleware {
		return func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) error {
				trace = append(trace, name+">")
				err := next(w, r)
				trace = append(trace, "<"+name)
				return err
			}
		}
	}
	rt := httpfw.NewRoutes("t")
	rt.Use(mark("g1"))
	rt.Use(mark("g2"))
	rt.Handle("GET /x", func(w http.ResponseWriter, r *http.Request) error {
		trace = append(trace, "handler")
		return nil
	}, httpfw.With(mark("r1"), mark("r2")))
	rt.Handle("GET /blocked", echoRoute("never"), httpfw.With(func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			return httpfw.Forbidden("NOPE", "stopped by middleware")
		}
	}))
	d := compile(t, nil, rt)
	do(d, "GET", "/x", "")
	want := "g1> g2> r1> r2> handler <r2 <r1 <g2 <g1"
	if got := strings.Join(trace, " "); got != want {
		t.Errorf("order\n want %s\n  got %s", want, got)
	}
	if r := do(d, "GET", "/blocked", ""); r.status != 403 || !strings.Contains(r.body, `"NOPE"`) {
		t.Errorf("short circuit: %d %s", r.status, r.body)
	}
}

func TestEveryRowOfTheErrorTable(t *testing.T) {
	t.Parallel()
	sink := &logSink{}
	rt := httpfw.NewRoutes("t")
	rt.Handle("GET /boom", func(http.ResponseWriter, *http.Request) error {
		return errors.New("secret internal detail: password=hunter2")
	})
	rt.Handle("GET /panic", func(http.ResponseWriter, *http.Request) error { panic("kaboom: token=abc") })
	rt.Handle("GET /status", func(http.ResponseWriter, *http.Request) error {
		return httpfw.NewError(409, "CONFLICTING", "in conflict", httpfw.Detail{Key: "b", Value: "2"}, httpfw.Detail{Key: "a", Value: "1"})
	})
	rt.Handle("GET /wrapped", func(http.ResponseWriter, *http.Request) error {
		return errors.Join(errors.New("while loading"), httpfw.NotFound("no such thing"))
	})
	rt.Handle("POST /body", func(w http.ResponseWriter, r *http.Request) error {
		_, err := httpfw.DecodeJSON[struct {
			A int `json:"a"`
		}](r)
		return err
	})
	rt.Handle("GET /usecase", func(http.ResponseWriter, *http.Request) error {
		return usecase.NotFound(usecase.MustErrorCode("ORDER_NOT_FOUND"), "There is no such order.")
	})
	d := compile(t, []httpfw.Option{httpfw.WithLogger(sink.logger()), httpfw.WithMaxBodyBytes(10)}, rt)

	cases := []struct {
		method, target, body string
		headers              []string
		status               int
		want                 string
	}{
		{"GET", "/boom", "", nil, 500, `{"error":"INTERNAL","message":"internal error"}`},
		{"GET", "/panic", "", nil, 500, `{"error":"INTERNAL","message":"internal error"}`},
		{"GET", "/status", "", nil, 409, `{"error":"CONFLICTING","message":"in conflict","details":{"b":"2","a":"1"}}`},
		{"GET", "/wrapped", "", nil, 404, `{"error":"NOT_FOUND","message":"no such thing"}`},
		{"GET", "/usecase", "", nil, 404, `{"error":"ORDER_NOT_FOUND","message":"There is no such order."}`},
		{"POST", "/body", `{"a":1}`, []string{"Content-Type", "text/plain"}, 415, `{"error":"UNSUPPORTED_MEDIA_TYPE","message":"content type must be application/json"}`},
		{"POST", "/body", `{"a":"x"}`, []string{"Content-Type", "application/json"}, 400, `{"error":"VALIDATION","message":"request is invalid","details":{"a":"INVALID_JSON: wrong type or format"}}`},
		{"POST", "/body", `{"b":1}`, []string{"Content-Type", "application/json"}, 400, `{"error":"VALIDATION","message":"request is invalid","details":{"b":"INVALID_JSON: unknown property"}}`},
		{"POST", "/body", `{"a":1,"a":2}`, []string{"Content-Type", "application/json"}, 413, `{"error":"PAYLOAD_TOO_LARGE","message":"request body is too large"}`},
		{"POST", "/body", `{"a":`, []string{"Content-Type", "application/json"}, 400, `{"error":"VALIDATION","message":"request is invalid","details":{"a":"INVALID_JSON: unexpected end of input"}}`},
		{"POST", "/body", `{"a"`, []string{"Content-Type", "application/json"}, 400, `{"error":"VALIDATION","message":"request is invalid","details":{"a":"INVALID_JSON: unexpected end of input"}}`},
	}
	for _, c := range cases {
		r := do(d, c.method, c.target, c.body, c.headers...)
		if r.status != c.status || r.body != c.want {
			t.Errorf("%s %s %q: want %d %s\n got %d %s", c.method, c.target, c.body, c.status, c.want, r.status, r.body)
		}
		if strings.Contains(r.body, "hunter2") || strings.Contains(r.body, "abc") {
			t.Errorf("%s: the cause leaked into the response: %s", c.target, r.body)
		}
		if r.header.Get("X-Content-Type-Options") != "nosniff" || r.header.Get("X-Correlation-Id") == "" {
			t.Errorf("%s: every response carries nosniff and a correlation id, got %v", c.target, r.header)
		}
	}
	logs := sink.String()
	if !strings.Contains(logs, "hunter2") || !strings.Contains(logs, "kaboom") {
		t.Errorf("the cause of a 500 is logged:\n%s", logs)
	}
}

func TestADuplicateMemberIsRefusedAndNeverEchoed(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("t")
	rt.Handle("POST /body", func(w http.ResponseWriter, r *http.Request) error {
		_, err := httpfw.DecodeJSON[struct {
			A string `json:"a"`
		}](r)
		return err
	})
	d := compile(t, nil, rt)
	r := do(d, "POST", "/body", `{"a":"sensitive-1","a":"sensitive-2"}`, "Content-Type", "application/json")
	if r.status != 400 || strings.Contains(r.body, "sensitive") {
		t.Errorf("got %d %s", r.status, r.body)
	}
	// member names are case sensitive
	if r := do(d, "POST", "/body", `{"A":"x"}`, "Content-Type", "application/json"); r.status != 400 {
		t.Errorf("a wrong-case member name: %d %s", r.status, r.body)
	}
}

func TestBodyOverTheLimitIs413ByDeclaredLengthAndWhileReading(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("t")
	rt.Handle("POST /sink", func(w http.ResponseWriter, r *http.Request) error {
		_, err := httpfw.DecodeJSON[map[string]any](r)
		if err != nil {
			return err
		}
		return jsonBody(w, `{}`)
	})
	rt.Handle("POST /noread", func(w http.ResponseWriter, _ *http.Request) error { return jsonBody(w, `{}`) })
	d := compile(t, []httpfw.Option{httpfw.WithMaxBodyBytes(16)}, rt)
	big := `{"k":"` + strings.Repeat("x", 40) + `"}`
	if r := do(d, "POST", "/sink", big, "Content-Type", "application/json"); r.status != 413 {
		t.Errorf("declared length: %d %s", r.status, r.body)
	}
	// an undeclared length (chunked): counted while reading
	req := request("POST", "/sink", big, "Content-Type", "application/json")
	req.ContentLength = -1
	rec := newRecorder()
	d.ServeHTTP(rec, req)
	if rec.Code != 413 {
		t.Errorf("while reading: %d %s", rec.Code, rec.Body.String())
	}
	if r := do(d, "POST", "/sink", `{"k":1}`, "Content-Type", "application/json"); r.status != 200 {
		t.Errorf("within the limit: %d %s", r.status, r.body)
	}
	// the limit is inclusive: exactly 16 bytes pass, 17 do not
	sixteen := `{"k":"` + strings.Repeat("a", 8) + `"}`
	if len(sixteen) != 16 {
		t.Fatalf("test data: %d", len(sixteen))
	}
	if r := do(d, "POST", "/sink", sixteen, "Content-Type", "application/json"); r.status != 200 {
		t.Errorf("exactly the limit by declared length: %d %s", r.status, r.body)
	}
	if r := do(d, "POST", "/sink", sixteen+" ", "Content-Type", "application/json"); r.status != 413 {
		t.Errorf("one byte over by declared length: %d %s", r.status, r.body)
	}
	// a declared length over the limit is refused even by a route that never reads the body
	if r := do(d, "POST", "/noread", sixteen, "Content-Type", "application/json"); r.status != 200 {
		t.Errorf("a route that ignores the body, at the limit: %d", r.status)
	}
	if r := do(d, "POST", "/noread", sixteen+" ", "Content-Type", "application/json"); r.status != 413 {
		t.Errorf("a route that ignores the body, one byte over: %d", r.status)
	}
	chunked := func(body string) int {
		req := request("POST", "/sink", body, "Content-Type", "application/json")
		req.ContentLength = -1
		rec := newRecorder()
		d.ServeHTTP(rec, req)
		return rec.Code
	}
	if code := chunked(sixteen); code != 200 {
		t.Errorf("exactly the limit while reading: %d", code)
	}
	if code := chunked(sixteen + " "); code != 413 {
		t.Errorf("one byte over while reading: %d", code)
	}
}

func TestCorrelationId(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("t")
	rt.HandleContext("GET /cx", func(w http.ResponseWriter, r *http.Request, cx fw.Context) error {
		return jsonBody(w, `{"request":"`+httpfw.CorrelationID(r).String()+`","context":"`+cx.Correlation().String()+`"}`)
	})
	rt.Handle("GET /plain", func(w http.ResponseWriter, r *http.Request) error { return jsonBody(w, `{}`) })
	d := compile(t, []httpfw.Option{httpfw.WithResolver(fixedContext())}, rt)

	r := do(d, "GET", "/cx", "", "X-Correlation-Id", "abc.DEF_1-2")
	if got := r.header.Get("X-Correlation-Id"); got != "abc.DEF_1-2" {
		t.Errorf("a valid supplied id is used and echoed, got %q", got)
	}
	if !strings.Contains(r.body, `"request":"abc.DEF_1-2","context":"abc.DEF_1-2"`) {
		t.Errorf("the resolved Context carries the request's id: %s", r.body)
	}
	for _, bad := range []string{"has space", "semi;colon", strings.Repeat("a", 101), ""} {
		r := do(d, "GET", "/plain", "", "X-Correlation-Id", bad)
		got := r.header.Get("X-Correlation-Id")
		if r.status != 200 || got == "" || got == bad {
			t.Errorf("an invalid id %q is replaced, not rejected: %d %q", bad, r.status, got)
		}
	}
	// echoed on errors and 404s
	if got := do(d, "GET", "/missing", "", "X-Correlation-Id", "from-client").header.Get("X-Correlation-Id"); got != "from-client" {
		t.Errorf("a 404 echoes the id: %q", got)
	}
	if a, b := do(d, "GET", "/plain", "").header.Get("X-Correlation-Id"), do(d, "GET", "/plain", "").header.Get("X-Correlation-Id"); a == b {
		t.Errorf("generated ids differ: %q %q", a, b)
	}
}

func TestContextResolutionRunsAfterGlobalAndBeforeRouteMiddleware(t *testing.T) {
	t.Parallel()
	var trace []string
	rt := httpfw.NewRoutes("t")
	rt.Use(func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			trace = append(trace, "global")
			return next(w, r)
		}
	})
	rt.HandleContext("GET /x", func(w http.ResponseWriter, r *http.Request, cx fw.Context) error {
		trace = append(trace, "handler:"+cx.Client().String())
		return nil
	}, httpfw.With(func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			trace = append(trace, "route:"+httpfw.ContextOf(r).Client().String())
			return next(w, r)
		}
	}))
	resolver := resolverFunc(func(r *http.Request) (fw.Context, error) {
		trace = append(trace, "resolve")
		return fixedContext()(r)
	})
	d := compile(t, []httpfw.Option{httpfw.WithResolver(resolver)}, rt)
	do(d, "GET", "/x", "")
	if got, want := strings.Join(trace, " "), "global resolve route:acme handler:acme"; got != want {
		t.Errorf("want %s, got %s", want, got)
	}
}

func TestResolverFailureIsAnsweredWithItsEnvelope(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("t")
	rt.HandleContext("GET /x", func(http.ResponseWriter, *http.Request, fw.Context) error { return nil })
	d := compile(t, []httpfw.Option{httpfw.WithResolver(resolverFunc(func(*http.Request) (fw.Context, error) {
		return fw.Context{}, httpfw.Unauthenticated("sign in")
	}))}, rt)
	r := do(d, "GET", "/x", "")
	if r.status != 401 || r.body != `{"error":"UNAUTHENTICATED","message":"sign in"}` {
		t.Errorf("got %d %s", r.status, r.body)
	}
}

func TestRoutesCannotBeRegisteredAfterCompile(t *testing.T) {
	t.Parallel()
	rt := httpfw.NewRoutes("t")
	rt.Handle("GET /x", echoRoute("x"))
	compile(t, nil, rt)
	defer func() {
		if recover() == nil {
			t.Error("registering after the server was built must panic")
		}
	}()
	rt.Handle("GET /late", echoRoute("late"))
}
