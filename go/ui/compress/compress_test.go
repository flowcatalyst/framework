package compress_test

import (
	"bufio"
	"bytes"
	"compress/gzip"
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/andybalholm/brotli"

	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/compress"
	"github.com/flowcatalyst/framework/go/ui/ds"
)

func TestNegotiate(t *testing.T) {
	for in, want := range map[string]string{
		"":                           "",
		"identity":                   "",
		"gzip":                       compress.Gzip,
		"GZIP":                       compress.Gzip,
		"x-gzip":                     compress.Gzip,
		"br":                         compress.Brotli,
		"gzip, deflate, br, zstd":    compress.Brotli, // Chrome
		"gzip, deflate, br":          compress.Brotli,
		"br;q=0, gzip":               compress.Gzip,
		"gzip;q=0":                   "",
		"gzip; q=0.000":              "",
		"br;q=0.5, gzip;q=0.8":       compress.Gzip,
		"br;q=0.8, gzip;q=0.8":       compress.Brotli,
		"*":                          compress.Brotli,
		"*;q=0":                      "",
		"br;q=0, *":                  compress.Gzip,
		"deflate, zstd":              "",
		"gzip;q=nonsense, br;q=0.1":  compress.Brotli,
		" br ; q=1 ,gzip;q=0.9 ":     compress.Brotli,
		"gzip;q=1, br;q=0, *;q=0.5 ": compress.Gzip,
	} {
		if got := compress.Negotiate(in); got != want {
			t.Errorf("Negotiate(%q) = %q, want %q", in, got, want)
		}
	}
}

func decode(t *testing.T, coding string, r io.Reader) io.Reader {
	t.Helper()
	switch coding {
	case compress.Brotli:
		return brotli.NewReader(r)
	case compress.Gzip:
		g, err := gzip.NewReader(r)
		if err != nil {
			t.Fatal(err)
		}
		return g
	}
	return r
}

const page = "<!doctype html><title>x</title>" + "<tr><td>row</td></tr>"

func serve(t *testing.T, acceptEncoding string, h http.HandlerFunc) *httptest.ResponseRecorder {
	t.Helper()
	r := httptest.NewRequest(http.MethodGet, "/ui/x", nil)
	if acceptEncoding != "" {
		r.Header.Set("Accept-Encoding", acceptEncoding)
	}
	rec := httptest.NewRecorder()
	compress.Handler(h).ServeHTTP(rec, r)
	return rec
}

func htmlPage(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Content-Length", "999") // a stale length must not survive compression
	w.WriteHeader(http.StatusOK)
	for range 200 {
		_, _ = io.WriteString(w, page)
	}
}

func TestHandlerCompressesPages(t *testing.T) {
	want := strings.Repeat(page, 200)
	for _, coding := range []string{compress.Brotli, compress.Gzip} {
		rec := serve(t, coding, htmlPage)
		h := rec.Header()
		if h.Get("Content-Encoding") != coding || h.Get("Vary") != "Accept-Encoding" || h.Get("Content-Length") != "" {
			t.Fatalf("%s: headers %v", coding, h)
		}
		if rec.Body.Len() >= len(want)/10 {
			t.Errorf("%s: %d bytes for %d", coding, rec.Body.Len(), len(want))
		}
		got, err := io.ReadAll(decode(t, coding, rec.Body))
		if err != nil || string(got) != want {
			t.Errorf("%s: decoded %d bytes, err %v (the stream must be complete)", coding, len(got), err)
		}
	}
	// No acceptable coding: plain, but the response still varies.
	rec := serve(t, "gzip;q=0", htmlPage)
	if rec.Header().Get("Content-Encoding") != "" || rec.Header().Get("Vary") != "Accept-Encoding" || rec.Body.String() != want {
		t.Errorf("identity: %v", rec.Header())
	}
}

func TestHandlerLeavesOtherResponsesAlone(t *testing.T) {
	for name, h := range map[string]http.HandlerFunc{
		"zip download": func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "application/zip")
			_, _ = w.Write([]byte("PK\x03\x04"))
		},
		"plain text (a guard's bare refusal)": func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.WriteHeader(http.StatusForbidden)
			_, _ = w.Write([]byte("refused\n"))
		},
		"already encoded": func(w http.ResponseWriter, _ *http.Request) {
			w.Header().Set("Content-Type", "text/html")
			w.Header().Set("Content-Encoding", "gzip")
			_, _ = w.Write([]byte("\x1f\x8bpre-compressed"))
		},
	} {
		plain := serve(t, "", h)
		rec := serve(t, "br, gzip", h)
		if rec.Body.String() != plain.Body.String() || rec.Header().Get("Content-Encoding") != plain.Header().Get("Content-Encoding") {
			t.Errorf("%s: changed by compression: %v", name, rec.Header())
		}
	}
	// A 304 or 204 has no body to encode, and an existing Vary is kept.
	rec := serve(t, "br", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html")
		w.Header().Set("Vary", "Cookie")
		w.WriteHeader(http.StatusNotModified)
	})
	if rec.Header().Get("Content-Encoding") != "" || strings.Join(rec.Header().Values("Vary"), ",") != "Cookie,Accept-Encoding" {
		t.Errorf("304: %v", rec.Header())
	}
}

// The Datastar stream reaches the client event by event: the handler
// sends one patch, waits until the client has decoded it, then sends the
// next. Without a flush through the encoder the client would never see the
// first event and the test would time out. The stream ends complete (the
// gzip trailer / last Brotli block), which the Datastar SDK's own
// compression does not do.
func TestStreamFlushesEachEvent(t *testing.T) {
	for _, coding := range []string{compress.Brotli, compress.Gzip} {
		t.Run(coding, func(t *testing.T) {
			release := make(chan struct{})
			srv := httptest.NewServer(compress.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				sse := ds.NewSSE(w, r)
				if err := sse.PatchHTML(`<div id="first">one</div>`); err != nil {
					t.Error(err)
				}
				select {
				case <-release:
				case <-time.After(5 * time.Second):
					t.Error("the client never saw the first event")
					return
				}
				if err := sse.PatchHTML(`<div id="second">two</div>`); err != nil {
					t.Error(err)
				}
			})))
			defer srv.Close()

			ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
			defer cancel()
			req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL, nil)
			req.Header.Set("Accept-Encoding", coding)
			req.Header.Set(ds.RequestHeader, "true")
			res, err := srv.Client().Do(req)
			if err != nil {
				t.Fatal(err)
			}
			defer func() { _ = res.Body.Close() }()
			if res.Header.Get("Content-Encoding") != coding || res.Header.Get("Content-Type") != "text/event-stream" || res.Header.Get("Vary") != "Accept-Encoding" {
				t.Fatalf("headers %v", res.Header)
			}
			br := bufio.NewReader(decode(t, coding, res.Body))
			first := readEvent(t, br)
			if !strings.Contains(first, `<div id="first">one</div>`) {
				t.Fatalf("first event = %q", first)
			}
			close(release)
			if second := readEvent(t, br); !strings.Contains(second, `<div id="second">two</div>`) {
				t.Fatalf("second event = %q", second)
			}
			if rest, err := io.ReadAll(br); err != nil || strings.TrimSpace(string(rest)) != "" {
				t.Errorf("end of stream: %q, %v (want a complete stream)", rest, err)
			}
		})
	}
}

// readEvent reads one server-sent event: blank lines before it are
// skipped (the SDK ends an event with an extra one), the blank line after
// it ends it.
func readEvent(t *testing.T, r *bufio.Reader) string {
	t.Helper()
	var b bytes.Buffer
	for {
		line, err := r.ReadString('\n')
		if err != nil {
			t.Fatalf("read event: %v (so far %q)", err, b.String()+line)
		}
		if line == "\n" {
			if b.Len() == 0 {
				continue
			}
			return b.String()
		}
		b.WriteString(line)
	}
}

// The static assets through Handler (contract section 12): each coding has its own ETag, a
// conditional request matches only its own variant, and every answer varies on Accept-Encoding.
func TestAssetsGetAnETagPerEncodingAndConditionalRequestsMatchPerVariant(t *testing.T) {
	a, err := assets.Load()
	if err != nil {
		t.Fatal(err)
	}
	h := compress.Handler(a.Handler())
	url := a.URL("kit.css")
	send := func(method, acceptEncoding string, headers ...string) *httptest.ResponseRecorder {
		r := httptest.NewRequest(method, url, http.NoBody)
		if acceptEncoding != "" {
			r.Header.Set("Accept-Encoding", acceptEncoding)
		}
		for i := 0; i+1 < len(headers); i += 2 {
			r.Header.Set(headers[i], headers[i+1])
		}
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, r)
		return rec
	}
	plain := send("GET", "")
	base := plain.Header().Get("ETag")
	if plain.Code != 200 || len(base) < 4 || plain.Header().Get("Content-Encoding") != "" || plain.Header().Get("Vary") != "Accept-Encoding" {
		t.Fatalf("identity: %d %v", plain.Code, plain.Header())
	}
	tag := map[string]string{compress.Brotli: strings.TrimSuffix(base, `"`) + `-br"`, compress.Gzip: strings.TrimSuffix(base, `"`) + `-gz"`}
	for _, coding := range []string{compress.Brotli, compress.Gzip} {
		rec := send("GET", coding)
		if rec.Code != 200 || rec.Header().Get("Content-Encoding") != coding || rec.Header().Get("ETag") != tag[coding] ||
			rec.Header().Get("Vary") != "Accept-Encoding" || rec.Header().Get("Content-Length") != "" {
			t.Fatalf("%s: %d %v", coding, rec.Code, rec.Header())
		}
		if rec.Header().Get("Cache-Control") != "public, max-age=31536000, immutable" {
			t.Errorf("%s: Cache-Control %q", coding, rec.Header().Get("Cache-Control"))
		}
		got, err := io.ReadAll(decode(t, coding, rec.Body))
		if err != nil || !bytes.Equal(got, plain.Body.Bytes()) {
			t.Errorf("%s: decoded %d bytes (want %d), err %v", coding, len(got), plain.Body.Len(), err)
		}
		// its own tag: 304, with the variant's tag and Vary, and no body
		nm := send("GET", coding, "If-None-Match", tag[coding])
		if nm.Code != 304 || nm.Header().Get("ETag") != tag[coding] || nm.Header().Get("Vary") != "Accept-Encoding" || nm.Body.Len() != 0 ||
			nm.Header().Get("Content-Encoding") != "" {
			t.Errorf("%s own tag: %d %v body %d", coding, nm.Code, nm.Header(), nm.Body.Len())
		}
		// weak form and a list
		if rec := send("GET", coding, "If-None-Match", `"zzz", W/`+tag[coding]); rec.Code != 304 {
			t.Errorf("%s weak/list: %d", coding, rec.Code)
		}
	}
	// the other coding's tag does not match
	if rec := send("GET", compress.Brotli, "If-None-Match", tag[compress.Gzip]); rec.Code != 200 || rec.Header().Get("ETag") != tag[compress.Brotli] {
		t.Errorf("br request with the gzip tag: %d %v", rec.Code, rec.Header())
	}
	if rec := send("GET", compress.Gzip, "If-None-Match", tag[compress.Brotli]); rec.Code != 200 {
		t.Errorf("gzip request with the br tag: %d", rec.Code)
	}
	// an unrelated tag, and a variant tag of another file, never match
	if rec := send("GET", compress.Brotli, "If-None-Match", `"other-br"`); rec.Code != 200 {
		t.Errorf("other file's tag: %d", rec.Code)
	}
	// without an acceptable coding the plain tag is the validator
	if rec := send("GET", "gzip;q=0", "If-None-Match", base); rec.Code != 304 || rec.Header().Get("ETag") != base {
		t.Errorf("identity conditional: %d %v", rec.Code, rec.Header())
	}
	if rec := send("GET", "", "If-None-Match", tag[compress.Brotli]); rec.Code != 200 {
		t.Errorf("a variant tag does not match the plain body: %d", rec.Code)
	}
	// `*` still matches
	if rec := send("GET", compress.Brotli, "If-None-Match", "*"); rec.Code != 304 {
		t.Errorf("*: %d", rec.Code)
	}
	// HEAD carries the headers of the GET answer
	head := send("HEAD", compress.Brotli)
	if head.Code != 200 || head.Header().Get("ETag") != tag[compress.Brotli] || head.Header().Get("Content-Encoding") != compress.Brotli ||
		head.Header().Get("Vary") != "Accept-Encoding" {
		t.Errorf("HEAD: %d %v", head.Code, head.Header())
	}
	// the sprite is image/svg+xml and the islands text/javascript: all compressed
	for _, name := range []string{"lucide-sprite.svg", "datastar.js"} {
		r := httptest.NewRequest("GET", a.URL(name), http.NoBody)
		r.Header.Set("Accept-Encoding", "br")
		rec := httptest.NewRecorder()
		h.ServeHTTP(rec, r)
		if rec.Header().Get("Content-Encoding") != compress.Brotli || !strings.HasSuffix(rec.Header().Get("ETag"), `-br"`) {
			t.Errorf("%s: %v", name, rec.Header())
		}
	}
}

// A caller's request is not changed by the tag rewriting.
func TestHandlerDoesNotMutateTheCallersRequest(t *testing.T) {
	r := httptest.NewRequest("GET", "/x", http.NoBody)
	r.Header.Set("Accept-Encoding", "br")
	r.Header.Set("If-None-Match", `"abc-br"`)
	compress.Handler(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("If-None-Match"); got != `"abc"` {
			t.Errorf("the handler sees %q", got)
		}
		w.WriteHeader(http.StatusNoContent)
	})).ServeHTTP(httptest.NewRecorder(), r)
	if got := r.Header.Get("If-None-Match"); got != `"abc-br"` {
		t.Errorf("the caller's request was changed: %q", got)
	}
}

// The helpers for a handler that compresses its own content (the assets' WithCompression).
func TestCompressibleVariantTagAndEncode(t *testing.T) {
	for ctype, want := range map[string]bool{
		"text/css; charset=utf-8": true, "text/javascript; charset=utf-8": true, "image/svg+xml": true, "TEXT/CSS": true,
		"text/plain; charset=utf-8": false, "image/png": false, "text/html; charset=utf-8": false, "": false,
	} {
		if got := compress.Compressible(ctype); got != want {
			t.Errorf("Compressible(%q) = %v", ctype, got)
		}
	}
	for in, want := range map[[2]string]string{
		{`"abc"`, compress.Brotli}: `"abc-br"`, {`"abc"`, compress.Gzip}: `"abc-gz"`, {`W/"abc"`, compress.Brotli}: `W/"abc-br"`,
		{`abc`, compress.Brotli}: `abc`,
	} {
		if got := compress.VariantTag(in[0], in[1]); got != want {
			t.Errorf("VariantTag(%s, %s) = %s, want %s", in[0], in[1], got, want)
		}
	}
	body := bytes.Repeat([]byte("the kit stylesheet "), 500)
	for _, coding := range []string{compress.Brotli, compress.Gzip} {
		enc := compress.Encode(coding, body)
		got, err := io.ReadAll(decode(t, coding, bytes.NewReader(enc)))
		if err != nil || !bytes.Equal(got, body) || len(enc) >= len(body) {
			t.Errorf("%s: %d -> %d bytes, round trip %v", coding, len(body), len(enc), err)
		}
	}
}
