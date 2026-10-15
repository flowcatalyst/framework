package conformance_test

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"testing"
	"testing/fstest"

	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

const spaVector = "http/spa.json"

type spaDoc struct {
	Fixture map[string]string `json:"fixture"`
	SPA     struct {
		Prefix      string   `json:"prefix"`
		APIPrefixes []string `json:"apiPrefixes"`
	} `json:"spa"`
	Routes []struct {
		Method  string `json:"method"`
		Pattern string `json:"pattern"`
	} `json:"routes"`
	Cases []spaCase `json:"cases"`
}

type spaCase struct {
	Request struct {
		Method  string            `json:"method"`
		Path    string            `json:"path"`
		Headers map[string]string `json:"headers"`
	} `json:"request"`
	Expect struct {
		Status     int               `json:"status"`
		Headers    map[string]string `json:"headers"`
		BodyLength int               `json:"bodyLength"`
		BodySha256 string            `json:"bodySha256"`
	} `json:"expect"`
}

// The SPA fallback answers every request of conformance/http/spa.json as the Java framework does:
// status, every header except X-Correlation-Id and Content-Length, the body's length (the
// Content-Length sent, for HEAD) and its SHA-256. The vector's SPA uses the baseline policy (the
// Java builder's default; Go's default is the strict policy the contract rules).
func TestSPAVector(t *testing.T) {
	t.Parallel()
	var doc spaDoc
	conformance.ReadJSON(t, spaVector, &doc)
	if len(doc.Cases) == 0 {
		t.Fatalf("%s: no cases", spaVector)
	}
	build := fstest.MapFS{}
	for name, text := range doc.Fixture {
		build[name] = &fstest.MapFile{Data: []byte(text)}
	}
	s, err := spa.New(build, spa.Options{Prefix: doc.SPA.Prefix, APIPrefixes: doc.SPA.APIPrefixes, CSP: spa.Baseline()})
	if err != nil {
		t.Fatal(err)
	}
	table := httpfw.NewRoutes("vector")
	for _, r := range doc.Routes {
		table.Handle(r.Method+" "+r.Pattern, func(w http.ResponseWriter, _ *http.Request) error {
			return httpfw.JSON(w, 200, map[string]string{"route": r.Pattern})
		})
	}
	table.SPA(s)
	d, err := httpfw.Compile([]*httpfw.Routes{table}, httpfw.WithEnvironment("test"))
	if err != nil {
		t.Fatal(err)
	}

	for _, c := range doc.Cases {
		name := c.Request.Method + " " + c.Request.Path
		where := conformance.Where(spaVector, name, "answer")
		rec := httptest.NewRecorder()
		d.ServeHTTP(rec, vectorRequest(c.Request.Method, c.Request.Path, c.Request.Headers))
		res := rec.Result()
		body, _ := io.ReadAll(res.Body)

		if res.StatusCode != c.Expect.Status {
			t.Errorf("%s: status want %d, got %d", where, c.Expect.Status, res.StatusCode)
		}
		got := map[string]string{}
		for k, v := range res.Header {
			if k == "X-Correlation-Id" || k == "Content-Length" {
				continue
			}
			got[k] = strings.Join(v, ", ")
		}
		if !mapsEqual(got, c.Expect.Headers) {
			t.Errorf("%s: headers differ\n want: %v\n  got: %v", where, c.Expect.Headers, got)
		}
		length := len(body)
		if c.Request.Method == "HEAD" {
			length, _ = strconv.Atoi(res.Header.Get("Content-Length"))
			if len(body) != 0 {
				t.Errorf("%s: a HEAD answer carries a body of %d bytes", where, len(body))
			}
		}
		if length != c.Expect.BodyLength {
			t.Errorf("%s: body length want %d, got %d", where, c.Expect.BodyLength, length)
		}
		sum := sha256.Sum256(body)
		if h := hex.EncodeToString(sum[:]); h != c.Expect.BodySha256 {
			t.Errorf("%s: body sha256 want %s, got %s (body %q)", where, c.Expect.BodySha256, h, excerptOf(body))
		}
	}
}

func excerptOf(b []byte) string {
	if len(b) > 120 {
		return string(b[:120]) + "..."
	}
	return string(b)
}

func mapsEqual(a, b map[string]string) bool {
	if len(a) != len(b) {
		return false
	}
	for k, v := range a {
		if b[k] != v {
			return false
		}
	}
	return true
}

// vectorRequest builds a request the way a server hands it over: the target as sent in
// RequestURI, which the router splits before it decodes.
func vectorRequest(method, target string, headers map[string]string) *http.Request {
	r := &http.Request{
		Method:     method,
		RequestURI: target,
		URL:        &url.URL{Path: target, RawPath: target},
		Header:     http.Header{},
		Body:       http.NoBody,
		Proto:      "HTTP/1.1", ProtoMajor: 1, ProtoMinor: 1,
		Host: "example.test",
	}
	if u, err := url.ParseRequestURI(target); err == nil {
		r.URL = u
	}
	for k, v := range headers {
		r.Header.Set(k, v)
	}
	return r
}

var (
	_ = json.Marshal
	_ = fmt.Sprint
	_ = slices.Contains[[]string]
)
