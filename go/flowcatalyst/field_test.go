package flowcatalyst_test

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"maps"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
)

const publicBase = "https://app.example"

// fieldApp is an application with the auth module (field workers on) and the FlowCatalyst module
// against a test provider and a fake platform.
type fieldApp struct {
	t        *testing.T
	platform *fakePlatform
	clock    *fwtest.Clock
	provider *authtest.Provider
	device   *authtest.FieldDevice
	testing  *auth.Testing
	handler  http.Handler
}

func startFieldApp(t *testing.T, maxAges ...time.Duration) *fieldApp {
	t.Helper()
	p := newFakePlatform(t)
	clock := fwtest.NewClock(testNow)
	provider := authtest.Start(clock)
	t.Cleanup(provider.Close)
	provider.FieldAudience("orders")
	provider.Client("app-client", "", publicBase+"/auth/callback")
	key, err := auth.GenerateCookieKey("k1")
	if err != nil {
		t.Fatal(err)
	}
	env := baseEnv(p)
	env["AUTH_ISSUER"], env["AUTH_CLIENT_ID"], env["AUTH_PUBLIC_BASE_URL"], env["AUTH_COOKIE_KEYS"] = provider.Issuer(), "app-client", publicBase, key
	env["HTTP_HOST"], env["HTTP_PORT"] = "127.0.0.1", "0"
	app := fw.New("orders", fw.WithEnvironment(env), fw.WithConfigDir(t.TempDir()), fw.WithClock(clock),
		fw.WithOutput(io.Discard, io.Discard), fw.WithLogger(slog.New(slog.DiscardHandler)))
	web := httpfw.Declare(app)
	ad := auth.Declare(app, web)
	fd := flowcatalyst.Declare(app)
	f := &fieldApp{t: t, platform: p, clock: clock, provider: provider, device: authtest.NewFieldDevice(authtest.DeviceClock(clock))}
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		fc, err := flowcatalyst.Build(ctx, b, fd)
		if err != nil {
			return nil, err
		}
		am, err := ad.Build(ctx, b, auth.SessionStore(auth.MemoryStoreFactory()), auth.Application(fw.MustApplicationCode("orders")),
			auth.FieldWorkers())
		if err != nil {
			return nil, err
		}
		if f.testing, err = am.Testing(); err != nil {
			return nil, err
		}
		rt := httpfw.NewRoutes("test-routes")
		for i, age := range maxAges {
			path := "POST /api/gate/open" + string(rune('a'+i))
			rt.HandleContext(path, func(w http.ResponseWriter, _ *http.Request, _ fw.Context) error {
				return httpfw.JSON(w, 200, map[string]any{"opened": true})
			}, httpfw.With(fc.RequireLiveField(age)))
		}
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{am.Routes(), rt}, httpfw.WithResolver(am.Resolver()))
		f.handler = srv.Handler()
		return srv, err
	})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return f
}

type answer struct {
	status int
	header http.Header
	body   string
}

// open sends a field request to gate route i as a worker of the session sid.
func (f *fieldApp) open(i int, sid string) answer {
	f.t.Helper()
	path := "/api/gate/open" + string(rune('a'+i))
	h, err := f.testing.FieldRequest(f.provider, f.device, "POST", path, auth.FieldPrincipal{SessionID: sid})
	if err != nil {
		f.t.Fatal(err)
	}
	return f.send("POST", path, h)
}

func (f *fieldApp) send(method, path string, h http.Header) answer {
	f.t.Helper()
	req := httptest.NewRequest(method, path, http.NoBody)
	maps.Copy(req.Header, h)
	rec := httptest.NewRecorder()
	f.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return answer{rec.Code, rec.Result().Header, string(b)}
}

func (f *fieldApp) introspectAnswer(status int, body string) {
	f.platform.respond("field-introspect", status, body)
}

func TestRequireLiveField(t *testing.T) {
	t.Run("active", func(t *testing.T) {
		f := startFieldApp(t, 0)
		f.introspectAnswer(200, `{"active":true,"sub":"fwu_TEST1","clientId":"clt_TEST","workerCode":"W001"}`)
		got := f.open(0, "fws_1")
		if got.status != 200 {
			t.Fatalf("%d %s", got.status, got.body)
		}
		calls := f.platform.introspections()
		if len(calls) != 1 || calls[0].method != "POST" || !strings.HasPrefix(calls[0].header.Get("Authorization"), "Bearer ") {
			t.Fatalf("calls %+v", calls)
		}
		// the token is what was sent, the service account's credentials are what called
		if tok, _ := calls[0].json(t)["token"].(string); strings.Count(tok, ".") != 2 {
			t.Fatalf("body %s", calls[0].body)
		}
	})
	t.Run("inactive", func(t *testing.T) {
		f := startFieldApp(t, 0)
		f.introspectAnswer(200, `{"active":false}`)
		got := f.open(0, "fws_1")
		if got.status != 401 || got.header.Get("WWW-Authenticate") != `DPoP error="invalid_token"` || !strings.Contains(got.body, "UNAUTHENTICATED") {
			t.Fatalf("%d %s %v", got.status, got.body, got.header)
		}
	})
	t.Run("another worker's token is not active", func(t *testing.T) {
		f := startFieldApp(t, 0)
		f.introspectAnswer(200, `{"active":true,"sub":"fwu_SOMEONE_ELSE"}`)
		if got := f.open(0, "fws_1"); got.status != 401 {
			t.Fatalf("%d %s", got.status, got.body)
		}
	})
	t.Run("the platform fails closed", func(t *testing.T) {
		for _, c := range []struct {
			status int
			body   string
		}{{500, `{}`}, {503, ``}, {403, `{"error":"PERMISSION_REQUIRED"}`}, {200, `not json`}, {404, ``}} {
			f := startFieldApp(t, 0)
			f.introspectAnswer(c.status, c.body)
			got := f.open(0, "fws_1")
			if got.status != 503 || !strings.Contains(got.body, "UNAVAILABLE") {
				t.Errorf("%d: %d %s", c.status, got.status, got.body)
			}
		}
	})
	t.Run("the platform is unreachable", func(t *testing.T) {
		f := startFieldApp(t, 0)
		f.platform.srv.Close()
		if got := f.open(0, "fws_1"); got.status != 503 {
			t.Fatalf("%d %s", got.status, got.body)
		}
	})
	t.Run("not a field worker", func(t *testing.T) {
		f := startFieldApp(t, 0)
		f.introspectAnswer(200, `{"active":true}`)
		got := f.send("POST", "/api/gate/opena", http.Header{"Authorization": {"Bearer " + f.provider.AccessToken(nil)}})
		if got.status != 403 || len(f.platform.introspections()) != 0 {
			t.Fatalf("%d %s", got.status, got.body)
		}
	})
	t.Run("the answer is cached per sid for maxAge", func(t *testing.T) {
		f := startFieldApp(t, 30*time.Second, 0)
		f.introspectAnswer(200, `{"active":true}`)
		for range 3 {
			if got := f.open(0, "fws_1"); got.status != 200 {
				t.Fatalf("%d %s", got.status, got.body)
			}
		}
		if n := len(f.platform.introspections()); n != 1 {
			t.Fatalf("%d checks for three requests of one session", n)
		}
		if got := f.open(0, "fws_2"); got.status != 200 || len(f.platform.introspections()) != 2 {
			t.Fatalf("another session is another entry: %d, %d checks", got.status, len(f.platform.introspections()))
		}
		f.clock.Set(f.clock.Now().Add(31 * time.Second))
		f.introspectAnswer(200, `{"active":false}`)
		if got := f.open(0, "fws_1"); got.status != 401 {
			t.Fatalf("after maxAge the platform is asked again: %d %s", got.status, got.body)
		}
		// an inactive answer is not remembered
		f.introspectAnswer(200, `{"active":true}`)
		if got := f.open(0, "fws_1"); got.status != 200 {
			t.Fatalf("%d %s", got.status, got.body)
		}
		// maxAge 0 never caches
		before := len(f.platform.introspections())
		f.open(1, "fws_1")
		f.open(1, "fws_1")
		if n := len(f.platform.introspections()) - before; n != 2 {
			t.Fatalf("maxAge 0: %d checks for two requests", n)
		}
	})
}

// ---- OnFieldRevocation -------------------------------------------------------------------------

func fieldEnvelope(eventType, data string) string {
	return `{"id":"dj_0HZXEQ5Y8JY5Z","type":"` + eventType + `","attemptNumber":1,"source":"platform:field","correlationId":"corr-1","data":` + data + `}`
}

func (r *rig) deliver(eventType, data string) reply {
	r.t.Helper()
	body := fieldEnvelope(eventType, data)
	return r.post("/fc/dispatch", body, dispatchHeaders(body, eventType, nil))
}

func TestOnFieldRevocation(t *testing.T) {
	var seen recorderOf[flowcatalyst.FieldRevocation]
	var fail bool
	r := startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		return flowcatalyst.OnFieldRevocation(fc.Receivers(), func(_ context.Context, _ fw.Context, e flowcatalyst.FieldRevocation) error {
			if fail {
				return errors.New("boom")
			}
			seen.add(e)
			return nil
		})
	}})
	// the data as the platform writes it (fieldidentity/events.go and operations.go)
	for _, c := range []struct {
		eventType, data string
		want            flowcatalyst.FieldRevocation
	}{
		{
			"platform:iam:field-worker:suspended", `{"workerId":"fwu_1","clientId":"clt_1","workerCode":"W1"}`,
			flowcatalyst.WorkerSuspended{WorkerID: "fwu_1", ClientID: "clt_1"},
		},
		{
			"platform:iam:field-device:revoked", `{"deviceId":"fwd_1","clientId":"clt_1","mode":"SHARED"}`,
			flowcatalyst.DeviceRevoked{DeviceID: "fwd_1", ClientID: "clt_1"},
		},
		{
			"platform:iam:field-session:revoked", `{"clientId":"clt_1","reason":"REUSE","count":2,"familyId":"fws_1","workerId":"fwu_1","deviceId":"fwd_1"}`,
			flowcatalyst.SessionRevoked{SessionID: "fws_1", WorkerID: "fwu_1", DeviceID: "fwd_1", ClientID: "clt_1", Reason: "REUSE"},
		},
		{
			"platform:iam:field-session:revoked", `{"clientId":"clt_1","reason":"ADMIN","count":3,"workerId":"fwu_1"}`,
			flowcatalyst.SessionRevoked{WorkerID: "fwu_1", ClientID: "clt_1", Reason: "ADMIN"},
		},
	} {
		got := r.deliver(c.eventType, c.data)
		if got.status != 200 || got.body != `{"ack":true}` {
			t.Fatalf("%s: %d %s", c.eventType, got.status, got.body)
		}
		all := seen.get()
		if last := all[len(all)-1]; last != c.want {
			t.Errorf("%s: got %+v, want %+v", c.eventType, last, c.want)
		}
	}
	for name, c := range map[string]struct{ eventType, data string }{
		"no worker":  {"platform:iam:field-worker:suspended", `{"clientId":"clt_1"}`},
		"no device":  {"platform:iam:field-device:revoked", `{}`},
		"no session": {"platform:iam:field-session:revoked", `{"clientId":"clt_1","count":0}`},
	} {
		if got := r.deliver(c.eventType, c.data); got.status != 422 {
			t.Errorf("%s: %d %s, want a rejection", name, got.status, got.body)
		}
	}
	n := len(seen.get())
	fail = true
	if got := r.deliver("platform:iam:field-worker:suspended", `{"workerId":"fwu_1"}`); got.status != 503 {
		t.Errorf("a handler error is transient: %d %s", got.status, got.body)
	}
	if len(seen.get()) != n {
		t.Error("the failing handler recorded")
	}
	// other event types are not the revocation handler's
	if got := r.deliver("platform:iam:field-worker:activated", `{"workerId":"fwu_1"}`); got.status == 200 {
		t.Errorf("an unregistered type: %d %s", got.status, got.body)
	}
}

func TestOnFieldRevocationRegistersOnce(t *testing.T) {
	h := func(context.Context, fw.Context, flowcatalyst.FieldRevocation) error { return nil }
	var second error
	startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		if err := flowcatalyst.OnFieldRevocation(fc.Receivers(), h); err != nil {
			return err
		}
		second = flowcatalyst.OnFieldRevocation(fc.Receivers(), h)
		return nil
	}})
	if second == nil || !strings.Contains(second.Error(), "already registered") {
		t.Fatalf("second: %v", second)
	}
}
