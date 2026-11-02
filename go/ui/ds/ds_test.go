package ds

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"
)

// raw is a minimal Component for tests.
type raw string

func (h raw) Render(_ context.Context, w io.Writer) error {
	_, err := io.WriteString(w, string(h))
	return err
}

type failing struct{}

func (failing) Render(context.Context, io.Writer) error { return errors.New("boom") }

func newStream(t *testing.T) (*httptest.ResponseRecorder, *SSE) {
	t.Helper()
	rec := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/x", nil)
	req.Header.Set(RequestHeader, "true")
	return rec, NewSSE(rec, req)
}

func wire(t *testing.T, fn func(s *SSE) error) string {
	t.Helper()
	rec, s := newStream(t)
	if err := fn(s); err != nil {
		t.Fatalf("stream: %v", err)
	}
	return rec.Body.String()
}

func expect(t *testing.T, name string, got, want string) {
	t.Helper()
	if got != want {
		t.Errorf("%s: bytes\n got %q\nwant %q", name, got, want)
	}
}

// The expected strings below are the ones of framework-http's DatastarTest
// (the Java adapter of the same contract section), so both adapters are held
// to the same bytes.
func TestPatchElementsBytes(t *testing.T) {
	expect(t, "default", wire(t, func(s *SSE) error { return s.PatchHTML(`<div id="a">hi</div>`) }),
		"event: datastar-patch-elements\ndata: elements <div id=\"a\">hi</div>\n\n")

	expect(t, "one data line per line", wire(t, func(s *SSE) error {
		return s.PatchHTML("<div id=\"feed\">\n    <span>1</span>\r\n</div>")
	}), "event: datastar-patch-elements\n"+
		"data: elements <div id=\"feed\">\n"+
		"data: elements     <span>1</span>\n"+
		"data: elements </div>\n\n")

	expect(t, "selector", wire(t, func(s *SSE) error { return s.PatchHTML("<p>x</p>", WithSelector("#feed")) }),
		"event: datastar-patch-elements\ndata: selector #feed\ndata: elements <p>x</p>\n\n")

	expect(t, "utf-8", wire(t, func(s *SSE) error { return s.PatchHTML(`<p id="a">héllo ☃</p>`) }),
		"event: datastar-patch-elements\ndata: elements <p id=\"a\">héllo ☃</p>\n\n")
}

func TestEveryModeIsWrittenAndOuterIsNot(t *testing.T) {
	for _, m := range modes {
		if m == ModeRemove {
			continue
		}
		got := wire(t, func(s *SSE) error { return s.PatchHTML(`<i id="x"></i>`, WithMode(m)) })
		want := "event: datastar-patch-elements\ndata: mode " + string(m) + "\ndata: elements <i id=\"x\"></i>\n\n"
		if m == ModeOuter {
			want = "event: datastar-patch-elements\ndata: elements <i id=\"x\"></i>\n\n"
		}
		expect(t, string(m), got, want)
	}
}

func TestOptions(t *testing.T) {
	expect(t, "view transition", wire(t, func(s *SSE) error { return s.PatchHTML(`<p id="a"></p>`, WithViewTransition()) }),
		"event: datastar-patch-elements\ndata: useViewTransition true\ndata: elements <p id=\"a\"></p>\n\n")

	expect(t, "svg", wire(t, func(s *SSE) error { return s.PatchHTML(`<circle id="c"/>`, WithNamespace(NamespaceSVG)) }),
		"event: datastar-patch-elements\ndata: namespace svg\ndata: elements <circle id=\"c\"/>\n\n")
	if got := wire(t, func(s *SSE) error { return s.PatchHTML(`<m id="c"/>`, WithNamespace(NamespaceMathML)) }); !strings.Contains(got, "data: namespace mathml\n") {
		t.Errorf("mathml: %q", got)
	}
	if got := wire(t, func(s *SSE) error { return s.PatchHTML(`<p id="c"/>`, WithNamespace(NamespaceHTML)) }); strings.Contains(got, "namespace") {
		t.Errorf("html namespace is the default and is not written: %q", got)
	}

	expect(t, "event id and retry come before the data lines", wire(t, func(s *SSE) error {
		return s.PatchHTML(`<p id="a"></p>`, WithEventID("evt-1"), WithRetry(2500*time.Millisecond))
	}), "event: datastar-patch-elements\nid: evt-1\nretry: 2500\ndata: elements <p id=\"a\"></p>\n\n")

	if got := wire(t, func(s *SSE) error { return s.PatchHTML(`<p id="a"></p>`, WithRetry(DefaultRetry)) }); strings.Contains(got, "retry") {
		t.Errorf("the default retry is omitted: %q", got)
	}

	expect(t, "all options in spec order", wire(t, func(s *SSE) error {
		return s.PatchHTML(`<g id="a"/>`, WithSelector("#t"), WithMode(ModeInner), WithViewTransition(),
			WithNamespace(NamespaceSVG), WithEventID("7"), WithRetry(10*time.Millisecond))
	}), "event: datastar-patch-elements\nid: 7\nretry: 10\ndata: selector #t\ndata: mode inner\n"+
		"data: useViewTransition true\ndata: namespace svg\ndata: elements <g id=\"a\"/>\n\n")
}

func TestModeHelpers(t *testing.T) {
	cases := map[string]PatchOption{
		"inner": Inner("#s"), "append": Append("#s"), "prepend": Prepend("#s"),
		"replace": Replace("#s"), "before": Before("#s"), "after": After("#s"),
	}
	for mode, opt := range cases {
		expect(t, mode, wire(t, func(s *SSE) error { return s.PatchHTML(`<b>x</b>`, opt) }),
			"event: datastar-patch-elements\ndata: selector #s\ndata: mode "+mode+"\ndata: elements <b>x</b>\n\n")
	}
}

func TestRemove(t *testing.T) {
	expect(t, "by selector", wire(t, func(s *SSE) error { return s.Remove("#gone") }),
		"event: datastar-patch-elements\ndata: selector #gone\ndata: mode remove\n\n")
	expect(t, "by element ids", wire(t, func(s *SSE) error { return s.PatchHTML(`<div id="x"></div>`, WithMode(ModeRemove)) }),
		"event: datastar-patch-elements\ndata: mode remove\ndata: elements <div id=\"x\"></div>\n\n")
}

func TestBadInputsAreRefusedAndNothingIsWritten(t *testing.T) {
	cases := map[string]func(s *SSE) error{
		"blank elements":    func(s *SSE) error { return s.PatchHTML("  ") },
		"remove nothing":    func(s *SSE) error { return s.Remove("") },
		"selector newline":  func(s *SSE) error { return s.PatchHTML(`<p id="a"/>`, WithSelector("a\nb")) },
		"event id newline":  func(s *SSE) error { return s.PatchHTML(`<p id="a"/>`, WithEventID("a\r\nevent: x")) },
		"negative retry":    func(s *SSE) error { return s.PatchHTML(`<p id="a"/>`, WithRetry(-1)) },
		"unknown mode":      func(s *SSE) error { return s.PatchHTML(`<p id="a"/>`, WithMode("sideways")) },
		"unknown namespace": func(s *SSE) error { return s.PatchHTML(`<p id="a"/>`, WithNamespace("xml")) },
		"blank selector":    func(s *SSE) error { return s.PatchHTML(`<p id="a"/>`, WithSelector("  ")) },
		"invalid signals":   func(s *SSE) error { return s.PatchSignalsJSON("{nope") },
		"signals event id":  func(s *SSE) error { return s.PatchSignalsJSON("{}", WithEventID("a\nb")) },
	}
	for name, fn := range cases {
		rec, s := newStream(t)
		if err := fn(s); err == nil {
			t.Errorf("%s: accepted", name)
		}
		if rec.Body.Len() != 0 {
			t.Errorf("%s: wrote %q", name, rec.Body.String())
		}
	}
}

func TestPatchSignalsBytes(t *testing.T) {
	expect(t, "default", wire(t, func(s *SSE) error { return s.PatchSignalsJSON(`{"output":"x","show":true}`) }),
		"event: datastar-patch-signals\ndata: signals {\"output\":\"x\",\"show\":true}\n\n")
	expect(t, "options", wire(t, func(s *SSE) error {
		return s.PatchSignalsJSON(`{"a":1}`, OnlyIfMissing(), WithEventID("123"), WithRetry(2000*time.Millisecond))
	}), "event: datastar-patch-signals\nid: 123\nretry: 2000\ndata: onlyIfMissing true\ndata: signals {\"a\":1}\n\n")
	expect(t, "multi-line", wire(t, func(s *SSE) error { return s.PatchSignalsJSON("{\n  \"a\": null\n}") }),
		"event: datastar-patch-signals\ndata: signals {\ndata: signals   \"a\": null\ndata: signals }\n\n")
	expect(t, "encoded value", wire(t, func(s *SSE) error { return s.PatchSignals(map[string]any{"q": "abc", "n": 2}) }),
		"event: datastar-patch-signals\ndata: signals {\"n\":2,\"q\":\"abc\"}\n\n")
	expect(t, "two events in one response", wire(t, func(s *SSE) error {
		return errors.Join(s.Patch(raw(`<p id="a">1</p>`)), s.PatchSignalsJSON(`{"n":2}`))
	}), "event: datastar-patch-elements\ndata: elements <p id=\"a\">1</p>\n\n"+
		"event: datastar-patch-signals\ndata: signals {\"n\":2}\n\n")
}

func TestStreamHeaders(t *testing.T) {
	check := func(proto string, major, minor int, wantKeepAlive bool) {
		t.Helper()
		rec := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodGet, "/x", nil)
		req.Proto, req.ProtoMajor, req.ProtoMinor = proto, major, minor
		NewSSE(rec, req)
		h := rec.Header()
		if h.Get("Content-Type") != "text/event-stream" || h.Get("Cache-Control") != "no-cache" {
			t.Errorf("%s: headers %v", proto, h)
		}
		if got := h.Get("Connection") == "keep-alive"; got != wantKeepAlive {
			t.Errorf("%s: Connection = %q, want keep-alive %v", proto, h.Get("Connection"), wantKeepAlive)
		}
		if rec.Code != http.StatusOK {
			t.Errorf("%s: status %d", proto, rec.Code)
		}
	}
	check("HTTP/1.1", 1, 1, true)
	check("HTTP/1.0", 1, 0, false)
	check("HTTP/2.0", 2, 0, false)
}

// flushCounter counts Flush calls.
type flushCounter struct {
	*httptest.ResponseRecorder
	flushes int
}

func (f *flushCounter) Flush() { f.flushes++ }

func TestEachEventIsFlushed(t *testing.T) {
	w := &flushCounter{ResponseRecorder: httptest.NewRecorder()}
	s := NewSSE(w, httptest.NewRequest(http.MethodGet, "/x", nil))
	start := w.flushes // the headers are flushed on start
	if start != 1 {
		t.Fatalf("start flushed %d times, want 1", start)
	}
	if err := errors.Join(s.PatchHTML(`<p id="a"/>`), s.PatchSignalsJSON("{}"), s.Remove("#x")); err != nil {
		t.Fatal(err)
	}
	if w.flushes-start != 3 {
		t.Errorf("3 events flushed %d times", w.flushes-start)
	}
}

// noFlush is a ResponseWriter that cannot flush.
type noFlush struct{ h http.Header }

func (n noFlush) Header() http.Header       { return n.h }
func (noFlush) Write(b []byte) (int, error) { return len(b), nil }
func (noFlush) WriteHeader(int)             {}

func TestAWriterThatCannotFlushFailsEveryCall(t *testing.T) {
	s := NewSSE(noFlush{h: http.Header{}}, httptest.NewRequest(http.MethodGet, "/x", nil))
	if err := s.PatchHTML(`<p id="a"/>`); err == nil {
		t.Fatal("event written on a writer that cannot flush")
	}
}

type brokenWriter struct{ *httptest.ResponseRecorder }

func (brokenWriter) Write([]byte) (int, error)       { return 0, errors.New("broken pipe") }
func (brokenWriter) WriteString(string) (int, error) { return 0, errors.New("broken pipe") }
func (brokenWriter) Flush()                          {}

func TestAWriteFailureIsReturnedAndSticks(t *testing.T) {
	s := NewSSE(brokenWriter{httptest.NewRecorder()}, httptest.NewRequest(http.MethodGet, "/x", nil))
	err := s.PatchSignalsJSON("{}")
	if err == nil || !strings.Contains(err.Error(), "broken pipe") {
		t.Fatalf("err = %v", err)
	}
	if err := s.PatchSignalsJSON("{}"); err == nil {
		t.Fatal("a stream that failed once accepted another event")
	}
}

func TestAGoneClientIsReported(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	rec := httptest.NewRecorder()
	s := NewSSE(rec, httptest.NewRequest(http.MethodGet, "/x", nil).WithContext(ctx))
	cancel()
	err := s.PatchSignalsJSON("{}")
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("err = %v, want context.Canceled", err)
	}
	if rec.Body.Len() != 0 {
		t.Errorf("wrote %q", rec.Body.String())
	}
}

func TestPatchRenderErrorIsReturnedAndNothingIsSent(t *testing.T) {
	rec, s := newStream(t)
	if err := s.Patch(failing{}); err == nil || !strings.Contains(err.Error(), "boom") {
		t.Fatalf("err = %v, want the render error", err)
	}
	if rec.Body.Len() != 0 {
		t.Fatalf("a failed render must not emit an event, got %q", rec.Body.String())
	}
}

// The self-removing elements. The expressions are those of Java's Patches
// (focus, closeDialog), whose attribute escaping is & < > " ' to the five
// entities below.
func TestSelfRemovingElementBytes(t *testing.T) {
	const head = "event: datastar-patch-elements\ndata: selector body\ndata: mode append\n"
	expect(t, "focus", wire(t, func(s *SSE) error { return s.Focus("name-input") }),
		head+"data: elements <span hidden data-init=\"requestAnimationFrame(() =&gt; document.getElementById(&#39;name-input&#39;)?.focus()); el.remove()\"></span>\n\n")
	expect(t, "close dialog", wire(t, func(s *SSE) error { return s.CloseDialog("edit-dlg") }),
		head+"data: elements <span hidden data-init=\"document.getElementById(&#39;edit-dlg&#39;).close(); el.remove()\"></span>\n\n")
	expect(t, "replace url", wire(t, func(s *SSE) error { return s.ReplaceURL("/pools?q=a%20b&status=ACTIVE") }),
		head+"data: elements <span hidden data-init=\"history.replaceState(history.state, &#39;&#39;, &quot;/pools?q=a%20b\\u0026status=ACTIVE&quot;); el.remove()\"></span>\n\n")
	expect(t, "redirect", wire(t, func(s *SSE) error { return s.Redirect("/login?returnTo=/x&a=<b>") }),
		head+"data: elements <span hidden data-init=\"window.location.assign(&quot;/login?returnTo=/x\\u0026a=\\u003cb\\u003e&quot;)\"></span>\n\n")
}

func TestFocusAndCloseDialogRefuseAnIdThatIsNotValid(t *testing.T) {
	for _, id := range []string{"", "1a", "a b", "x');alert(1);//", "a:b", "é"} {
		rec, s := newStream(t)
		if err := s.Focus(id); !errors.Is(err, ErrInvalidID) {
			t.Errorf("Focus(%q) = %v", id, err)
		}
		if err := s.CloseDialog(id); !errors.Is(err, ErrInvalidID) {
			t.Errorf("CloseDialog(%q) = %v", id, err)
		}
		if rec.Body.Len() != 0 {
			t.Errorf("%q wrote %q", id, rec.Body.String())
		}
	}
}

func TestRedirectRefusesOtherOrigins(t *testing.T) {
	for _, target := range []string{"https://evil.example/x", "//evil.example/x", `/\evil.example`, `/a\b`, "ui/x", "javascript:alert(1)", "/ui/\nx", "/a\x7f", ""} {
		rec, s := newStream(t)
		if err := s.Redirect(target); !errors.Is(err, ErrUnsafeRedirect) {
			t.Errorf("Redirect(%q) err = %v, want ErrUnsafeRedirect", target, err)
		}
		if err := s.ReplaceURL(target); !errors.Is(err, ErrUnsafeRedirect) {
			t.Errorf("ReplaceURL(%q) err = %v, want ErrUnsafeRedirect", target, err)
		}
		if rec.Body.Len() != 0 {
			t.Errorf("Redirect(%q) wrote %q", target, rec.Body.String())
		}
	}
}

func TestIsLocalPath(t *testing.T) {
	for p, want := range map[string]bool{
		"/": true, "/x": true, "/x?a=b&c=d#f": true, "/a/b%20c": true,
		"": false, "x": false, "//h": false, `/\h`: false, "/a\\b": false, "/a\tb": false, "/a\x00": false, "http://h/": false,
	} {
		if got := IsLocalPath(p); got != want {
			t.Errorf("IsLocalPath(%q) = %v, want %v", p, got, want)
		}
	}
}

func TestIsDatastar(t *testing.T) {
	r := httptest.NewRequest(http.MethodGet, "/", nil)
	if IsDatastar(r) {
		t.Fatal("plain request reported as Datastar")
	}
	for _, v := range []string{"true", "TRUE", "True"} {
		r.Header.Set(RequestHeader, v)
		if !IsDatastar(r) {
			t.Errorf("Datastar-Request: %s not detected", v)
		}
	}
	r.Header.Set(RequestHeader, "false")
	if IsDatastar(r) {
		t.Error("Datastar-Request: false detected")
	}
}

type filters struct {
	Q      string `json:"q"`
	Status string `json:"status"`
	N      int    `json:"n"`
}

func status(t *testing.T, err error) int {
	t.Helper()
	var se *SignalsError
	if !errors.As(err, &se) {
		t.Fatalf("err = %v, want *SignalsError", err)
	}
	return se.Status
}

func TestReadSignals(t *testing.T) {
	const q = `{"q":"abc","status":"ACTIVE","n":3}`
	for _, method := range []string{http.MethodGet, http.MethodHead, http.MethodDelete} {
		t.Run(method+" reads the datastar query parameter, not the body", func(t *testing.T) {
			r := httptest.NewRequest(method, "/x?datastar="+url.QueryEscape(q), strings.NewReader(`{"q":"body"}`))
			var f filters
			if err := ReadSignals(r, &f); err != nil {
				t.Fatal(err)
			}
			if f != (filters{Q: "abc", Status: "ACTIVE", N: 3}) {
				t.Fatalf("got %+v", f)
			}
		})
	}
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodPatch} {
		t.Run(method+" reads the JSON body, not the query", func(t *testing.T) {
			r := httptest.NewRequest(method, "/x?datastar="+url.QueryEscape(`{"q":"query"}`), strings.NewReader(`{"q":"body","status":"SUSPENDED","other":1}`))
			var f filters
			if err := ReadSignals(r, &f); err != nil {
				t.Fatal(err)
			}
			if f != (filters{Q: "body", Status: "SUSPENDED"}) {
				t.Fatalf("got %+v (unknown members are ignored)", f)
			}
		})
	}
	t.Run("absent, empty, null or invalid signals are 400", func(t *testing.T) {
		for name, r := range map[string]*http.Request{
			"get absent":   httptest.NewRequest(http.MethodGet, "/x", nil),
			"get invalid":  httptest.NewRequest(http.MethodGet, "/x?datastar=%7Bnope", nil),
			"get null":     httptest.NewRequest(http.MethodGet, "/x?datastar=null", nil),
			"get wrongtyp": httptest.NewRequest(http.MethodGet, "/x?datastar=%7B%22n%22%3A%22x%22%7D", nil),
			"post no body": httptest.NewRequest(http.MethodPost, "/x", nil),
			"post empty":   httptest.NewRequest(http.MethodPost, "/x", strings.NewReader("")),
			"post blank":   httptest.NewRequest(http.MethodPut, "/x", strings.NewReader("  ")),
			"post invalid": httptest.NewRequest(http.MethodPost, "/x", strings.NewReader("not json")),
			"post null":    httptest.NewRequest(http.MethodPost, "/x", strings.NewReader("null")),
		} {
			var f filters
			err := ReadSignals(r, &f)
			if err == nil {
				t.Errorf("%s: accepted", name)
				continue
			}
			if got := status(t, err); got != http.StatusBadRequest {
				t.Errorf("%s: status %d", name, got)
			}
		}
	})
	t.Run("a body above 1 MiB is 413", func(t *testing.T) {
		big := `{"q":"` + strings.Repeat("a", MaxSignalBytes) + `"}`
		var f filters
		err := ReadSignals(httptest.NewRequest(http.MethodPost, "/x", strings.NewReader(big)), &f)
		if got := status(t, err); got != http.StatusRequestEntityTooLarge {
			t.Fatalf("status %d", got)
		}
		if _, ok := errors.AsType[*http.MaxBytesError](err); !ok {
			t.Errorf("err = %v does not wrap *http.MaxBytesError", err)
		}
	})
	t.Run("a body of exactly 1 MiB is read", func(t *testing.T) {
		const prefix, suffix = `{"q":"`, `"}`
		body := prefix + strings.Repeat("a", MaxSignalBytes-len(prefix)-len(suffix)) + suffix
		var f filters
		if err := ReadSignals(httptest.NewRequest(http.MethodPost, "/x", strings.NewReader(body)), &f); err != nil {
			t.Fatal(err)
		}
	})
}
