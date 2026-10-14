package fw_test

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

type events struct {
	mu  sync.Mutex
	got []string
}

func (e *events) add(s string) {
	e.mu.Lock()
	defer e.mu.Unlock()
	e.got = append(e.got, s)
}

func (e *events) list() []string {
	e.mu.Lock()
	defer e.mu.Unlock()
	return slices.Clone(e.got)
}

func newApp(t *testing.T, env map[string]string, opts ...fw.Option) *fw.App {
	t.Helper()
	if env == nil {
		env = map[string]string{}
	}
	env["APP_ENV"] = "test"
	var stderr bytes.Buffer
	return fw.New("demo", append([]fw.Option{
		fw.WithEnvironment(env), fw.WithConfigDir(t.TempDir()), fw.WithOutput(&bytes.Buffer{}, &stderr),
	}, opts...)...)
}

func TestLifecycleOrder(t *testing.T) {
	t.Parallel()
	app := newApp(t, nil)
	ev := &events{}
	loopRan := make(chan struct{}, 1)
	r, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		ev.add("build")
		b.OnStarted("first", func(context.Context) error { ev.add("started first"); return nil })
		b.OnStarted("second", func(context.Context) error { ev.add("started second"); return nil })
		b.OnShutdown("pool", func(context.Context) error { ev.add("shutdown pool"); return nil })
		b.OnShutdown("server-resources", func(context.Context) error { ev.add("shutdown server-resources"); return nil })
		b.Loop("tick", background.Options{Interval: 10 * time.Millisecond}, func(ctx context.Context, tick background.Tick) error {
			if tick.Run == 1 {
				ev.add("loop run")
				loopRan <- struct{}{}
			}
			<-ctx.Done() // runs until the loops are stopped
			ev.add("loop stopped")
			return nil
		})
		return fw.ServeFunc(func(ctx context.Context) error {
			ev.add("serve")
			<-ctx.Done()
			ev.add("serve drained")
			return nil
		}), nil
	})
	if err != nil {
		t.Fatal(err)
	}
	<-loopRan
	if !r.Ready() {
		t.Error("not ready after Start")
	}
	if err := r.Shutdown(); err != nil {
		t.Fatal(err)
	}
	if r.Ready() {
		t.Error("ready after Shutdown")
	}
	got := ev.list()
	// The loop and serve start concurrently; everything else is ordered.
	idx := func(s string) int { return slices.Index(got, s) }
	for _, pair := range [][2]string{
		{"build", "started first"},
		{"started first", "started second"},
		{"started second", "loop run"},
		{"started second", "serve"},
		{"loop stopped", "serve drained"},
		{"serve drained", "shutdown server-resources"},
		{"shutdown server-resources", "shutdown pool"},
	} {
		if idx(pair[0]) < 0 || idx(pair[1]) < 0 || idx(pair[0]) > idx(pair[1]) {
			t.Errorf("%q must come before %q; events %v", pair[0], pair[1], got)
		}
	}
}

func TestConfigurationProblemsAreReportedTogether(t *testing.T) {
	t.Parallel()
	app := fw.New("Bad Name", fw.WithEnvironment(map[string]string{"SVC_PORT": "eighty"}), fw.WithConfigDir(t.TempDir()))
	d := app.Module("svc")
	config.Declare(d, config.Int("svc.port", "port"))
	config.Declare(d, config.String("svc.name", "name").Required())
	app.Module("svc") // a second module with one id
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) {
		t.Error("build ran despite configuration problems")
		return nil, nil
	})
	se, ok := errors.AsType[*fw.StartupError](err)
	if !ok {
		t.Fatalf("err %v, want *fw.StartupError", err)
	}
	text := se.Error()
	for _, want := range []string{
		"Application failed to start: 4 problems",
		"application name \"Bad Name\" must match",
		"module 'svc': two modules with one id",
		"module 'svc': key 'svc.port' (from environment variable SVC_PORT): expected an integer, found 'eighty'",
		"module 'svc': key 'svc.name': missing required setting",
	} {
		if !strings.Contains(text, want) {
			t.Errorf("missing %q in:\n%s", want, text)
		}
	}
}

func TestBuildFailureRunsTheShutdownActionsRegisteredSoFar(t *testing.T) {
	t.Parallel()
	app := newApp(t, nil)
	ev := &events{}
	_, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		b.OnShutdown("a", func(context.Context) error { ev.add("a"); return nil })
		b.OnShutdown("b", func(context.Context) error { ev.add("b"); return errors.New("b fails") })
		b.OnShutdown("c", func(context.Context) error { ev.add("c"); return nil })
		return nil, errors.New("database does not answer")
	})
	if err == nil || !strings.Contains(err.Error(), "database does not answer") {
		t.Fatalf("err %v", err)
	}
	if got := ev.list(); !slices.Equal(got, []string{"c", "b", "a"}) {
		t.Errorf("shutdown actions %v, want reverse order with every one run", got)
	}
}

func TestPanicInBuildIsAStartupFailure(t *testing.T) {
	t.Parallel()
	app := newApp(t, nil)
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { panic("bug") })
	if err == nil || !strings.Contains(err.Error(), "build panicked: bug") {
		t.Fatalf("err %v", err)
	}
}

func TestShutdownActionsAreBoundedByTheDrain(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_SHUTDOWN_DRAIN": "50ms"})
	ev := &events{}
	r, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		b.OnShutdown("fast", func(context.Context) error { ev.add("fast"); return nil })
		b.OnShutdown("stuck", func(context.Context) error { time.Sleep(5 * time.Second); return nil })
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	begin := time.Now()
	_ = r.Shutdown()
	if took := time.Since(begin); took > 2*time.Second {
		t.Errorf("shutdown took %v; a stuck action must be abandoned after the drain", took)
	}
	if got := ev.list(); !slices.Equal(got, []string{"fast"}) {
		t.Errorf("events %v, want the next action to run after the overrun", got)
	}
}

func TestCommandsRunAfterBuildWithoutStartedActions(t *testing.T) {
	t.Parallel()
	var out bytes.Buffer
	app := fw.New("demo", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()), fw.WithOutput(&out, &bytes.Buffer{}))
	ev := &events{}
	code := app.Launch(t.Context(), []string{"greet", "world"}, func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		b.OnStarted("never", func(context.Context) error { ev.add("started"); return nil })
		b.OnShutdown("close", func(context.Context) error { ev.add("closed"); return nil })
		b.Command("greet", func(_ context.Context, args []string, w io.Writer) int {
			_, _ = w.Write([]byte("hello " + strings.Join(args, " ")))
			return 3
		})
		return fw.ServeFunc(func(context.Context) error { ev.add("served"); return nil }), nil
	})
	if code != 3 || out.String() != "hello world" {
		t.Errorf("code %d out %q", code, out.String())
	}
	if got := ev.list(); !slices.Equal(got, []string{"closed"}) {
		t.Errorf("events %v, want only the shutdown action", got)
	}
}

func TestCommandClaimedTwiceFailsStart(t *testing.T) {
	t.Parallel()
	app := newApp(t, nil)
	_, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		run := func(context.Context, []string, io.Writer) int { return 0 }
		b.Command("sync", run)
		b.Command("sync", run)
		return nil, nil
	})
	if err == nil || !strings.Contains(err.Error(), "key 'command sync': claimed twice") {
		t.Fatalf("err %v", err)
	}
}

func TestLaunchExitCodes(t *testing.T) {
	t.Parallel()
	ok := newApp(t, nil).Launch(t.Context(), nil, func(context.Context, *fw.Boot) (fw.Serve, error) {
		return fw.ServeFunc(func(context.Context) error { return nil }), nil
	})
	failed := newApp(t, nil).Launch(t.Context(), nil, func(context.Context, *fw.Boot) (fw.Serve, error) {
		return fw.ServeFunc(func(context.Context) error { return errors.New("bind failed") }), nil
	})
	badConfig := newApp(t, map[string]string{"APP_SHUTDOWN_DRAIN": "soon"}).Launch(t.Context(), nil,
		func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if ok != 0 || failed != 1 || badConfig != 1 {
		t.Errorf("exit codes ok=%d failed=%d badConfig=%d, want 0 1 1", ok, failed, badConfig)
	}
}

func TestHealthCriticalityAndOverrides(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_HEALTH_CRITICAL": "outbox", "APP_HEALTH_TIMEOUT": "50ms"})
	r, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		b.Health("database:main", fw.Critical, func(context.Context) error { return nil })
		b.Health("flowcatalyst", fw.Informational, func(context.Context) error { return errors.New("platform down") })
		b.Health("outbox", fw.Informational, func(context.Context) error { return nil })
		b.Health("slow", fw.Informational, func(ctx context.Context) error { <-ctx.Done(); time.Sleep(time.Second); return nil })
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = r.Shutdown() }()
	rep := r.Health(t.Context())
	if rep.IsUp() {
		t.Error("IsUp with a down check")
	}
	if !rep.IsReady() {
		t.Error("an informational check that is down made the application not ready")
	}
	if res, _ := rep.Result("outbox"); res.Criticality != fw.Critical {
		t.Errorf("outbox: %v, want Critical from app.health.critical", res.Criticality)
	}
	if res, _ := rep.Result("slow"); res.Status != fw.TimedOut {
		t.Errorf("slow: %v, want TimedOut", res.Status)
	}
	if res, _ := rep.Result("flowcatalyst"); res.Status != fw.Down || res.Reason != "platform down" {
		t.Errorf("flowcatalyst: %+v", res)
	}
}

func TestHealthNameInBothListsFailsStart(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_HEALTH_CRITICAL": "a", "APP_HEALTH_INFORMATIONAL": "a"})
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if err == nil || !strings.Contains(err.Error(), "key 'app.health.informational'") {
		t.Fatalf("err %v", err)
	}
}

func TestDeclaringAfterStartPanics(t *testing.T) {
	t.Parallel()
	app := newApp(t, nil)
	r, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if err != nil {
		t.Fatal(err)
	}
	defer func() { _ = r.Shutdown() }()
	defer func() {
		if recover() == nil {
			t.Error("Module after Start did not panic")
		}
	}()
	app.Module("late")
}

func TestMigrationSetsInModuleOrder(t *testing.T) {
	t.Parallel()
	app := newApp(t, nil)
	b2 := app.Module("second")
	b1 := app.Module("first")
	b1.Migrations(nil)
	b2.Migrations(nil)
	r, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		var mods []string
		for _, s := range b.MigrationSets() {
			mods = append(mods, s.Module)
		}
		if !slices.Equal(mods, []string{"second", "first"}) {
			t.Errorf("sets %v, want module declaration order", mods)
		}
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	_ = r.Shutdown()
}

func TestBootAccessors(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_SHUTDOWN_DRAIN": "3s"})
	var boot *fw.Boot
	r, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		boot = b
		b.Health("thing", fw.Critical, func(context.Context) error { return errors.New("broken") })
		if b.Ready() {
			t.Error("ready during build")
		}
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if !boot.Ready() || !r.Ready() {
		t.Error("not ready after Start")
	}
	if got := boot.ShutdownDrain(); got != 3*time.Second {
		t.Errorf("drain %v", got)
	}
	rep := boot.HealthReport(t.Context())
	if res, ok := rep.Result("thing"); !ok || res.Status != fw.Down || rep.IsReady() {
		t.Errorf("%+v", rep)
	}
	if err := r.Shutdown(); err != nil {
		t.Fatal(err)
	}
	if boot.Ready() {
		t.Error("Boot.Ready after Shutdown")
	}
}

func TestStartupRefusesRemovedAndUnknownKeys(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"),
		[]byte("app:\n  shutdown:\n    drian: 1s\nlocks:\n  default: x\n  instances:\n    x: { driver: memory }\napp2: {}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	app := fw.New("demo", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(dir),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	se, ok := errors.AsType[*fw.StartupError](err)
	if !ok {
		t.Fatalf("%v", err)
	}
	var texts []string
	for _, p := range se.Problems {
		texts = append(texts, p.Key+": "+p.Message)
	}
	joined := strings.Join(texts, "\n")
	for _, want := range []string{
		"app.shutdown.drian: unknown key: component 'app' belongs to module 'core', which reads only app.health.critical",
		"locks.default: moved to code: locks.DefaultStore(name)",
		"locks.instances.x.driver: moved to code: locks.Build(",
	} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing %q in\n%s", want, joined)
		}
	}
}

func TestMaxSeriesIsRangeChecked(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_METRICS_MAX_SERIES": "0"})
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if err == nil || !strings.Contains(err.Error(), "must be 1 to 100000, was 0") {
		t.Errorf("%v", err)
	}
}

func TestInstancesAndClaimOnDeclarer(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"),
		[]byte("things:\n  instances:\n    a: { url: u }\n    b: { url: v, extra: 1 }\nmine:\n  oops: 1\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	app := fw.New("demo", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(dir),
		fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := app.Module("things")
	d.Instances("things", "Things", config.String("url", "").Required())
	d.ClaimComponent("mine")
	_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
	if err == nil {
		t.Fatal("want problems")
	}
	for _, want := range []string{"key 'things.instances.b.extra'", "unknown key for Things instance 'b'", "key 'mine.oops'", "component 'mine' belongs to module 'things'"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("missing %q in\n%v", want, err)
		}
	}
}
