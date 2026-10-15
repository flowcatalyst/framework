package httpclient_test

import (
	"bytes"
	"compress/flate"
	"compress/gzip"
	"compress/zlib"
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpclient"
)

type order struct {
	ID    string `json:"id"`
	Lines int    `json:"lines"`
}

type attempts struct {
	mu   sync.Mutex
	list []string
}

func (a *attempts) Attempted(x httpclient.Attempt) {
	a.mu.Lock()
	defer a.mu.Unlock()
	out := fmt.Sprint(x.Status)
	if x.Failure != 0 {
		out = strings.ToUpper(x.Failure.String())
	}
	a.list = append(a.list, fmt.Sprintf("%s %s %s %s #%d", x.Method, x.PathTemplate, out, x.Service, x.Number))
}

func (a *attempts) get() []string {
	a.mu.Lock()
	defer a.mu.Unlock()
	return append([]string(nil), a.list...)
}

// harness is an engine whose waits are recorded, not slept.
type harness struct {
	t      *testing.T
	f      *fakeServer
	engine *httpclient.Engine
	seen   *attempts
	clock  *fwtest.Clock
	mu     sync.Mutex
	waits  []time.Duration
}

func newHarness(t *testing.T) *harness {
	t.Helper()
	h := &harness{t: t, f: newFake(t), seen: &attempts{}, clock: fwtest.NewClock(time.Date(2026, 10, 15, 12, 0, 0, 0, time.UTC))}
	e, err := httpclient.NewEngine(httpclient.WithClock(h.clock), httpclient.WithSleep(func(ctx context.Context, d time.Duration) error {
		h.mu.Lock()
		h.waits = append(h.waits, d)
		h.mu.Unlock()
		return ctx.Err()
	}))
	if err != nil {
		t.Fatal(err)
	}
	h.engine = e
	t.Cleanup(e.Close)
	return h
}

func (h *harness) waited() []time.Duration {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]time.Duration(nil), h.waits...)
}

func (h *harness) service(opts ...httpclient.ServiceOption) *httpclient.Service {
	h.t.Helper()
	all := append([]httpclient.ServiceOption{httpclient.WithObserver(h.seen)}, opts...)
	s, err := h.engine.Service("orders", h.f.base("/v1"), all...)
	if err != nil {
		h.t.Fatal(err)
	}
	return s
}

func send(t *testing.T, r *httpclient.Request) *httpclient.Response {
	t.Helper()
	resp, err := r.Send(t.Context())
	if err != nil {
		t.Fatalf("Send: %v", err)
	}
	return resp
}

func kindOf(err error) httpclient.Kind {
	if he, ok := errors.AsType[*httpclient.Error](err); ok {
		return he.Kind
	}
	return 0
}

func TestGetsJSONWithEncodedPathAndQueryUnderTheBasePath(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/orders/a%2Fb%20c%3F%23", ok(`{"id":"x","lines":2,"extra":true}`))
	got, err := httpclient.ReceiveJSON[order](t.Context(), h.service().Get("/orders/{id}").PathParam("id", "a/b c?#").Query("q", "x&y=z").Query("q", "é"))
	if err != nil || got != (order{"x", 2}) {
		t.Fatalf("got %+v, %v", got, err)
	}
	r := h.f.requests()[0]
	if r.rawQuery != "q=x%26y%3Dz&q=%C3%A9" {
		t.Fatalf("raw query %q", r.rawQuery)
	}
	if r.requestURI != "/v1/orders/a%2Fb%20c%3F%23?q=x%26y%3Dz&q=%C3%A9" {
		t.Fatalf("request target %q", r.requestURI)
	}
	if r.header.Get("User-Agent") != "flowcatalyst-framework-httpclient" {
		t.Fatalf("User-Agent %q", r.header.Get("User-Agent"))
	}
	if want := []string{"GET /orders/{id} 200 orders #1"}; fmt.Sprint(h.seen.get()) != fmt.Sprint(want) {
		t.Fatalf("attempts %v", h.seen.get())
	}
}

func TestUnreservedCharactersStayLiteralInAPathParam(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/orders/a~b-c_d.e", ok(`{"id":"t"}`))
	if got, err := httpclient.ReceiveJSON[order](t.Context(), h.service().Get("/orders/{id}").PathParam("id", "a~b-c_d.e")); err != nil || got.ID != "t" {
		t.Fatalf("got %+v %v", got, err)
	}
	if uri := h.f.requests()[0].requestURI; uri != "/v1/orders/a~b-c_d.e" {
		t.Fatalf("request target %q", uri)
	}
}

func TestAnEncodedUnicodePathParamAndABaseURLWithTrailingSlash(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/orders/%C3%A9", ok(`{}`))
	s, err := h.engine.Service("orders", h.f.base("/v1/"))
	if err != nil {
		t.Fatal(err)
	}
	if resp := send(t, s.Get("/orders/{id}").PathParam("id", "é")); resp.Status() != 200 {
		t.Fatalf("status %d", resp.Status())
	}
}

func TestPostsJSONAndDecodesGenericTypes(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/orders", status(201, `[{"id":"o1","lines":1}]`))
	got, err := httpclient.ReceiveJSON[[]order](t.Context(), h.service().Post("/orders").JSON(order{"o1", 1}))
	if err != nil || len(got) != 1 || got[0] != (order{"o1", 1}) {
		t.Fatalf("got %+v, %v", got, err)
	}
	r := h.f.requests()[0]
	if r.method != "POST" || r.body != `{"id":"o1","lines":1}` || r.header.Get("Content-Type") != "application/json" {
		t.Fatalf("request %+v", r)
	}
}

func TestHeadFormAndRawBodies(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/ping", ok(`{}`))
	h.f.on("/v1/form", status(204, ""))
	h.f.on("/v1/raw", status(204, ""))
	s := h.service()
	if send(t, s.Head("/ping")).Status() != 200 {
		t.Fatal("HEAD")
	}
	if send(t, s.Post("/form").Form("a", "1 2", "b", "&", "c", "*~")).Status() != 204 {
		t.Fatal("form")
	}
	r := h.f.requests()[1]
	if r.body != "a=1+2&b=%26&c=*%7E" || r.header.Get("Content-Type") != "application/x-www-form-urlencoded" {
		t.Fatalf("form request %+v", r)
	}
	send(t, s.Put("/raw").Body([]byte{1, 2, 3}, "application/octet-stream"))
	if r := h.f.requests()[2]; r.body != "\x01\x02\x03" || r.header.Get("Content-Type") != "application/octet-stream" {
		t.Fatalf("raw request %+v", r)
	}
}

func TestPathsThatCouldLeaveTheServiceAreRefusedBeforeAnythingIsSent(t *testing.T) {
	h := newHarness(t)
	s := h.service()
	for _, bad := range []string{
		"orders", "http://evil.example.com/x", "//evil.example.com/x", "/a//b", "/a?b=1", "/a#f",
		"/../admin", "/a/../b", "/a/./b", "/a\\b", "/a b", "/a/%zz", "/{x}/{x}", "/a/{bad name}",
	} {
		if _, err := s.Get(bad).Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
			t.Errorf("path %q: err = %v, want ErrInvalidRequest", bad, err)
		}
	}
	for _, bad := range []string{"", ".", ".."} {
		if _, err := s.Get("/a/{id}").PathParam("id", bad).Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
			t.Errorf("param %q: err = %v", bad, err)
		}
	}
	if _, err := s.Get("/a/{id}").PathParam("other", "x").Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
		t.Errorf("a parameter the path does not have: %v", err)
	}
	if _, err := s.Get("/a/{id}").Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
		t.Errorf("an unfilled placeholder: %v", err)
	}
	for _, good := range []string{"/", "/a/%2F", "/a/b-c_d.e~f!$&'()*+,;=:@", "/{x}", "/a/{x}.json"} {
		req := s.Get(good)
		if strings.Contains(good, "{x}") {
			req = req.PathParam("x", "v")
		}
		if _, err := req.Send(t.Context()); errors.Is(err, httpclient.ErrInvalidRequest) {
			t.Errorf("path %q refused: %v", good, err)
		}
	}
	// only the "good" paths could send anything
	for _, r := range h.f.requests() {
		if !strings.HasPrefix(r.requestURI, "/v1/") && r.requestURI != "/v1" {
			t.Errorf("a request left the base path: %q", r.requestURI)
		}
	}
	if n := len(h.f.requests()); n != 5 {
		t.Errorf("%d requests sent, want only the 5 good paths", n)
	}
}

func TestHeaderInjectionAndFramingHeadersAreRefused(t *testing.T) {
	h := newHarness(t)
	s := h.service()
	for _, c := range [][2]string{
		{"X-A", "v\r\nX-Evil: 1"},
		{"X-A", "v\nx"},
		{"X-A", "v\x00"},
		{"Bad Name", "v"},
		{"", "v"},
		{"Host", "x"},
		{"content-length", "x"},
		{"Transfer-Encoding", "x"},
		{"Connection", "x"},
	} {
		if _, err := s.Get("/a").Header(c[0], c[1]).Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
			t.Errorf("header %q: err = %v", c[0], err)
		}
	}
	if _, err := s.Post("/a").IdempotencyKey("k\r\n").Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
		t.Errorf("idempotency key: %v", err)
	}
	if _, err := s.Post("/a").IdempotencyKey("").Send(t.Context()); !errors.Is(err, httpclient.ErrInvalidRequest) {
		t.Errorf("empty idempotency key: %v", err)
	}
	if _, err := h.engine.Service("orders", h.f.base("/"), httpclient.DefaultHeader("X", "a\r\nb")); err == nil {
		t.Error("a default header with CRLF accepted")
	}
	if _, err := s.WithHeaders(map[string]string{"X": "a\rb"}); err == nil {
		t.Error("WithHeaders with CR accepted")
	}
	if n := len(h.f.requests()); n != 0 {
		t.Errorf("%d requests sent", n)
	}
}

func TestServiceNamesBaseURLsAndOptionsAreChecked(t *testing.T) {
	h := newHarness(t)
	for _, bad := range []string{"/relative", "ftp://h/x", "https://user:pw@h.example/x", "https://h.example/x?q=1", "https://h.example/#f", "https://h.example/x?", "https:///x"} {
		u, err := url.Parse(bad)
		if err != nil {
			continue
		}
		if _, err := h.engine.Service("x", u); err == nil {
			t.Errorf("base URL %q accepted", bad)
		}
	}
	good, _ := url.Parse("https://h.example/api")
	for _, bad := range []string{"Bad", "", "a_b", "1a", strings.Repeat("a", 101)} {
		if _, err := h.engine.Service(bad, good); err == nil {
			t.Errorf("name %q accepted", bad)
		}
	}
	for _, o := range []httpclient.ServiceOption{
		httpclient.ConnectTimeout(0), httpclient.RequestTimeout(-1), httpclient.MaxResponseBytes(0), httpclient.MaxResponseBytes(1<<30 + 1),
		httpclient.Retry(httpclient.DefaultRetry().WithMaxAttempts(0)), httpclient.Retry(httpclient.DefaultRetry().WithMaxAttempts(11)),
		httpclient.Retry(httpclient.DefaultRetry().WithBackoff(time.Second, time.Millisecond)),
	} {
		if _, err := h.engine.Service("x", good, o); err == nil {
			t.Error("an option out of range was accepted")
		}
	}
	if _, err := h.engine.Service("x", good, httpclient.MaxResponseBytes(1<<30), httpclient.Retry(httpclient.NoRetry())); err != nil {
		t.Errorf("limits refused: %v", err)
	}
	for _, n := range []int{0, 1001} {
		if _, err := httpclient.NewEngine(httpclient.MaxConnectionsPerHost(n)); err == nil {
			t.Errorf("MaxConnectionsPerHost(%d) accepted", n)
		}
	}
}

func TestANonSuccessStatusIsAResponseAndReceiveJSONFailsWithTheEnvelopeCodeButNoBody(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/orders/1", status(409, `{"error":"TAKEN","message":"internal secret detail"}`))
	s := h.service()
	resp := send(t, s.Get("/orders/{id}").PathParam("id", "1"))
	if resp.Status() != 409 || resp.IsSuccess() {
		t.Fatalf("status %d", resp.Status())
	}
	_, err := httpclient.ReceiveJSON[order](t.Context(), s.Get("/orders/{id}").PathParam("id", "1"))
	he, ok := errors.AsType[*httpclient.Error](err)
	if !ok || he.Kind != httpclient.KindUnexpectedStatus || he.Status != 409 || he.Code != "TAKEN" {
		t.Fatalf("err = %#v", err)
	}
	if err == nil || !strings.Contains(err.Error(), "orders GET /orders/{id}") || strings.Contains(err.Error(), "secret") {
		t.Fatalf("message %q", err.Error())
	}
	// an error that is not the envelope's has no code
	h.f.on("/v1/odd", status(500, `{"error":"lower case","message":"x"}`))
	_, err = httpclient.ReceiveJSON[order](t.Context(), s.Get("/odd"))
	if he, _ := errors.AsType[*httpclient.Error](err); he == nil || he.Code != "" {
		t.Fatalf("code of a non-envelope: %#v", err)
	}
}

func TestABodyThatIsNotTheJSONAskedForIsADecodeFailureWithoutTheBody(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/x", ok("password=hunter2 not json"))
	_, err := httpclient.ReceiveJSON[order](t.Context(), h.service().Get("/x"))
	if err == nil || kindOf(err) != httpclient.KindDecode || strings.Contains(err.Error(), "hunter2") || errors.Unwrap(err) != nil {
		t.Fatalf("err = %v (kind %v, cause %v)", err, kindOf(err), errors.Unwrap(err))
	}
	// a body of the wrong shape, with a value in it
	h.f.on("/v1/y", ok(`{"id":12345,"lines":"hunter2"}`))
	_, err = httpclient.ReceiveJSON[order](t.Context(), h.service().Get("/y"))
	if err == nil || kindOf(err) != httpclient.KindDecode || strings.Contains(err.Error(), "hunter2") {
		t.Fatalf("err = %v", err)
	}
}

func TestADeclaredLengthOverTheLimitIsRefusedBeforeTheBodyIsRead(t *testing.T) {
	h := newHarness(t)
	release := make(chan struct{})
	h.f.srv.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Length", "100000000")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		select {
		case <-release:
		case <-r.Context().Done():
		}
	})
	defer close(release)
	s := h.service(httpclient.MaxResponseBytes(1000), httpclient.RequestTimeout(5*time.Second), httpclient.Retry(httpclient.NoRetry()))
	start := time.Now()
	_, err := s.Get("/stalled").Send(t.Context())
	if kindOf(err) != httpclient.KindResponseTooLarge || time.Since(start) > 2*time.Second {
		t.Fatalf("err = %v after %v: the body was waited for", err, time.Since(start))
	}
}

func TestOversizedResponsesAreRefusedByLengthAndWhileReading(t *testing.T) {
	h := newHarness(t)
	big := bytes.Repeat([]byte("x"), 5000)
	h.f.on("/v1/declared", answer{status: 200, body: big})
	h.f.on("/v1/chunked", answer{status: 200, body: big, chunked: true})
	h.f.on("/v1/exact", answer{status: 200, body: big[:1000]})
	s := h.service(httpclient.MaxResponseBytes(1000))
	for _, p := range []string{"/declared", "/chunked"} {
		if _, err := s.Get(p).Send(t.Context()); kindOf(err) != httpclient.KindResponseTooLarge {
			t.Errorf("%s: err = %v", p, err)
		}
	}
	if n := h.f.count("/v1/declared"); n != 1 {
		t.Errorf("a response that is too large was retried: %d requests", n)
	}
	if resp := send(t, s.Get("/exact")); len(resp.Bytes()) != 1000 {
		t.Errorf("an answer of exactly the limit: %d bytes", len(resp.Bytes()))
	}
}

func encodeWith(t *testing.T, kind string, plain []byte) []byte {
	t.Helper()
	var buf bytes.Buffer
	var w interface {
		Write([]byte) (int, error)
		Close() error
	}
	switch kind {
	case "gzip":
		w = gzip.NewWriter(&buf)
	case "deflate":
		w = zlib.NewWriter(&buf)
	case "raw-deflate":
		fw, err := flate.NewWriter(&buf, flate.DefaultCompression)
		if err != nil {
			t.Fatal(err)
		}
		w = fw
	default:
		t.Fatalf("unknown encoding %s", kind)
	}
	if _, err := w.Write(plain); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}
	return buf.Bytes()
}

func TestGzipAndDeflateAnswersAreDecodedAndTheSizeLimitHoldsForTheDecodedBody(t *testing.T) {
	h := newHarness(t)
	plain := []byte(`{"id":"zipped","lines":7}`)
	for _, enc := range []struct{ name, header string }{{"gzip", "gzip"}, {"deflate", "deflate"}, {"raw-deflate", "deflate"}} {
		h.f.on("/v1/"+enc.name, answer{status: 200, body: encodeWith(t, enc.name, plain), headers: map[string]string{"Content-Encoding": enc.header}})
		got, err := httpclient.ReceiveJSON[order](t.Context(), h.service().Get("/"+enc.name))
		if err != nil || got != (order{"zipped", 7}) {
			t.Errorf("%s: %+v, %v", enc.name, got, err)
		}
	}
	if ae := h.f.requests()[0].header.Get("Accept-Encoding"); ae != "gzip, deflate" {
		t.Errorf("Accept-Encoding %q", ae)
	}
	// a small compressed body that expands past the limit is refused
	bomb := encodeWith(t, "gzip", bytes.Repeat([]byte("a"), 100_000))
	h.f.on("/v1/bomb", answer{status: 200, body: bomb, headers: map[string]string{"Content-Encoding": "gzip"}})
	if _, err := h.service(httpclient.MaxResponseBytes(10_000)).Get("/bomb").Send(t.Context()); kindOf(err) != httpclient.KindResponseTooLarge {
		t.Errorf("a decompression bomb: %v", err)
	}
	h.f.on("/v1/bad", answer{status: 200, body: []byte("not gzip"), headers: map[string]string{"Content-Encoding": "gzip"}})
	if _, err := h.service().Get("/bad").Send(t.Context()); kindOf(err) != httpclient.KindIO {
		t.Errorf("a body that is not gzip: %v", err)
	}
}

func TestASlowAnswerTimesOutAndIdempotentRequestsAreRetried(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/slow", ok(`{}`).delayed(1500*time.Millisecond))
	s := h.service(httpclient.RequestTimeout(100 * time.Millisecond))
	start := time.Now()
	_, err := s.Get("/slow").Send(t.Context())
	if kindOf(err) != httpclient.KindTimeout {
		t.Fatalf("err = %v", err)
	}
	if time.Since(start) > 1400*time.Millisecond {
		t.Fatal("the attempts did not stop at their timeout")
	}
	got := h.seen.get()
	if len(got) != 3 {
		t.Fatalf("attempts %v, want 3", got)
	}
	for _, a := range got {
		if !strings.Contains(a, "TIMEOUT") {
			t.Fatalf("attempt %q is not a timeout", a)
		}
	}
	// per-request timeout overrides the service's
	h.seen.mu.Lock()
	h.seen.list = nil
	h.seen.mu.Unlock()
	h.f.on("/v1/fast", ok(`{}`))
	if resp := send(t, s.Get("/fast").Timeout(5*time.Second)); resp.Status() != 200 {
		t.Fatal("fast")
	}
}

func TestAConnectionFailureIsConnectAndIsRetriedForGet(t *testing.T) {
	h := newHarness(t)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	addr := ln.Addr().String()
	_ = ln.Close()
	u, _ := url.Parse("http://" + addr)
	s, err := h.engine.Service("down", u, httpclient.WithObserver(h.seen))
	if err != nil {
		t.Fatal(err)
	}
	_, err = s.Get("/x").Send(t.Context())
	if kindOf(err) != httpclient.KindConnect || err.Error() != "down GET /x: could not connect" {
		t.Fatalf("err = %v", err)
	}
	if want := "[GET /x CONNECT down #1 GET /x CONNECT down #2 GET /x CONNECT down #3]"; fmt.Sprint(h.seen.get()) != want {
		t.Fatalf("attempts %v", h.seen.get())
	}
	before := len(h.seen.get())
	if _, err := s.Post("/x").Send(t.Context()); err == nil {
		t.Fatal("POST to a closed port succeeded")
	}
	if len(h.seen.get())-before != 1 {
		t.Fatalf("a POST was retried: %v", h.seen.get())
	}
	// the cause carries no URL (and so no query string)
	_, err = s.Get("/x").Query("token", "SECRETQUERY").Send(t.Context())
	if strings.Contains(fmt.Sprint(err, errors.Unwrap(err)), "SECRETQUERY") {
		t.Fatalf("the query string leaked into the error: %v / %v", err, errors.Unwrap(err))
	}
}

func TestRedirectsAreReturnedNotFollowed(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/moved", status(302, "").with("Location", h.f.srv.URL+"/elsewhere"))
	h.f.on("/elsewhere", ok("{}"))
	s := h.service(httpclient.WithAuth(httpclient.Bearer(httpclient.Static("tok"))))
	resp := send(t, s.Get("/moved"))
	if resp.Status() != 302 || h.f.count("/elsewhere") != 0 {
		t.Fatalf("status %d, followed=%d", resp.Status(), h.f.count("/elsewhere"))
	}
}

func TestIdempotentRequestsAreRetriedOnRetryableStatuses(t *testing.T) {
	h := newHarness(t)
	h.f.onScript("/v1/flaky", func(_ recorded, n int) answer {
		if n < 3 {
			return status(503, "{}")
		}
		return ok(`{"id":"ok","lines":0}`)
	})
	got, err := httpclient.ReceiveJSON[order](t.Context(), h.service().Get("/flaky"))
	if err != nil || got.ID != "ok" || h.f.count("/v1/flaky") != 3 {
		t.Fatalf("%+v %v count=%d", got, err, h.f.count("/v1/flaky"))
	}
	waits := h.waited()
	if len(waits) != 2 {
		t.Fatalf("waits %v, want 2", waits)
	}
	// full jitter: 0 to initial*2^(n-1), capped
	if waits[0] < 0 || waits[0] > 200*time.Millisecond || waits[1] < 0 || waits[1] > 400*time.Millisecond {
		t.Fatalf("waits out of the backoff bounds: %v", waits)
	}
}

func TestBackoffGrowsAndIsCapped(t *testing.T) {
	h := newHarness(t)
	var bounds []int64
	e, err := httpclient.NewEngine(httpclient.WithJitter(func(bound int64) int64 { bounds = append(bounds, bound); return bound }),
		httpclient.WithSleep(func(_ context.Context, d time.Duration) error {
			h.mu.Lock()
			h.waits = append(h.waits, d)
			h.mu.Unlock()
			return nil
		}))
	if err != nil {
		t.Fatal(err)
	}
	defer e.Close()
	h.engine = e
	h.f.on("/v1/down", status(503, "{}"))
	s := h.service(httpclient.Retry(httpclient.DefaultRetry().WithMaxAttempts(10).WithBackoff(100*time.Millisecond, 500*time.Millisecond)))
	send(t, s.Get("/down"))
	want := []time.Duration{100, 200, 400, 500, 500, 500, 500, 500, 500}
	waits := h.waited()
	if len(waits) != len(want) {
		t.Fatalf("%d waits, want %d", len(waits), len(want))
	}
	for i, w := range waits {
		if w != want[i]*time.Millisecond {
			t.Fatalf("wait %d = %v, want %v (initial 100ms doubling, capped at 500ms)", i+1, w, want[i]*time.Millisecond)
		}
	}
	// the default jitter is uniform from 0 to the bound
	h2 := newHarness(t)
	h2.f.on("/v1/down", status(503, "{}"))
	s2 := h2.service(httpclient.Retry(httpclient.DefaultRetry().WithMaxAttempts(10).WithBackoff(100*time.Millisecond, 500*time.Millisecond)))
	send(t, s2.Get("/down"))
	distinct := map[time.Duration]bool{}
	for i, w := range h2.waited() {
		if w < 0 || w > want[i]*time.Millisecond {
			t.Fatalf("jittered wait %d = %v, bound %v", i+1, w, want[i]*time.Millisecond)
		}
		distinct[w] = true
	}
	if len(distinct) < 3 {
		t.Fatalf("waits %v do not look jittered", h2.waited())
	}
}

func TestPostAndPatchAreNotRetriedUnlessMadeSafe(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/p", status(503, "{}"))
	s := h.service()
	if send(t, s.Post("/p").JSON(map[string]any{})).Status() != 503 || send(t, s.Patch("/p").JSON(map[string]any{})).Status() != 503 {
		t.Fatal("status")
	}
	if n := h.f.count("/v1/p"); n != 2 {
		t.Fatalf("POST and PATCH sent %d times, want 2 (once each)", n)
	}
	send(t, s.Post("/p").IdempotencyKey("k-1"))
	if n := h.f.count("/v1/p"); n != 5 {
		t.Fatalf("with an idempotency key: %d total, want 5", n)
	}
	if k := h.f.requests()[4].header.Get("Idempotency-Key"); k != "k-1" {
		t.Fatalf("Idempotency-Key %q", k)
	}
	send(t, s.Post("/p").Retryable(true))
	if n := h.f.count("/v1/p"); n != 8 {
		t.Fatalf("retryable(true): %d total, want 8", n)
	}
	send(t, s.Get("/p").Retryable(false))
	if n := h.f.count("/v1/p"); n != 9 {
		t.Fatalf("retryable(false): %d total, want 9", n)
	}
}

func TestOtherStatusesAreNotRetried(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/bad", status(500, "{}"))
	h.f.on("/v1/nf", status(404, "{}"))
	s := h.service()
	if send(t, s.Get("/bad")).Status() != 500 || send(t, s.Get("/nf")).Status() != 404 || len(h.f.requests()) != 2 {
		t.Fatalf("requests %d", len(h.f.requests()))
	}
}

func TestRetryAfterIsHonouredAndATooLongOneReturnsTheResponseAtOnce(t *testing.T) {
	h := newHarness(t)
	h.f.onScript("/v1/limited", func(_ recorded, n int) answer {
		if n == 1 {
			return status(429, "{}").with("Retry-After", "7")
		}
		return ok("{}")
	})
	s := h.service()
	if send(t, s.Get("/limited")).Status() != 200 {
		t.Fatal("not retried after Retry-After")
	}
	if w := h.waited(); len(w) != 1 || w[0] != 7*time.Second {
		t.Fatalf("waited %v, want exactly the 7s the service asked for", w)
	}
	h.f.on("/v1/later", status(503, "{}").with("Retry-After", "120"))
	if send(t, s.Get("/later")).Status() != 503 || h.f.count("/v1/later") != 1 {
		t.Fatal("a Retry-After above the cap was waited for")
	}
	// the digits of a Retry-After are read up to nine: a huge value is over the cap, ten digits are not a number of seconds
	h.f.on("/v1/huge", status(503, "{}").with("Retry-After", "999999999"))
	if send(t, s.Get("/huge")).Status() != 503 || h.f.count("/v1/huge") != 1 {
		t.Fatal("a nine-digit Retry-After was not read as seconds")
	}
	h.f.on("/v1/ten", status(503, "{}").with("Retry-After", "1234567890"))
	if send(t, s.Get("/ten")).Status() != 503 || h.f.count("/v1/ten") != 3 {
		t.Fatalf("a ten-digit Retry-After is not seconds: %d requests", h.f.count("/v1/ten"))
	}
	// at the cap exactly is honoured
	h.f.onScript("/v1/edge", func(_ recorded, n int) answer {
		if n == 1 {
			return status(503, "{}").with("Retry-After", "30")
		}
		return ok("{}")
	})
	if send(t, s.Get("/edge")).Status() != 200 {
		t.Fatal("a Retry-After of exactly the cap was not honoured")
	}
	// an HTTP date, against the engine's clock
	date := h.clock.Now().Add(12 * time.Second).UTC().Format(http.TimeFormat)
	h.f.onScript("/v1/dated", func(_ recorded, n int) answer {
		if n == 1 {
			return status(503, "{}").with("Retry-After", date)
		}
		return ok("{}")
	})
	if send(t, s.Get("/dated")).Status() != 200 {
		t.Fatal("dated")
	}
	if w := h.waited(); w[len(w)-1] != 12*time.Second {
		t.Fatalf("waited %v for an HTTP date 12s ahead", w)
	}
	// a date in the past is no wait; garbage falls back to the backoff
	past := h.clock.Now().Add(-time.Hour).UTC().Format(http.TimeFormat)
	h.f.onScript("/v1/past", func(_ recorded, n int) answer {
		if n == 1 {
			return status(503, "{}").with("Retry-After", past)
		}
		return ok("{}")
	})
	send(t, s.Get("/past"))
	if w := h.waited(); w[len(w)-1] != 0 {
		t.Fatalf("waited %v for a date in the past", w)
	}
}

func TestWhenAttemptsRunOutTheLastResponseIsReturned(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/down", status(502, "{}"))
	four := h.service(httpclient.Retry(httpclient.DefaultRetry().WithMaxAttempts(4).WithBackoff(0, 0)))
	if send(t, four.Get("/down")).Status() != 502 || h.f.count("/v1/down") != 4 {
		t.Fatalf("count %d", h.f.count("/v1/down"))
	}
	none := h.service(httpclient.Retry(httpclient.NoRetry()))
	if send(t, none.Get("/down")).Status() != 502 || h.f.count("/v1/down") != 5 {
		t.Fatalf("count %d", h.f.count("/v1/down"))
	}
}

func TestACancelledCallerGetsCanceledAndIsNotRetried(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/slow", ok("{}").delayed(3*time.Second))
	ctx, cancel := context.WithCancel(t.Context())
	time.AfterFunc(100*time.Millisecond, cancel)
	_, err := h.service().Get("/slow").Send(ctx)
	if kindOf(err) != httpclient.KindCanceled || !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v", err)
	}
	if got := h.seen.get(); len(got) != 1 {
		t.Fatalf("a cancelled request was retried: %v", got)
	}
	// cancelled while waiting to retry
	h.f.on("/v1/flaky", status(503, "{}"))
	ctx2, cancel2 := context.WithCancel(t.Context())
	e, _ := httpclient.NewEngine(httpclient.WithSleep(func(c context.Context, _ time.Duration) error { cancel2(); return c.Err() }))
	defer e.Close()
	s, _ := e.Service("orders", h.f.base("/v1"))
	if _, err := s.Get("/flaky").Send(ctx2); kindOf(err) != httpclient.KindCanceled {
		t.Fatalf("cancelled during the wait: %v", err)
	}
}

func TestWithHeadersAddsHeadersToEveryRequestAndDefaultsApply(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/x", ok("{}"))
	s := h.service(httpclient.DefaultHeader("X-Tenant", "t1"))
	c, err := s.WithHeaders(map[string]string{"X-Correlation-Id": "c-1"})
	if err != nil {
		t.Fatal(err)
	}
	send(t, c.Get("/x"))
	send(t, s.Get("/x"))
	r0, r1 := h.f.requests()[0], h.f.requests()[1]
	if r0.header.Get("X-Correlation-Id") != "c-1" || r0.header.Get("X-Tenant") != "t1" {
		t.Fatalf("headers %v", r0.header)
	}
	if r1.header.Get("X-Correlation-Id") != "" {
		t.Fatal("WithHeaders changed the original service")
	}
}

func TestUsingAnEngineAfterItWasClosedIsAKindClosedError(t *testing.T) {
	h := newHarness(t)
	h.f.on("/v1/x", ok("{}"))
	s := h.service()
	h.engine.Close()
	if _, err := s.Get("/x").Send(t.Context()); kindOf(err) != httpclient.KindClosed {
		t.Fatalf("err = %v", err)
	}
}

func TestHTTPSWithHostVerification(t *testing.T) {
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) { _, _ = w.Write([]byte(`{"id":"tls"}`)) }))
	defer srv.Close()
	u, _ := url.Parse(srv.URL)
	trusting, err := httpclient.NewEngine(httpclient.WithTLSConfig(srv.Client().Transport.(*http.Transport).TLSClientConfig)) //nolint:forcetypeassert // httptest's client is an *http.Transport
	if err != nil {
		t.Fatal(err)
	}
	defer trusting.Close()
	s, _ := trusting.Service("tls", u, httpclient.Retry(httpclient.NoRetry()))
	if got, err := httpclient.ReceiveJSON[order](t.Context(), s.Get("/")); err != nil || got.ID != "tls" {
		t.Fatalf("%+v %v", got, err)
	}
	// the default trust store does not know the test CA: the handshake fails as a connect failure
	plain, _ := httpclient.NewEngine()
	defer plain.Close()
	s2, _ := plain.Service("tls", u, httpclient.Retry(httpclient.NoRetry()))
	if _, err := s2.Get("/").Send(t.Context()); kindOf(err) != httpclient.KindConnect {
		t.Fatalf("an untrusted certificate: %v", err)
	}
}
