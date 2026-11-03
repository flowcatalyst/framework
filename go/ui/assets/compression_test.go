package assets

import (
	"bytes"
	"compress/gzip"
	"io"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"sync"
	"testing"
	"testing/fstest"

	"github.com/andybalholm/brotli"

	"github.com/flowcatalyst/framework/go/ui/compress"
)

func decoded(t *testing.T, coding string, body []byte) []byte {
	t.Helper()
	var r io.Reader
	switch coding {
	case compress.Brotli:
		r = brotli.NewReader(bytes.NewReader(body))
	case compress.Gzip:
		gr, err := gzip.NewReader(bytes.NewReader(body))
		if err != nil {
			t.Fatal(err)
		}
		r = gr
	}
	out, err := io.ReadAll(r)
	if err != nil {
		t.Fatalf("%s: %v", coding, err)
	}
	return out
}

// WithCompression (contract section 12): the same answers from the handler alone and wrapped by
// compress.Handler, which passes an answer that already has a Content-Encoding through.
func TestCompressedAnswers(t *testing.T) {
	a := load(t, WithCompression())
	for wrapper, h := range map[string]http.Handler{"alone": a.Handler(), "under compress.Handler": compress.Handler(a.Handler())} {
		t.Run(wrapper, func(t *testing.T) {
			url := a.URL("kit.css")
			file := a.files["kit.css"]
			plainTag := file.etag
			tag := map[string]string{compress.Brotli: compress.VariantTag(plainTag, compress.Brotli), compress.Gzip: compress.VariantTag(plainTag, compress.Gzip)}
			send := func(method, acceptEncoding, ifNoneMatch string) *httptest.ResponseRecorder {
				r := httptest.NewRequest(method, url, http.NoBody)
				if acceptEncoding != "" {
					r.Header.Set("Accept-Encoding", acceptEncoding)
				}
				if ifNoneMatch != "" {
					r.Header.Set("If-None-Match", ifNoneMatch)
				}
				rec := httptest.NewRecorder()
				h.ServeHTTP(rec, r)
				return rec
			}

			plain := send(http.MethodGet, "", "")
			if plain.Code != 200 || plain.Header().Get("ETag") != plainTag || plain.Header().Get("Content-Encoding") != "" ||
				plain.Header().Get("Vary") != "Accept-Encoding" || !bytes.Equal(plain.Body.Bytes(), file.body) {
				t.Fatalf("identity: %d %v", plain.Code, plain.Header())
			}
			for _, coding := range []string{compress.Brotli, compress.Gzip} {
				rec := send(http.MethodGet, coding, "")
				hd := rec.Header()
				if rec.Code != 200 || hd.Get("Content-Encoding") != coding || hd.Get("ETag") != tag[coding] || hd.Get("Vary") != "Accept-Encoding" ||
					hd.Get("Content-Type") != "text/css; charset=utf-8" || hd.Get("Cache-Control") != "public, max-age=31536000, immutable" {
					t.Fatalf("%s: %d %v", coding, rec.Code, hd)
				}
				if hd.Get("Content-Length") != strconv.Itoa(rec.Body.Len()) || rec.Body.Len() >= len(file.body) {
					t.Errorf("%s: Content-Length %q for %d bytes (file %d)", coding, hd.Get("Content-Length"), rec.Body.Len(), len(file.body))
				}
				if !bytes.Equal(decoded(t, coding, rec.Body.Bytes()), file.body) {
					t.Errorf("%s: the decoded body differs from the file", coding)
				}
				// its own tag, weak or in a list: 304 with the variant's tag and Vary, no body
				for _, inm := range []string{tag[coding], `"zzz", W/` + tag[coding]} {
					nm := send(http.MethodGet, coding, inm)
					if nm.Code != 304 || nm.Header().Get("ETag") != tag[coding] || nm.Header().Get("Vary") != "Accept-Encoding" ||
						nm.Body.Len() != 0 || nm.Header().Get("Content-Encoding") != "" {
						t.Errorf("%s, If-None-Match %s: %d %v", coding, inm, nm.Code, nm.Header())
					}
				}
				// the plain tag (a client that stored the uncompressed body): 304 with the plain tag
				if nm := send(http.MethodGet, coding, plainTag); nm.Code != 304 || nm.Header().Get("ETag") != plainTag {
					t.Errorf("%s, plain tag: %d %v", coding, nm.Code, nm.Header())
				}
				// HEAD: the GET's headers, no body
				head := send(http.MethodHead, coding, "")
				if head.Code != 200 || head.Body.Len() != 0 || head.Header().Get("Content-Encoding") != coding || head.Header().Get("ETag") != tag[coding] ||
					head.Header().Get("Content-Length") != hd.Get("Content-Length") {
					t.Errorf("%s HEAD: %d %v", coding, head.Code, head.Header())
				}
			}
			// the other coding's tag matches nothing; nor does a variant tag on an identity request
			if rec := send(http.MethodGet, compress.Brotli, tag[compress.Gzip]); rec.Code != 200 || rec.Header().Get("ETag") != tag[compress.Brotli] {
				t.Errorf("br with the gzip tag: %d %v", rec.Code, rec.Header())
			}
			if rec := send(http.MethodGet, compress.Gzip, tag[compress.Brotli]); rec.Code != 200 {
				t.Errorf("gzip with the br tag: %d", rec.Code)
			}
			if rec := send(http.MethodGet, "", tag[compress.Brotli]); rec.Code != 200 || rec.Header().Get("Content-Encoding") != "" {
				t.Errorf("identity with the br tag: %d %v", rec.Code, rec.Header())
			}
			if rec := send(http.MethodGet, "gzip;q=0, br;q=0", plainTag); rec.Code != 304 || rec.Header().Get("ETag") != plainTag {
				t.Errorf("no acceptable coding, plain tag: %d %v", rec.Code, rec.Header())
			}
			if rec := send(http.MethodGet, compress.Brotli, "*"); rec.Code != 304 {
				t.Errorf("*: %d", rec.Code)
			}
			// gzip when Brotli is refused, Brotli preferred otherwise
			if rec := send(http.MethodGet, "br;q=0, gzip", ""); rec.Header().Get("Content-Encoding") != compress.Gzip {
				t.Errorf("br;q=0, gzip: %v", rec.Header())
			}
			// the sprite and the scripts are compressed; a licence notice (text/plain) is not, and does not vary
			for _, name := range []string{SpriteName, "datastar.js", "fc-diagram.js"} {
				r := httptest.NewRequest(http.MethodGet, a.URL(name), http.NoBody)
				r.Header.Set("Accept-Encoding", "br")
				rec := httptest.NewRecorder()
				h.ServeHTTP(rec, r)
				if rec.Header().Get("Content-Encoding") != compress.Brotli || !bytes.Equal(decoded(t, compress.Brotli, rec.Body.Bytes()), a.files[name].body) {
					t.Errorf("%s: %v", name, rec.Header())
				}
			}
			r := httptest.NewRequest(http.MethodGet, a.URL("LICENSE-datastar.md"), http.NoBody)
			r.Header.Set("Accept-Encoding", "br")
			rec := httptest.NewRecorder()
			h.ServeHTTP(rec, r)
			if rec.Header().Get("Content-Encoding") != "" || rec.Header().Get("Vary") != "" || rec.Body.String() != string(a.files["LICENSE-datastar.md"].body) {
				t.Errorf("licence notice: %v", rec.Header())
			}
		})
	}
}

// Without WithCompression nothing is compressed and nothing varies (compression is opt-in, ruling R3).
func TestNoCompressionByDefault(t *testing.T) {
	a := load(t)
	rec := get(t, a, http.MethodGet, a.URL("kit.css"), map[string]string{"Accept-Encoding": "br, gzip"})
	if rec.Header().Get("Content-Encoding") != "" || rec.Header().Get("Vary") != "" || rec.Header().Get("ETag") != a.files["kit.css"].etag {
		t.Errorf("default instance: %v", rec.Header())
	}
	for name, f := range a.files {
		if f.coded != nil {
			t.Errorf("%s has compressed variants without WithCompression", name)
		}
	}
}

// Each variant is made by the first request for its coding and kept: nothing is compressed at Load,
// a coding nobody asked for is never made, and later requests are answered from the same bytes.
func TestEachVariantIsCompressedOnceAndKept(t *testing.T) {
	a := load(t, WithCompression())
	f := a.files["datastar.js"]
	for coding, c := range f.coded {
		if c.body != nil {
			t.Fatalf("%s compressed at Load", coding)
		}
	}
	br, gz := f.coded[compress.Brotli], f.coded[compress.Gzip]
	if br == nil || gz == nil {
		t.Fatal("datastar.js has no compressed variants")
	}
	accept := map[string]string{"Accept-Encoding": "br"}
	first := get(t, a, http.MethodGet, a.URL("datastar.js"), accept)
	kept := br.body
	if len(kept) == 0 || !bytes.Equal(first.Body.Bytes(), kept) {
		t.Fatal("the Brotli variant was not kept")
	}
	if gz.body != nil {
		t.Error("the gzip variant was made without a request for it")
	}
	get(t, a, http.MethodGet, a.URL("datastar.js"), accept)
	if len(br.body) == 0 || &br.body[0] != &kept[0] {
		t.Error("the second request compressed the file again")
	}
}

// Concurrent first requests share one compression and all get the whole body (run with -race).
func TestConcurrentFirstRequests(t *testing.T) {
	a := load(t, WithCompression())
	want := a.files["fc-diagram.js"].body
	var wg sync.WaitGroup
	bodies := make([][]byte, 16)
	for i := range bodies {
		wg.Go(func() {
			r := httptest.NewRequest(http.MethodGet, a.URL("fc-diagram.js"), http.NoBody)
			r.Header.Set("Accept-Encoding", []string{"br", "gzip"}[i%2])
			rec := httptest.NewRecorder()
			a.Handler().ServeHTTP(rec, r)
			bodies[i] = decoded(t, rec.Header().Get("Content-Encoding"), rec.Body.Bytes())
		})
	}
	wg.Wait()
	for i, b := range bodies {
		if !bytes.Equal(b, want) {
			t.Errorf("request %d: decoded %d bytes, want %d", i, len(b), len(want))
		}
	}
}

// A compressed body that is not smaller than the file is not used; an application file of a type
// that is not compressible is never compressed.
func TestCompressionOnlyWhenItHelpsAndForCompressibleTypes(t *testing.T) {
	app := fstest.MapFS{"tiny.css": {Data: []byte("a")}, "logo.png": {Data: bytes.Repeat([]byte("x"), 4096)}}
	a := load(t, WithCompression(), WithFiles(app, "tiny.css", "logo.png"))
	rec := get(t, a, http.MethodGet, a.URL("tiny.css"), map[string]string{"Accept-Encoding": "br"})
	if rec.Header().Get("Content-Encoding") != "" || rec.Header().Get("ETag") != a.files["tiny.css"].etag || rec.Body.String() != "a" ||
		rec.Header().Get("Vary") != "Accept-Encoding" {
		t.Errorf("tiny.css: %v %q", rec.Header(), rec.Body.String())
	}
	if a.files["logo.png"].coded != nil {
		t.Error("an image/png file has compressed variants")
	}
	if rec := get(t, a, http.MethodGet, a.URL("logo.png"), map[string]string{"Accept-Encoding": "br"}); rec.Header().Get("Content-Encoding") != "" ||
		strings.Contains(rec.Header().Get("Vary"), "Accept-Encoding") {
		t.Errorf("logo.png: %v", rec.Header())
	}
}
