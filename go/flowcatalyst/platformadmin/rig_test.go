package platformadmin_test

import (
	"context"
	"encoding/json/v2"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
	"github.com/flowcatalyst/framework/go/flowcatalyst/platformadmin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// permsResolver is the test's ContextResolver: the caller's permissions are in X-Perms, comma
// separated; a request without the header is not signed in.
type permsResolver struct{}

func (permsResolver) Resolve(r *http.Request) (fw.Context, error) {
	header, ok := r.Header["X-Perms"]
	if !ok {
		return fw.Context{}, httpfw.Unauthenticated("not signed in")
	}
	var perms []fw.Permission
	for p := range strings.SplitSeq(strings.Join(header, ","), ",") {
		if p = strings.TrimSpace(p); p != "" {
			perms = append(perms, fw.MustPermission(p))
		}
	}
	return fw.ContextFor(fw.MustClientID("demo")).WithPermissions(perms...), nil
}

// rig is an application "orders" with the admin skeleton and the platform sections against a fake
// platform.
type rig struct {
	t        *testing.T
	platform *fakeplatform.Platform
	handler  http.Handler
	fc       *flowcatalyst.Platform
}

type rigOptions struct {
	env      map[string]string
	platform *fakeplatform.Platform
	// declare picks the sections; the default is the catalog
	declare func(adm *admin.Declared) []platformadmin.Part
	// definitions adds to the FlowCatalyst declarations, after the sections are declared
	definitions func(fc *flowcatalyst.Declared, adm *admin.Declared)
}

func startRig(t *testing.T, o rigOptions) *rig {
	t.Helper()
	r, err := tryRig(t, o)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	return r
}

func tryRig(t *testing.T, o rigOptions) (*rig, error) {
	t.Helper()
	p := o.platform
	if p == nil {
		p = fakeplatform.Start()
		t.Cleanup(p.Close)
	}
	env := map[string]string{
		"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0",
		"FLOWCATALYST_BASE_URL": p.BaseURL(), "FLOWCATALYST_APPLICATION": "orders",
		"FLOWCATALYST_CLIENT_ID": fakeplatform.ClientID, "FLOWCATALYST_CLIENT_SECRET": fakeplatform.ClientSecret,
	}
	maps.Copy(env, o.env)
	for k, v := range env {
		if v == "" {
			delete(env, k)
		}
	}
	app := fw.New("orders-admin-test", fw.WithEnvironment(env), fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard))
	web := httpfw.Declare(app)
	adm := admin.Declare(web, fw.MustApplicationCode("orders"))
	fcd := flowcatalyst.Declare(app)
	declare := o.declare
	if declare == nil {
		declare = func(adm *admin.Declared) []platformadmin.Part {
			return []platformadmin.Part{platformadmin.DeclareCatalog(adm)}
		}
	}
	sections := declare(adm)
	if o.definitions != nil {
		o.definitions(fcd, adm)
	}
	out := &rig{t: t, platform: p}
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		fc, err := flowcatalyst.Build(ctx, b, fcd)
		if err != nil {
			return nil, err
		}
		out.fc = fc
		skeleton, err := adm.Build(b)
		if err != nil {
			return nil, err
		}
		routes, err := platformadmin.Build(b, fc.Client(), sections...)
		if err != nil {
			return nil, err
		}
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{skeleton, routes}, httpfw.WithResolver(permsResolver{}))
		if err != nil {
			return nil, err
		}
		out.handler = srv.Handler()
		return srv, nil
	})
	if err != nil {
		return nil, err
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return out, nil
}

type answer struct {
	status int
	body   string
	header http.Header
}

func (a answer) tree() map[string]any {
	var m map[string]any
	_ = json.Unmarshal([]byte(a.body), &m)
	return m
}

// send sends a request; held is the caller's permissions, nil for an unauthenticated caller.
func (r *rig) send(method, target, body string, held []string) answer {
	r.t.Helper()
	var reader io.Reader = http.NoBody
	if body != "" {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, target, reader)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if held != nil {
		req.Header.Set("X-Perms", strings.Join(held, ","))
	}
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return answer{status: rec.Code, body: string(b), header: rec.Result().Header}
}

func (r *rig) get(target string, held []string) answer { return r.send("GET", target, "", held) }

// platformCalls counts the requests that reached the platform's API (not its token endpoint).
func (r *rig) platformCalls() int {
	n := 0
	for _, q := range r.platform.Requests() {
		if strings.HasPrefix(q.Path, "/api/") {
			n++
		}
	}
	return n
}
