package flowcatalyst_test

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
)

const (
	testSecret = "whsec-test-only"
	testTS     = "2026-10-09T12:00:00.000Z"
)

var testNow = time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC)

// rig is an application with the FlowCatalyst module started against a fake platform.
type rig struct {
	t        *testing.T
	platform *fakePlatform
	clock    *fwtest.Clock
	logs     *logCapture
	fc       *flowcatalyst.Platform
	run      *fw.Running
	handler  http.Handler
	dbs      *database.Databases
	out      *bytes.Buffer
	exit     int
}

// options of a rig.
type rigOptions struct {
	env       map[string]string // extra or overriding environment (a "" value removes the variable)
	yaml      string            // application.yaml
	define    func(d *flowcatalyst.Declared)
	register  func(fc *flowcatalyst.Platform) error // handlers, in the build function
	buildOpts func(b *fw.Boot) []flowcatalyst.BuildOption
	noRoutes  bool // do not hand the routes to a dispatcher (no HTTP module)
	locks     bool // a memory lock store as the default
	dbURL     string
	platform  *fakePlatform
	migrate   bool
	args      []string // run the application's command instead of starting it
	declare   func(app *fw.App)
	// lockFactory replaces the memory lock store; clock replaces the fake clock.
	lockFactory locks.StoreFactory
	clock       fw.Clock
}

func baseEnv(p *fakePlatform) map[string]string {
	return map[string]string{
		"APP_ENV":                                       "test",
		"FLOWCATALYST_BASE_URL":                         p.baseURL(),
		"FLOWCATALYST_APPLICATION":                      "orders",
		"FLOWCATALYST_CLIENT_ID":                        fakeClientID,
		"FLOWCATALYST_CLIENT_SECRET":                    fakeClientSecret,
		"FLOWCATALYST_RECEIVERS_SIGNING_SECRET":         testSecret,
		"FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY": "0s",
	}
}

// startRig starts the application; it fails the test on a start-up error.
func startRig(t *testing.T, o rigOptions) *rig {
	t.Helper()
	r, err := tryRig(t, o)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	return r
}

// tryRig starts the application and returns the start-up error, if any.
func tryRig(t *testing.T, o rigOptions) (*rig, error) {
	t.Helper()
	p := o.platform
	if p == nil {
		p = newFakePlatform(t)
	}
	r := &rig{t: t, platform: p, clock: fwtest.NewClock(testNow), logs: &logCapture{}, out: &bytes.Buffer{}}
	env := baseEnv(p)
	for k, v := range o.env {
		if v == "" {
			delete(env, k)
		} else {
			env[k] = v
		}
	}
	dir := t.TempDir()
	yaml := o.yaml
	if o.dbURL != "" {
		yaml += "\ndatabase:\n  instances:\n    main:\n      url: \"" + o.dbURL + "\"\n"
	}
	if yaml != "" {
		if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte(yaml), 0o600); err != nil {
			t.Fatal(err)
		}
	}
	var appClock fw.Clock = r.clock
	if o.clock != nil {
		appClock = o.clock
	}
	app := fw.New("orders", fw.WithEnvironment(env), fw.WithConfigDir(dir), fw.WithClock(appClock),
		fw.WithOutput(r.out, r.out), fw.WithLogger(slog.New(r.logs)))
	var dbDecl *database.Declared
	if o.dbURL != "" {
		dbDecl = database.Declare(app)
	}
	if o.declare != nil {
		o.declare(app)
	}
	d := flowcatalyst.Declare(app)
	if o.define != nil {
		o.define(d)
	}
	var lockDecl *locks.Declared
	if o.locks || o.lockFactory != nil {
		lockDecl = locks.Declare(app)
	}
	build := func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		var opts []flowcatalyst.BuildOption
		if dbDecl != nil {
			dbs, err := database.Connect(ctx, b, dbDecl)
			if err != nil {
				return nil, err
			}
			r.dbs = dbs
			if o.migrate {
				if err := database.Migrate(ctx, b, dbs); err != nil {
					return nil, err
				}
			}
			opts = append(opts, flowcatalyst.WithDatabases(dbs))
		}
		if lockDecl != nil {
			factory := locks.Memory()
			if o.lockFactory != nil {
				factory = o.lockFactory
			}
			all, err := locks.Build(ctx, b, lockDecl, locks.WithStore("main", factory))
			if err != nil {
				return nil, err
			}
			opts = append(opts, flowcatalyst.WithLocks(all))
		}
		if o.buildOpts != nil {
			opts = append(opts, o.buildOpts(b)...)
		}
		fc, err := flowcatalyst.Build(ctx, b, d, opts...)
		if err != nil {
			return nil, err
		}
		r.fc = fc
		if o.register != nil {
			if err := o.register(fc); err != nil {
				return nil, err
			}
		}
		if !o.noRoutes {
			routes, err := fc.Routes()
			if err != nil {
				return nil, err
			}
			disp, err := httpfw.Compile([]*httpfw.Routes{routes}, httpfw.WithClock(r.clock))
			if err != nil {
				return nil, err
			}
			r.handler = disp
		}
		return nil, nil
	}
	if o.args != nil {
		r.exit = app.Launch(t.Context(), o.args, build)
		return r, nil
	}
	run, err := app.Start(t.Context(), build)
	if err != nil {
		return r, err
	}
	r.run = run
	t.Cleanup(func() { _ = run.Shutdown() })
	return r, nil
}

// do sends a request through the dispatcher.
type reply struct {
	status int
	header http.Header
	body   string
}

func (r *rig) post(path, body string, headers map[string]string) reply {
	r.t.Helper()
	if r.handler == nil {
		r.t.Fatal("the rig has no handler")
	}
	req := httptest.NewRequest(http.MethodPost, path, strings.NewReader(body))
	req.RemoteAddr = "192.0.2.1:1234"
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return reply{rec.Code, rec.Result().Header, string(b)}
}

// signed returns headers carrying a valid signature of body at the test time.
func signedHeaders(body string, extra map[string]string) map[string]string {
	h := map[string]string{
		"X-FlowCatalyst-Signature": signature(testSecret, testTS, body),
		"X-FlowCatalyst-Timestamp": testTS,
	}
	maps.Copy(h, extra)
	return h
}

// dispatchHeaders are the headers of a dispatch delivery of an event type.
func dispatchHeaders(body, eventType string, extra map[string]string) map[string]string {
	h := signedHeaders(body, map[string]string{"X-Dispatch-Job-Id": "dj_0HZXEQ5Y8JY5Z", "X-Event-Type": eventType})
	maps.Copy(h, extra)
	return h
}

// problemTexts returns the text of every problem of a start-up error.
func problemTexts(err error) []string {
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

func mustFail(t *testing.T, err error, parts ...string) {
	t.Helper()
	if err == nil {
		t.Fatalf("start-up succeeded, want a failure naming %q", parts)
	}
	all := strings.Join(problemTexts(err), "\n")
	for _, p := range parts {
		if !strings.Contains(all, p) {
			t.Errorf("start-up problems lack %q:\n%s", p, all)
		}
	}
}

// waitFor polls cond until it holds or the deadline passes.
func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(5 * time.Millisecond)
	}
}

// recorderOf collects values from handlers running on other goroutines.
type recorderOf[T any] struct {
	mu   sync.Mutex
	vals []T
}

func (r *recorderOf[T]) add(v T) {
	r.mu.Lock()
	r.vals = append(r.vals, v)
	r.mu.Unlock()
}

func (r *recorderOf[T]) get() []T {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]T(nil), r.vals...)
}

// parseEnvelope reads an error envelope.
func parseEnvelope(t *testing.T, body string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(body), &m); err != nil {
		t.Fatalf("not an envelope: %v: %s", err, body)
	}
	return m
}

// postRequest sends a prepared request through the dispatcher.
func (r *rig) postRequest(req *http.Request) reply {
	r.t.Helper()
	req.RemoteAddr = "192.0.2.1:1234"
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return reply{rec.Code, rec.Result().Header, string(b)}
}

// compileForTest compiles one route table into a handler.
func compileForTest(t *testing.T, routes *httpfw.Routes) http.Handler {
	t.Helper()
	d, err := httpfw.Compile([]*httpfw.Routes{routes})
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func newRequest(t *testing.T, method, target string) *http.Request {
	t.Helper()
	return httptest.NewRequest(method, target, nil)
}

// health runs every health check of the started application.
func (r *rig) health() fw.HealthReport { return r.run.Health(r.t.Context()) }

// asSyncError reports whether err is a *flowcatalyst.SyncError.
func asSyncError(err error, target **flowcatalyst.SyncError) bool { return errors.As(err, target) }

// newLockHolder returns a function that takes the purge lock on store and never releases it.
func newLockHolder(t *testing.T, store *locks.MemoryStore, clk fw.Clock) func() {
	t.Helper()
	l := locks.New(store, locks.WithClock(clk))
	return func() {
		acq, err := l.TryAcquire(t.Context(), locks.MustServiceKey("fw-dispatch-once-purge"), 24*time.Hour)
		if err != nil {
			t.Fatal(err)
		}
		if _, ok := acq.(locks.Acquired); !ok {
			t.Fatal("the purge lock is not free")
		}
	}
}
