package httpfw_test

import (
	"encoding/json"
	"net/http"
	"net/url"
	"testing"

	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
)

func guardApp(t *testing.T, g *httpfw.CSRFGuard, mw ...httpfw.Middleware) *httpfw.Dispatcher {
	t.Helper()
	rt := httpfw.NewRoutes("csrf")
	h := func(w http.ResponseWriter, _ *http.Request) error {
		return httpfw.JSON(w, http.StatusOK, map[string]string{"ok": "yes"})
	}
	rt.Handle("GET /thing", h, httpfw.With(g.Middleware()))
	rt.Handle("POST /thing", h, httpfw.With(g.Middleware()))
	rt.Handle("DELETE /thing", h, httpfw.With(g.Middleware()))
	for _, m := range mw {
		rt.Use(m)
	}
	return compile(t, nil, rt)
}

func baseURL(t *testing.T, s string) *url.URL {
	t.Helper()
	u, err := url.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return u
}

func code(r result) string {
	if r.status < 400 {
		return ""
	}
	var out struct {
		Error string `json:"error"`
	}
	_ = json.Unmarshal([]byte(r.body), &out)
	return out.Error
}

func TestCSRFSessionsGuardRules(t *testing.T) {
	d := guardApp(t, httpfw.SessionsGuard(baseURL(t, "https://app.example.com")))
	jsonType := []string{"Content-Type", "application/json"}
	cases := []struct {
		name    string
		method  string
		body    string
		headers []string
		status  int
		code    string
	}{
		{"safe method needs nothing", "GET", "", nil, 200, ""},
		{"same-origin", "POST", "", []string{"Sec-Fetch-Site", "same-origin"}, 200, ""},
		{"none (a user typed it)", "POST", "", []string{"Sec-Fetch-Site", "none"}, 200, ""},
		{"same-site is cross-origin", "POST", "", []string{"Sec-Fetch-Site", "same-site"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"cross-site", "POST", "", []string{"Sec-Fetch-Site", "cross-site"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"Sec-Fetch-Site wins over a matching Origin", "POST", "", []string{"Sec-Fetch-Site", "cross-site", "Origin", "https://app.example.com"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"an empty Sec-Fetch-Site is refused, not read as absent", "POST", "", []string{"Sec-Fetch-Site", "", "Origin", "https://app.example.com"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"Origin matches", "POST", "", []string{"Origin", "https://app.example.com"}, 200, ""},
		{"Origin default port", "POST", "", []string{"Origin", "https://APP.example.com:443"}, 200, ""},
		{"Origin differs", "POST", "", []string{"Origin", "https://evil.example.com"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"Origin other scheme", "POST", "", []string{"Origin", "http://app.example.com"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"Origin other port", "POST", "", []string{"Origin", "https://app.example.com:8443"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"Origin null", "POST", "", []string{"Origin", "null"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"Origin with a path", "POST", "", []string{"Origin", "https://app.example.com/x"}, 403, "CROSS_ORIGIN_REQUEST"},
		{"neither header is refused", "POST", "", nil, 403, "CROSS_ORIGIN_REQUEST"},
		{"delete is unsafe too", "DELETE", "", nil, 403, "CROSS_ORIGIN_REQUEST"},
		{"json body", "POST", `{"a":1}`, append([]string{"Sec-Fetch-Site", "same-origin"}, jsonType...), 200, ""},
		{"+json body", "POST", `{"a":1}`, []string{"Sec-Fetch-Site", "same-origin", "Content-Type", "application/merge-patch+json; charset=utf-8"}, 200, ""},
		{"form body", "POST", `a=1`, []string{"Sec-Fetch-Site", "same-origin", "Content-Type", "application/x-www-form-urlencoded"}, 415, "UNSUPPORTED_MEDIA_TYPE"},
		{"text body", "POST", `a`, []string{"Sec-Fetch-Site", "same-origin", "Content-Type", "text/plain"}, 415, "UNSUPPORTED_MEDIA_TYPE"},
		{"body without content type", "POST", `a`, []string{"Sec-Fetch-Site", "same-origin"}, 415, "UNSUPPORTED_MEDIA_TYPE"},
		{"the provenance check comes before the media type", "POST", `a`, []string{"Content-Type", "text/plain"}, 403, "CROSS_ORIGIN_REQUEST"},
	}
	for _, c := range cases {
		r := do(d, c.method, "/thing", c.body, c.headers...)
		if r.status != c.status || code(r) != c.code {
			t.Errorf("%s: got %d %q, want %d %q (%s)", c.name, r.status, code(r), c.status, c.code, r.body)
		}
	}
}

func TestCSRFPublicFormGuardLetsAHeaderlessRequestThrough(t *testing.T) {
	d := guardApp(t, httpfw.PublicFormGuard(baseURL(t, "https://app.example.com")))
	if r := do(d, "POST", "/thing", ""); r.status != 200 {
		t.Fatalf("a headerless body-less request must pass: %d %s", r.status, r.body)
	}
	if r := do(d, "POST", "/thing", `{"a":1}`, "Content-Type", "application/json"); r.status != 200 {
		t.Fatalf("json: %d", r.status)
	}
	if r := do(d, "POST", "/thing", `a=1`, "Content-Type", "application/x-www-form-urlencoded"); r.status != 415 {
		t.Fatalf("a form body is refused for every caller: %d", r.status)
	}
	if r := do(d, "POST", "/thing", "", "Sec-Fetch-Site", "cross-site"); r.status != 403 {
		t.Fatalf("a present cross-site header is still refused: %d", r.status)
	}
}

func TestSecurityHeaders(t *testing.T) {
	rt := httpfw.NewRoutes("sec")
	rt.Use(httpfw.SecurityHeaders())
	rt.Handle("GET /plain", func(w http.ResponseWriter, _ *http.Request) error { return httpfw.JSON(w, 200, map[string]string{}) })
	rt.Handle("GET /own", func(w http.ResponseWriter, _ *http.Request) error {
		w.Header().Set("Content-Security-Policy", "default-src 'none'")
		return httpfw.JSON(w, 200, map[string]string{})
	})
	d := compile(t, nil, rt)
	r := do(d, "GET", "/plain", "")
	want := map[string]string{
		"Referrer-Policy": "same-origin", "X-Frame-Options": "DENY", "X-Content-Type-Options": "nosniff",
		"Content-Security-Policy": spa.Baseline().String(),
	}
	for k, v := range want {
		if got := r.header.Get(k); got != v {
			t.Errorf("%s = %q, want %q", k, got, v)
		}
	}
	if got := do(d, "GET", "/own", "").header.Get("Content-Security-Policy"); got != "default-src 'none'" {
		t.Errorf("a handler's own policy must stand: %q", got)
	}
}
