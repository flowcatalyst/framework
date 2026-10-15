package module_test

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpclient"
	"github.com/flowcatalyst/framework/go/httpclient/module"
	"github.com/flowcatalyst/framework/go/metrics"
)

type seenRequest struct {
	*http.Request
	body string
}

type seen struct {
	mu   sync.Mutex
	reqs []seenRequest
}

func (s *seen) add(r *http.Request) {
	b, _ := io.ReadAll(r.Body)
	s.mu.Lock()
	defer s.mu.Unlock()
	s.reqs = append(s.reqs, seenRequest{Request: r.Clone(context.Background()), body: string(b)})
}

func (s *seen) get() []seenRequest {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]seenRequest(nil), s.reqs...)
}

func server(t *testing.T) (*httptest.Server, *seen) {
	t.Helper()
	s := &seen{}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.add(r)
		if r.URL.Path == "/oauth/token" {
			_, _ = w.Write([]byte(`{"access_token":"tok","token_type":"Bearer","expires_in":300}`))
			return
		}
		_, _ = fmt.Fprintf(w, `{"id":%q}`, r.Header.Get("Authorization"))
	}))
	t.Cleanup(srv.Close)
	return srv, s
}

type result struct {
	clients *module.Clients
	reg     *metrics.Registry
	run     *fw.Running
}

// start declares the module, writes application.yaml and builds in a started application.
func start(t *testing.T, yaml string, env map[string]string, opts ...module.BuildOption) (result, error) {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte(yaml), 0o600); err != nil {
		t.Fatal(err)
	}
	e := map[string]string{"APP_ENV": "test"}
	maps.Copy(e, env)
	app := fw.New("clients-test", fw.WithEnvironment(e), fw.WithConfigDir(dir), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := module.Declare(app)
	var res result
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var err error
		res.clients, err = module.Build(ctx, b, d, opts...)
		res.reg = b.Metrics()
		return nil, err
	})
	if err != nil {
		return res, err
	}
	res.run = run
	t.Cleanup(func() { _ = run.Shutdown() })
	return res, nil
}

func problems(err error) []string {
	var se *fw.StartupError
	if !errors.As(err, &se) {
		return []string{fmt.Sprint(err)}
	}
	out := make([]string, len(se.Problems))
	for i, p := range se.Problems {
		out[i] = p.String()
	}
	return out
}

func mustContain(t *testing.T, err error, parts ...string) {
	t.Helper()
	if err == nil {
		t.Fatalf("no error, want one containing %q", parts)
	}
	all := strings.Join(problems(err), "\n")
	for _, p := range parts {
		if !strings.Contains(all, p) {
			t.Errorf("startup problems do not contain %q:\n%s", p, all)
		}
	}
}

func counter(reg *metrics.Registry, name string, labels ...string) float64 {
	for _, f := range reg.Snapshot().Families {
		if f.Name != name {
			continue
		}
		for _, s := range f.Samples {
			if strings.Join(s.LabelValues, ",") == strings.Join(labels, ",") {
				return s.Value
			}
		}
	}
	return -1
}

func TestAClientNamedInCodeSendsTheCorrelationIDAndIsMeasured(t *testing.T) {
	srv, got := server(t)
	r, err := start(t, "http-clients:\n  instances:\n    billing:\n      base-url: "+srv.URL+"/v1\n", nil,
		module.WithClient("billing", module.DefaultClientOptions().WithRetry(httpclient.NoRetry())))
	if err != nil {
		t.Fatal(err)
	}
	cx := fw.ContextFor(fw.MustClientID("acme"))
	c, err := r.clients.For("billing", cx)
	if err != nil {
		t.Fatal(err)
	}
	resp, err := c.Get("/ping").Send(t.Context())
	if err != nil || resp.Status() != 200 {
		t.Fatalf("%v %v", resp, err)
	}
	req := got.get()[0]
	if req.URL.Path != "/v1/ping" || req.Header.Get("X-Correlation-Id") != cx.Correlation().String() {
		t.Fatalf("request %s %v", req.URL.Path, req.Header)
	}
	if req.Header.Get("X-FlowCatalyst-Client") != "" {
		t.Fatal("the client id is sent to other services")
	}
	if n := counter(r.reg, "fw_http_client_requests_total", "billing", "GET", "200"); n != 1 {
		t.Fatalf("requests_total = %v", n)
	}
	if n := counter(r.reg, "fw_http_client_request_duration_seconds", "billing", "GET"); n < 0 {
		t.Fatalf("duration series missing")
	}
	plain, _ := r.clients.Get("billing")
	_, _ = plain.Get("/ping").Send(t.Context())
	if got.get()[1].Header.Get("X-Correlation-Id") != "" {
		t.Fatal("Get adds a correlation id")
	}
	if names := r.clients.Names(); len(names) != 1 || names[0] != "billing" {
		t.Fatalf("names %v", names)
	}
	if _, err := r.clients.Get("nope"); err == nil || !strings.Contains(err.Error(), "billing") {
		t.Fatalf("unknown client: %v", err)
	}
}

func TestClientCredentialsComeFromConfigurationWithTheSecretFromTheEnvironment(t *testing.T) {
	srv, got := server(t)
	yaml := "http-clients:\n  instances:\n    billing:\n      base-url: " + srv.URL + "\n      auth:\n        token-url: " + srv.URL + "/oauth/token\n        client-id: orders-service\n"
	r, err := start(t, yaml, map[string]string{"HTTP_CLIENTS_INSTANCES_BILLING_AUTH_CLIENT_SECRET": "from-env"},
		module.WithClient("billing", module.DefaultClientOptions().WithAuth(module.ClientCredentials().WithScope("billing").WithAudience("api"))))
	if err != nil {
		t.Fatal(err)
	}
	c, _ := r.clients.Get("billing")
	resp, err := c.Get("/me").Send(t.Context())
	if err != nil || resp.Text() != `{"id":"Bearer tok"}` {
		t.Fatalf("%v %v", resp, err)
	}
	token := got.get()[0]
	if token.URL.Path != "/oauth/token" || token.Method != "POST" {
		t.Fatalf("token request %s %s", token.Method, token.URL.Path)
	}
	for _, want := range []string{"grant_type=client_credentials", "scope=billing", "audience=api", "client_id=orders-service", "client_secret=from-env"} {
		if !strings.Contains(token.body, want) {
			t.Errorf("token request body %q lacks %s", token.body, want)
		}
	}
}

func TestEachAuthStrategyReadsItsCredentialsFromItsSection(t *testing.T) {
	srv, got := server(t)
	yaml := `http-clients:
  instances:
    bearer: { base-url: ` + srv.URL + `, auth: { token: tk } }
    basic: { base-url: ` + srv.URL + `, auth: { username: ada, password: pw } }
    key: { base-url: ` + srv.URL + `, auth: { value: k-1 } }
    open: { base-url: ` + srv.URL + ` }
`
	r, err := start(t, yaml, nil,
		module.WithClient("bearer", module.DefaultClientOptions().WithAuth(module.Bearer())),
		module.WithClient("basic", module.DefaultClientOptions().WithAuth(module.Basic())),
		module.WithClient("key", module.DefaultClientOptions().WithAuth(module.Header("X-Api-Key"))),
		module.WithClient("open", module.DefaultClientOptions()))
	if err != nil {
		t.Fatal(err)
	}
	for _, n := range []string{"bearer", "basic", "key", "open"} {
		c, _ := r.clients.Get(n)
		if _, err := c.Get("/x").Send(t.Context()); err != nil {
			t.Fatal(err)
		}
	}
	reqs := got.get()
	if a := reqs[0].Header.Get("Authorization"); a != "Bearer tk" {
		t.Errorf("bearer: %q", a)
	}
	if a := reqs[1].Header.Get("Authorization"); a != "Basic YWRhOnB3" {
		t.Errorf("basic: %q", a)
	}
	if reqs[2].Header.Get("X-Api-Key") != "k-1" || reqs[3].Header.Get("Authorization") != "" {
		t.Errorf("key/open: %v / %v", reqs[2].Header, reqs[3].Header)
	}
}

func TestOptionDefaultsAndRanges(t *testing.T) {
	d := module.DefaultClientOptions()
	if d.ConnectTimeout != 5*time.Second || d.RequestTimeout != 30*time.Second || d.MaxResponseBytes != 10<<20 || d.Retry.MaxAttempts != 3 {
		t.Fatalf("defaults %+v", d)
	}
	if _, ok := d.Auth.(module.NoAuth); !ok {
		t.Fatalf("default auth %T", d.Auth)
	}
	if err := d.Validate(); err != nil {
		t.Fatal(err)
	}
	for _, bad := range []module.ClientOptions{
		d.WithConnectTimeout(0), d.WithRequestTimeout(-time.Second), d.WithConnectTimeout(time.Hour + 1), d.WithRequestTimeout(2 * time.Hour),
		d.WithMaxResponseBytes(0), d.WithMaxResponseBytes(1<<30 + 1), d.WithRetry(httpclient.DefaultRetry().WithMaxAttempts(11)), d.WithAuth(nil),
	} {
		if err := bad.Validate(); err == nil {
			t.Errorf("%+v accepted", bad)
		}
	}
	for _, good := range []module.ClientOptions{d.WithConnectTimeout(time.Hour), d.WithRequestTimeout(time.Nanosecond), d.WithMaxResponseBytes(1), d.WithMaxResponseBytes(1 << 30)} {
		if err := good.Validate(); err != nil {
			t.Errorf("%+v refused: %v", good, err)
		}
	}
}

func TestTheRequestTimeoutAndRetryFromCodeApply(t *testing.T) {
	var mu sync.Mutex
	hits := 0
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		hits++
		mu.Unlock()
		if r.URL.Path == "/slow" {
			select {
			case <-time.After(2 * time.Second):
			case <-r.Context().Done():
			}
		}
		w.WriteHeader(503)
	}))
	defer srv.Close()
	r, err := start(t, "http-clients:\n  instances:\n    slow:\n      base-url: "+srv.URL+"\n", nil,
		module.WithClient("slow", module.DefaultClientOptions().WithRequestTimeout(100*time.Millisecond).WithRetry(httpclient.NoRetry())))
	if err != nil {
		t.Fatal(err)
	}
	c, _ := r.clients.Get("slow")
	start := time.Now()
	_, err = c.Get("/slow").Send(t.Context())
	if he, ok := errors.AsType[*httpclient.Error](err); !ok || he.Kind != httpclient.KindTimeout || time.Since(start) > time.Second {
		t.Fatalf("err = %v", err)
	}
	mu.Lock()
	defer mu.Unlock()
	if hits != 1 {
		t.Fatalf("hits %d: NoRetry from code did not apply", hits)
	}
}

func TestOptionsMayComeFromAnApplicationSetting(t *testing.T) {
	srv, _ := server(t)
	r, err := start(t, "http-clients:\n  instances:\n    x:\n      base-url: "+srv.URL+"\n", nil,
		module.WithClientFrom("x", func(b *fw.Boot) (module.ClientOptions, error) {
			return module.DefaultClientOptions().WithRequestTimeout(7 * time.Second), nil
		}))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.clients.Get("x"); err != nil {
		t.Fatal(err)
	}
	_, err = start(t, "http-clients:\n  instances:\n    x:\n      base-url: "+srv.URL+"\n", nil,
		module.WithClientFrom("x", func(*fw.Boot) (module.ClientOptions, error) { return module.ClientOptions{}, errors.New("no setting") }))
	mustContain(t, err, "http-clients.instances.x", "client 'x': no setting")
}

func TestUnknownKeysUnnamedClientsAndBadValuesFailStartUp(t *testing.T) {
	srv, _ := server(t)
	base := "      base-url: " + srv.URL + "\n"
	cases := []struct {
		name  string
		yaml  string
		parts []string
		opts  []module.BuildOption
	}{
		{
			"a section for a client the code does not name", "http-clients:\n  instances:\n    ghost:\n" + base,
			[]string{"no client named 'ghost' in code", "WithClient(\"ghost\"", "named: (none)"},
			nil2(),
		},
		{
			"a client named in code has no section", "",
			[]string{"http-clients.instances.billing.base-url", "missing required key"},
			[]module.BuildOption{module.WithClient("billing", module.DefaultClientOptions())},
		},
		{
			"a key the strategy does not read", "http-clients:\n  instances:\n    b:\n" + base + "      auth: { token: t, username: u }\n",
			[]string{"http-clients.instances.b.auth.username", "its ClientAuth reads only its own credentials"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions().WithAuth(module.Bearer()))},
		},
		{
			"a credential the strategy reads is missing", "http-clients:\n  instances:\n    b:\n" + base,
			[]string{"http-clients.instances.b.auth.token", "missing required key", "HTTP_CLIENTS_INSTANCES_B_AUTH_TOKEN"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions().WithAuth(module.Bearer()))},
		},
		{
			"an unknown key in the section", "http-clients:\n  instances:\n    b:\n" + base + "      colour: red\n",
			[]string{"colour"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions())},
		},
		{
			"a stray key under http-clients", "http-clients:\n  verbose: true\n  instances:\n    b:\n" + base,
			[]string{"http-clients.verbose"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions())},
		},
		{
			"a bad base url", "http-clients:\n  instances:\n    b:\n      base-url: ftp://x\n",
			[]string{"client 'b'", "base URL"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions())},
		},
		{
			"a client name twice", "http-clients:\n  instances:\n    b:\n" + base,
			[]string{"client 'b' is already given"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions()), module.WithClient("b", module.DefaultClientOptions())},
		},
		{
			"a bad name", "",
			[]string{"client name must match [a-z][a-z0-9-]*, was 'Bad'"},
			[]module.BuildOption{module.WithClient("Bad", module.DefaultClientOptions())},
		},
		{
			"bad options", "http-clients:\n  instances:\n    b:\n" + base,
			[]string{"client 'b'", "requestTimeout must be more than 0"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions().WithRequestTimeout(0))},
		},
		{
			"a plain-http token url", "http-clients:\n  instances:\n    b:\n" + base + "      auth: { token-url: 'http://login.example.com/t', client-id: c, client-secret: s }\n",
			[]string{"client 'b'", "token URL must be https"},
			[]module.BuildOption{module.WithClient("b", module.DefaultClientOptions().WithAuth(module.ClientCredentials()))},
		},
	}
	for _, c := range cases {
		_, err := start(t, c.yaml, nil, c.opts...)
		t.Run(c.name, func(t *testing.T) { mustContain(t, err, c.parts...) })
	}
}

func nil2() []module.BuildOption { return nil }

func TestTheEngineSettingIsConfigurationWithItsDefaultAndRange(t *testing.T) {
	srv, _ := server(t)
	opts := []module.BuildOption{module.WithClient("x", module.DefaultClientOptions())}
	yaml := "http-clients:\n  instances:\n    x:\n      base-url: " + srv.URL + "\n"
	if _, err := start(t, yaml, nil, opts...); err != nil {
		t.Fatalf("default: %v", err)
	}
	for _, ok := range []string{"1", "50", "1000"} {
		if _, err := start(t, yaml, map[string]string{"HTTP_CLIENT_MAX_CONNECTIONS_PER_HOST": ok}, opts...); err != nil {
			t.Errorf("%s: %v", ok, err)
		}
	}
	for _, bad := range []string{"0", "1001", "-3", "many"} {
		_, err := start(t, yaml, map[string]string{"HTTP_CLIENT_MAX_CONNECTIONS_PER_HOST": bad}, opts...)
		mustContain(t, err, "http-client.max-connections-per-host")
	}
	_, err := start(t, "http-client:\n  max-connections-per-host: 2000\n"+yaml, nil, opts...)
	mustContain(t, err, "http-client.max-connections-per-host", "must be 1 to 1000", "application.yaml")
}

func TestAMisspeltHTTPClientKeyFailsAndEventLoopsIsNotAKey(t *testing.T) {
	srv, _ := server(t)
	opts := []module.BuildOption{module.WithClient("x", module.DefaultClientOptions())}
	yaml := "http-clients:\n  instances:\n    x:\n      base-url: " + srv.URL + "\n"
	_, err := start(t, "http-client:\n  max-connection-per-host: 3\n"+yaml, nil, opts...)
	mustContain(t, err, "http-client.max-connection-per-host", "max-connections-per-host")
	_, err = start(t, "http-client:\n  event-loops: 2\n"+yaml, nil, opts...)
	mustContain(t, err, "http-client.event-loops", "unknown")
}

func TestTheRemovedClientKeysFailStartUpNamingTheCode(t *testing.T) {
	srv, _ := server(t)
	opts := []module.BuildOption{module.WithClient("x", module.DefaultClientOptions())}
	base := "http-clients:\n  instances:\n    x:\n      base-url: " + srv.URL + "\n"
	for key, snippet := range map[string]string{
		"http-clients.default":                        "  default: x\n",
		"http-clients.instances.x.driver":             "      driver: http\n",
		"http-clients.instances.x.connect-timeout":    "      connect-timeout: 2s\n",
		"http-clients.instances.x.request-timeout":    "      request-timeout: 2s\n",
		"http-clients.instances.x.max-response-bytes": "      max-response-bytes: 100\n",
		"http-clients.instances.x.retry.max-attempts": "      retry: { max-attempts: 2 }\n",
		"http-clients.instances.x.auth.type":          "      auth: { type: none }\n",
		"http-clients.instances.x.auth.scope":         "      auth: { scope: s }\n",
	} {
		yaml := base
		if strings.HasPrefix(snippet, "  default") {
			yaml = "http-clients:\n" + snippet + "  instances:\n    x:\n      base-url: " + srv.URL + "\n"
		} else {
			yaml += snippet
		}
		_, err := start(t, yaml, nil, opts...)
		mustContain(t, err, key, "moved to code")
	}
}

func TestClientsBuiltInCodeCanBeRegisteredAndAreMeasuredToo(t *testing.T) {
	srv, _ := server(t)
	r, err := start(t, "", nil)
	if err != nil {
		t.Fatal(err)
	}
	u := mustURL(t, srv.URL)
	c, err := r.clients.Register("extra", u, httpclient.Retry(httpclient.NoRetry()))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := c.Get("/x").Send(t.Context()); err != nil {
		t.Fatal(err)
	}
	if n := counter(r.reg, "fw_http_client_requests_total", "extra", "GET", "200"); n != 1 {
		t.Fatalf("a registered client is not measured: %v", n)
	}
	if _, err := r.clients.Register("extra", u); err == nil {
		t.Fatal("a name registered twice")
	}
	if got := r.clients.Names(); len(got) != 1 || got[0] != "extra" {
		t.Fatalf("names %v", got)
	}
}

func TestTheEngineIsClosedAtShutdown(t *testing.T) {
	srv, _ := server(t)
	r, err := start(t, "http-clients:\n  instances:\n    x:\n      base-url: "+srv.URL+"\n", nil, module.WithClient("x", module.DefaultClientOptions()))
	if err != nil {
		t.Fatal(err)
	}
	c, _ := r.clients.Get("x")
	if _, err := c.Get("/x").Send(t.Context()); err != nil {
		t.Fatal(err)
	}
	if err := r.run.Shutdown(); err != nil {
		t.Fatal(err)
	}
	_, err = c.Get("/x").Send(t.Context())
	if he, ok := errors.AsType[*httpclient.Error](err); !ok || he.Kind != httpclient.KindClosed {
		t.Fatalf("after shutdown: %v", err)
	}
}

func mustURL(t *testing.T, s string) *url.URL {
	t.Helper()
	u, err := url.Parse(s)
	if err != nil {
		t.Fatal(err)
	}
	return u
}
