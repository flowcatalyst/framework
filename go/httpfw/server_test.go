package httpfw_test

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

type started struct {
	run    *fw.Running
	server *httpfw.Server
	base   string
}

// start launches an application whose only HTTP routes are the ones setup registers, on a free port
// of the loopback interface.
func start(t *testing.T, env map[string]string, setup func(b *fw.Boot, rt *httpfw.Routes)) (*started, error) {
	t.Helper()
	e := map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}
	maps.Copy(e, env)
	app := fw.New("http-test", fw.WithEnvironment(e), fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard),
		fw.WithLogger(slogDiscard()))
	web := httpfw.Declare(app)
	var srv *httpfw.Server
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		rt := httpfw.NewRoutes("test")
		if setup != nil {
			setup(b, rt)
		}
		s, err := httpfw.NewServer(b, web, []*httpfw.Routes{rt})
		srv = s
		return s, err
	})
	if err != nil {
		return nil, err
	}
	if srv == nil {
		return nil, errors.New("no server was built")
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return &started{run: run, server: srv, base: fmt.Sprintf("http://127.0.0.1:%d", srv.Port())}, nil
}

func get(t *testing.T, url string, headers ...string) (int, http.Header, string) {
	t.Helper()
	req, err := http.NewRequestWithContext(t.Context(), http.MethodGet, url, nil)
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
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return res.StatusCode, res.Header, string(b)
}

func TestServerAnswersOnAFreePortWithoutServerHeader(t *testing.T) {
	t.Parallel()
	s, err := start(t, nil, func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("GET /hello", func(w http.ResponseWriter, _ *http.Request) error { return jsonBody(w, `{"hello":"world"}`) })
	})
	if err != nil {
		t.Fatal(err)
	}
	if s.server.Port() == 0 || s.server.Port() == 8080 {
		t.Fatalf("port %d", s.server.Port())
	}
	status, h, body := get(t, s.base+"/hello")
	if status != 200 || body != `{"hello":"world"}` {
		t.Fatalf("%d %s", status, body)
	}
	for _, name := range []string{"Server", "X-Powered-By"} {
		if v := h.Get(name); v != "" {
			t.Errorf("%s header %q must not be sent", name, v)
		}
	}
	_, h, body = get(t, s.base+"/missing")
	if strings.Contains(body, "goroutine") || strings.Contains(body, ".go:") {
		t.Errorf("a stack trace in %s", body)
	}
	_ = h
}

func TestHealthEndpoints(t *testing.T) {
	t.Parallel()
	var failCritical, failInformational bool
	s, err := start(t, nil, func(b *fw.Boot, rt *httpfw.Routes) {
		b.Health("db", fw.Critical, func(context.Context) error {
			if failCritical {
				return errors.New("connection refused")
			}
			return nil
		})
		b.Health("cache", fw.Informational, func(context.Context) error {
			if failInformational {
				return errors.New("cold")
			}
			return nil
		})
	})
	if err != nil {
		t.Fatal(err)
	}
	status, _, body := get(t, s.base+"/health/live")
	if status != 200 || body != `{"status":"UP"}` {
		t.Errorf("live: %d %s", status, body)
	}
	status, h, body := get(t, s.base+"/health/ready")
	if status != 200 || body != `{"status":"UP","checks":{"db":{"status":"UP"},"cache":{"status":"UP","informational":true}}}` {
		t.Errorf("ready: %d %s", status, body)
	}
	if h.Get("Content-Type") != "application/json" {
		t.Errorf("content type %q", h.Get("Content-Type"))
	}
	failInformational = true
	status, _, body = get(t, s.base+"/health/ready")
	if status != 200 || body != `{"status":"DEGRADED","checks":{"db":{"status":"UP"},"cache":{"status":"DOWN","reason":"cold","informational":true}}}` {
		t.Errorf("degraded: %d %s", status, body)
	}
	failCritical = true
	status, _, body = get(t, s.base+"/health/ready")
	if status != 503 || body != `{"status":"DOWN","checks":{"db":{"status":"DOWN","reason":"connection refused"},"cache":{"status":"DOWN","reason":"cold","informational":true}}}` {
		t.Errorf("down: %d %s", status, body)
	}
	if status, _, _ := get(t, s.base+"/health/live"); status != 200 {
		t.Errorf("live does not depend on checks: %d", status)
	}
}

func TestMetricsEndpoint(t *testing.T) {
	t.Parallel()
	hello := func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("GET /hello/{n}", func(w http.ResponseWriter, _ *http.Request) error { return jsonBody(w, `{}`) })
	}
	off, err := start(t, nil, hello)
	if err != nil {
		t.Fatal(err)
	}
	if status, _, _ := get(t, off.base+"/metrics"); status != 404 {
		t.Errorf("disabled by default: %d", status)
	}

	on, err := start(t, map[string]string{"HTTP_METRICS_ENABLED": "true", "HTTP_METRICS_BEARER_TOKEN": "s3cret", "HTTP_METRICS_PATH": "/internal/metrics"}, hello)
	if err != nil {
		t.Fatal(err)
	}
	get(t, on.base+"/hello/1")
	get(t, on.base+"/hello/2")
	get(t, on.base+"/nowhere")
	if status, _, body := get(t, on.base+"/internal/metrics"); status != 401 || body != `{"error":"UNAUTHENTICATED","message":"a bearer token is required"}` {
		t.Errorf("without a token: %d %s", status, body)
	}
	if status, _, _ := get(t, on.base+"/internal/metrics", "Authorization", "Bearer wrong"); status != 401 {
		t.Errorf("a wrong token: %d", status)
	}
	status, h, body := get(t, on.base+"/internal/metrics", "Authorization", "Bearer s3cret")
	if status != 200 || !strings.HasPrefix(h.Get("Content-Type"), "text/plain; version=0.0.4") {
		t.Fatalf("with the token: %d %q", status, h.Get("Content-Type"))
	}
	for _, want := range []string{
		`fw_http_server_requests_total{method="GET",route="/hello/{n}",status="200"} 2`,
		`fw_http_server_requests_total{method="GET",route="<unmatched>",status="404"} 1`,
		`fw_http_server_request_duration_seconds_count{method="GET",route="/hello/{n}"} 2`,
		"# TYPE fw_http_server_request_duration_seconds histogram",
	} {
		if !strings.Contains(body, want) {
			t.Errorf("missing %q in\n%s", want, body)
		}
	}
	if strings.Contains(body, "/hello/1") {
		t.Errorf("a request path became a label value:\n%s", body)
	}
}

func TestSettingsAreCheckedAtStartup(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name string
		env  map[string]string
		want string
	}{
		{"port out of range", map[string]string{"HTTP_PORT": "70000"}, "http.port"},
		{"port below range", map[string]string{"HTTP_PORT": "-1"}, "must be 0 to 65535, was -1"},
		{"negative body limit", map[string]string{"HTTP_MAX_BODY_BYTES": "-5"}, "http.max-body-bytes"},
		{"bad timeout", map[string]string{"HTTP_REQUEST_TIMEOUT": "soon"}, "http.request-timeout"},
		{"metrics path with a parameter", map[string]string{"HTTP_METRICS_PATH": "/m/{x}"}, "must be literal segments such as /metrics"},
		{"metrics path with a trailing slash", map[string]string{"HTTP_METRICS_PATH": "/metrics/"}, "http.metrics.path"},
	} {
		_, err := start(t, c.env, nil)
		if err == nil || !strings.Contains(err.Error(), c.want) {
			t.Errorf("%s: want a problem containing %q, got %v", c.name, c.want, err)
		}
	}
}

func TestAMisspeltHttpKeyFailsStartup(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	writeFile(t, dir+"/application.yaml", "http:\n  prot: 9000\n  host: 127.0.0.1\n")
	app := fw.New("http-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(dir), fw.WithOutput(io.Discard, io.Discard))
	httpfw.Declare(app)
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if err == nil || !strings.Contains(err.Error(), "unknown key") || !strings.Contains(err.Error(), "http.port") {
		t.Fatalf("want an unknown-key problem listing the http keys, got %v", err)
	}
}

func TestAnAddressThatCannotBeBoundFailsTheStart(t *testing.T) {
	t.Parallel()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = ln.Close() }()
	port := ln.Addr().(*net.TCPAddr).Port
	_, err = start(t, map[string]string{"HTTP_PORT": fmt.Sprint(port)}, nil)
	if err == nil || !strings.Contains(err.Error(), "cannot listen on") {
		t.Fatalf("want a bind failure, got %v", err)
	}
}

func TestBodyOverTheLimitOnARealSocketIs413AndTheConnectionCloses(t *testing.T) {
	t.Parallel()
	s, err := start(t, map[string]string{"HTTP_MAX_BODY_BYTES": "32"}, func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("POST /sink", func(w http.ResponseWriter, r *http.Request) error {
			_, err := httpfw.DecodeJSON[map[string]any](r)
			if err != nil {
				return err
			}
			return jsonBody(w, `{}`)
		})
	})
	if err != nil {
		t.Fatal(err)
	}
	big := `{"k":"` + strings.Repeat("x", 100) + `"}`
	req, err := http.NewRequestWithContext(t.Context(), http.MethodPost, s.base+"/sink", strings.NewReader(big))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 413 || !strings.Contains(string(body), `"PAYLOAD_TOO_LARGE"`) {
		t.Errorf("declared length: %d %s", res.StatusCode, body)
	}
	if !res.Close && res.Header.Get("Connection") != "close" {
		t.Errorf("the connection must close after a 413, headers %v", res.Header)
	}
	// without a declared length: counted while reading
	req, err = http.NewRequestWithContext(t.Context(), http.MethodPost, s.base+"/sink", io.NopCloser(strings.NewReader(big)))
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Content-Type", "application/json")
	res, err = http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 413 {
		t.Errorf("chunked: %d", res.StatusCode)
	}
}

func TestAnUnknownMethodIsAnsweredWithTheEnvelope(t *testing.T) {
	t.Parallel()
	s, err := start(t, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("tcp", strings.TrimPrefix(s.base, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_, _ = fmt.Fprint(conn, "BREW /pot HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
	res, err := http.ReadResponse(bufio.NewReader(conn), nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	if res.StatusCode != 405 || res.Header.Get("Allow") != "DELETE, GET, HEAD, OPTIONS, PATCH, POST, PUT" ||
		string(b) != `{"error":"METHOD_NOT_ALLOWED","message":"method not allowed"}` {
		t.Errorf("%d %v %s", res.StatusCode, res.Header, b)
	}
}

func TestASlowRequestIsCutOffAtTheRequestTimeout(t *testing.T) {
	t.Parallel()
	s, err := start(t, map[string]string{"HTTP_REQUEST_TIMEOUT": "300ms"}, nil)
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("tcp", strings.TrimPrefix(s.base, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	_, _ = fmt.Fprint(conn, "GET /health/live HTTP/1.1\r\nHost: x\r\n") // the headers never finish
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	began := time.Now()
	_, err = io.ReadAll(conn)
	if err != nil {
		t.Fatalf("the server should have closed the connection: %v", err)
	}
	if took := time.Since(began); took < 250*time.Millisecond || took > 3*time.Second {
		t.Errorf("closed after %v, want about 300ms", took)
	}
}

func TestABodyThatStallsIsCutOffAtTheRequestTimeout(t *testing.T) {
	t.Parallel()
	s, err := start(t, map[string]string{"HTTP_REQUEST_TIMEOUT": "300ms"}, func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("POST /sink", func(w http.ResponseWriter, r *http.Request) error {
			_, err := httpfw.DecodeJSON[map[string]any](r)
			return err
		})
	})
	if err != nil {
		t.Fatal(err)
	}
	conn, err := net.Dial("tcp", strings.TrimPrefix(s.base, "http://"))
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	// complete headers (so the header timeout is satisfied), then a body that never completes
	_, _ = fmt.Fprint(conn, "POST /sink HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\nContent-Length: 100\r\n\r\n{\"k\":")
	_ = conn.SetReadDeadline(time.Now().Add(5 * time.Second))
	began := time.Now()
	_, err = io.ReadAll(conn)
	if took := time.Since(began); err != nil || took < 250*time.Millisecond || took > 3*time.Second {
		t.Errorf("closed after %v (err %v), want about 300ms", took, err)
	}
}

func TestGracefulShutdownLetsAnInFlightRequestFinish(t *testing.T) {
	t.Parallel()
	enter, release := make(chan struct{}), make(chan struct{})
	s, err := start(t, map[string]string{"HTTP_SHUTDOWN_GRACE": "5s"}, func(b *fw.Boot, rt *httpfw.Routes) {
		rt.Handle("GET /slow", func(w http.ResponseWriter, _ *http.Request) error {
			close(enter)
			<-release
			return jsonBody(w, `{"finished":true}`)
		})
	})
	if err != nil {
		t.Fatal(err)
	}
	type answer struct {
		status int
		body   string
	}
	inflight := make(chan answer, 1)
	go func() {
		status, _, body := get(t, s.base+"/slow")
		inflight <- answer{status, body}
	}()
	<-enter

	shutdownDone := make(chan error, 1)
	go func() { shutdownDone <- s.run.Shutdown() }()
	deadline := time.Now().Add(5 * time.Second)
	for !s.server.IsDraining() {
		if time.Now().After(deadline) {
			t.Fatal("the server never began draining")
		}
		time.Sleep(5 * time.Millisecond)
	}
	// ready reports down from the moment shutdown begins, and a request that still arrives is refused
	rec := newRecorder()
	s.server.Handler().ServeHTTP(rec, request("GET", "/health/ready", ""))
	if rec.Code != 503 || rec.Header().Get("Connection") != "close" || !strings.Contains(rec.Body.String(), `"UNAVAILABLE"`) {
		t.Errorf("during the drain: %d %v %s", rec.Code, rec.Header(), rec.Body.String())
	}
	// the listener is closed: no new connection
	if _, err := net.DialTimeout("tcp", strings.TrimPrefix(s.base, "http://"), time.Second); err == nil {
		t.Error("a new connection was accepted during the drain")
	}
	select {
	case <-shutdownDone:
		t.Fatal("shutdown returned while a request was still running")
	case <-time.After(100 * time.Millisecond):
	}
	close(release)
	if a := <-inflight; a.status != 200 || a.body != `{"finished":true}` {
		t.Errorf("the in-flight request: %d %s", a.status, a.body)
	}
	if err := <-shutdownDone; err != nil {
		t.Errorf("shutdown: %v", err)
	}
}

func TestRequestsStillRunningAfterTheGraceAreClosed(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name string
		env  map[string]string
	}{
		{"http.shutdown-grace", map[string]string{"HTTP_SHUTDOWN_GRACE": "150ms"}},
		{"app.shutdown.drain when no grace is set", map[string]string{"APP_SHUTDOWN_DRAIN": "150ms"}},
	} {
		enter, release := make(chan struct{}), make(chan struct{})
		s, err := start(t, c.env, func(b *fw.Boot, rt *httpfw.Routes) {
			rt.Handle("GET /stuck", func(w http.ResponseWriter, _ *http.Request) error {
				close(enter)
				<-release
				return nil
			})
		})
		if err != nil {
			t.Fatal(err)
		}
		failed := make(chan error, 1)
		go func() {
			res, err := http.Get(s.base + "/stuck") //nolint:noctx // a test client
			if err == nil {
				res.Body.Close()
			}
			failed <- err
		}()
		<-enter
		began := time.Now()
		_ = s.run.Shutdown()
		if took := time.Since(began); took < 100*time.Millisecond || took > 3*time.Second {
			t.Errorf("%s: shutdown took %v, want about 150ms", c.name, took)
		}
		if err := <-failed; err == nil {
			t.Errorf("%s: the stuck request should have been closed, not answered", c.name)
		}
		close(release)
	}
}

func TestReadyIsDownFromTheMomentShutdownBegins(t *testing.T) {
	t.Parallel()
	s, err := start(t, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if status, _, _ := get(t, s.base+"/health/ready"); status != 200 {
		t.Fatalf("ready before shutdown: %d", status)
	}
	_ = s.run.Shutdown()
	rec := newRecorder()
	s.server.Handler().ServeHTTP(rec, request("GET", "/health/ready", ""))
	if rec.Code != 503 {
		t.Errorf("after shutdown: %d %s", rec.Code, rec.Body.String())
	}
}

func writeFile(t *testing.T, path, text string) {
	t.Helper()
	if err := writeText(path, text); err != nil {
		t.Fatal(err)
	}
}

var _ = bytes.NewBuffer
