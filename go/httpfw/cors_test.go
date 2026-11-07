package httpfw_test

import (
	"context"
	"fmt"
	"io"
	"maps"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// startCORS runs a server with GET /api/items (requires a context: the resolver counts its calls and
// refuses everything) and GET /public, and global middleware that counts the OPTIONS requests it
// sees (application middleware, such as a rate limit, which a preflight never reaches).
func startCORS(t *testing.T, env map[string]string) (string, *atomic.Int32, error) {
	t.Helper()
	e := map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}
	maps.Copy(e, env)
	app := fw.New("cors-test", fw.WithEnvironment(e), fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard),
		fw.WithLogger(slogDiscard()))
	web := httpfw.Declare(app)
	var resolved atomic.Int32
	var srv *httpfw.Server
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		rt := httpfw.NewRoutes("test")
		rt.Use(func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
			return func(w http.ResponseWriter, r *http.Request) error {
				if r.Method == http.MethodOptions {
					resolved.Add(1)
				}
				return next(w, r)
			}
		})
		rt.HandleContext("GET /api/items", func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error {
			return jsonBody(w, `[]`)
		})
		rt.Handle("GET /public", func(w http.ResponseWriter, _ *http.Request) error { return jsonBody(w, `{}`) })
		s, err := httpfw.NewServer(b, web, []*httpfw.Routes{rt}, httpfw.WithResolver(resolverFunc(func(*http.Request) (fw.Context, error) {
			resolved.Add(1)
			return fw.Context{}, httpfw.Unauthenticated("no")
		})))
		srv = s
		return s, err
	})
	if err != nil {
		return "", nil, err
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	if srv == nil {
		t.Fatal("no server was built")
	}
	return fmt.Sprintf("http://127.0.0.1:%d", srv.Port()), &resolved, nil
}

func send(t *testing.T, method, url string, headers ...string) (int, http.Header) {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), method, url, nil)
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
	_, _ = io.Copy(io.Discard, res.Body)
	res.Body.Close()
	return res.StatusCode, res.Header
}

func corsHeaders(h http.Header) string {
	var out []string
	for name := range h {
		if strings.HasPrefix(name, "Access-Control-") {
			out = append(out, name)
		}
	}
	return strings.Join(out, ",")
}

const appOrigin = "https://app.example.com"

func TestCORSIsOffByDefault(t *testing.T) {
	t.Parallel()
	base, _, err := startCORS(t, nil)
	if err != nil {
		t.Fatal(err)
	}
	status, h := send(t, "OPTIONS", base+"/api/items", "Origin", appOrigin, "Access-Control-Request-Method", "GET")
	if status != 204 || h.Get("Allow") == "" || corsHeaders(h) != "" {
		t.Errorf("preflight: %d %v", status, h)
	}
	if _, h := send(t, "GET", base+"/public", "Origin", appOrigin); corsHeaders(h) != "" || h.Get("Vary") != "" {
		t.Errorf("request: %v", h)
	}
}

func TestCORSAnswersAPreflightFromAnAllowedOriginWithoutAuthenticatingIt(t *testing.T) {
	t.Parallel()
	base, resolved, err := startCORS(t, map[string]string{
		"HTTP_CORS_ALLOWED_ORIGINS": appOrigin + ", https://admin.example.com:8443",
	})
	if err != nil {
		t.Fatal(err)
	}
	status, h := send(t, "OPTIONS", base+"/api/items", "Origin", appOrigin,
		"Access-Control-Request-Method", "POST", "Access-Control-Request-Headers", "authorization, content-type")
	if status != 204 || h.Get("Access-Control-Allow-Origin") != appOrigin ||
		h.Get("Access-Control-Allow-Methods") != "GET, HEAD, POST, PUT, PATCH, DELETE" ||
		h.Get("Access-Control-Allow-Headers") != "Authorization, Content-Type, DPoP, Idempotency-Key, Last-Event-ID, X-Correlation-Id" ||
		h.Get("Access-Control-Max-Age") != "600" || h.Get("Access-Control-Allow-Credentials") != "" ||
		!strings.Contains(h.Get("Vary"), "Origin") {
		t.Errorf("preflight: %d %v", status, h)
	}
	if n := resolved.Load(); n != 0 {
		t.Errorf("the preflight reached the application's middleware or the resolver %d times", n)
	}
	// the request itself: the origin is allowed to read the answer, which still needs a caller
	status, h = send(t, "GET", base+"/api/items", "Origin", "https://admin.example.com:8443")
	if status != 401 || h.Get("Access-Control-Allow-Origin") != "https://admin.example.com:8443" ||
		h.Get("Access-Control-Expose-Headers") != "X-Correlation-Id, Idempotency-Replayed" || !strings.Contains(h.Get("Vary"), "Origin") {
		t.Errorf("request: %d %v", status, h)
	}
	// another origin is told nothing; a request without an Origin is not a CORS request
	for _, origin := range []string{"https://evil.example.com", "http://app.example.com", ""} {
		status, h := send(t, "OPTIONS", base+"/api/items", "Origin", origin, "Access-Control-Request-Method", "GET")
		if status != 204 || corsHeaders(h) != "" {
			t.Errorf("preflight from %q: %d %v", origin, status, h)
		}
		if _, h := send(t, "GET", base+"/public", "Origin", origin); corsHeaders(h) != "" {
			t.Errorf("request from %q: %v", origin, h)
		}
	}
}

func TestCORSSettings(t *testing.T) {
	t.Parallel()
	base, _, err := startCORS(t, map[string]string{
		"HTTP_CORS_ALLOWED_ORIGINS": appOrigin, "HTTP_CORS_ALLOW_CREDENTIALS": "true", "HTTP_CORS_MAX_AGE": "1h",
		"HTTP_CORS_ALLOWED_METHODS": "GET,POST", "HTTP_CORS_ALLOWED_HEADERS": "Authorization", "HTTP_CORS_EXPOSED_HEADERS": "Location",
	})
	if err != nil {
		t.Fatal(err)
	}
	_, h := send(t, "OPTIONS", base+"/public", "Origin", appOrigin, "Access-Control-Request-Method", "GET")
	if h.Get("Access-Control-Allow-Credentials") != "true" || h.Get("Access-Control-Max-Age") != "3600" ||
		h.Get("Access-Control-Allow-Methods") != "GET, POST" || h.Get("Access-Control-Allow-Headers") != "Authorization" {
		t.Errorf("preflight: %v", h)
	}
	if _, h := send(t, "GET", base+"/public", "Origin", appOrigin); h.Get("Access-Control-Allow-Credentials") != "true" ||
		h.Get("Access-Control-Expose-Headers") != "Location" {
		t.Errorf("request: %v", h)
	}
	// any origin, without credentials
	base, _, err = startCORS(t, map[string]string{"HTTP_CORS_ALLOWED_ORIGINS": "*"})
	if err != nil {
		t.Fatal(err)
	}
	if _, h := send(t, "GET", base+"/public", "Origin", "https://anyone.example"); h.Get("Access-Control-Allow-Origin") != "*" {
		t.Errorf("any origin: %v", h)
	}
	for _, c := range []struct {
		env  map[string]string
		want string
	}{
		{map[string]string{"HTTP_CORS_ALLOWED_ORIGINS": "https://app.example.com/path"}, "http.cors.allowed-origins"},
		{map[string]string{"HTTP_CORS_ALLOWED_ORIGINS": "app.example.com"}, "http.cors.allowed-origins"},
		{map[string]string{"HTTP_CORS_ALLOWED_ORIGINS": "*", "HTTP_CORS_ALLOW_CREDENTIALS": "true"}, "credentials"},
		{map[string]string{"HTTP_CORS_ALLOWED_ORIGINS": appOrigin, "HTTP_CORS_ALLOWED_METHODS": "GET,BREW"}, "http.cors.allowed-methods"},
	} {
		if _, _, err := startCORS(t, c.env); err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%v: got %v, want a start-up problem naming %q", c.env, err, c.want)
		}
	}
}
