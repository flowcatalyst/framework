package main

import (
	"bytes"
	"context"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

func TestLocksDemoWithThreeInstances(t *testing.T) {
	t.Parallel()
	db := fwtest.NewDatabase(t)
	rd := fwtest.NewRedis(t)
	env := map[string]string{
		"APP_ENV":                          "test",
		"DATABASE_INSTANCES_MAIN_URL":      db.URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
		"REDIS_INSTANCES_DEFAULT_URL":      rd.URL,
		"LOCKS_INSTANCES_SHARED_PREFIX":    rd.Prefix,
	}
	var out bytes.Buffer
	var mu sync.Mutex
	var trace []string
	app := newApp(fw.WithEnvironment(env), fw.WithConfigDir("config"), fw.WithOutput(&out, &bytes.Buffer{}))
	d := declare(app)
	r, err := app.Start(t.Context(), build(d, deps{out: &out, trace: func(e string) {
		mu.Lock()
		defer mu.Unlock()
		trace = append(trace, e)
	}}))
	if err != nil {
		t.Fatal(err)
	}
	select {
	case <-r.Done():
	case <-time.After(30 * time.Second):
		t.Fatal("the demo did not finish")
	}
	health := healthLine(r.Health(context.WithoutCancel(t.Context())))
	if err := r.Shutdown(); err != nil {
		t.Fatal(err)
	}
	lines := strings.Split(strings.TrimSpace(out.String()), "\n")
	want := []string{"main: completed with token ", "shared: completed with token ", "local: completed with token "}
	if len(lines) != len(want) {
		t.Fatalf("report %q", lines)
	}
	for i, prefix := range want {
		if !strings.HasPrefix(lines[i], prefix) {
			t.Errorf("line %d: %q, want prefix %q", i, lines[i], prefix)
		}
	}
	if health != "health: up [database:main, redis:default, background:sweeper]" {
		t.Errorf("health: %q", health)
	}
	mu.Lock()
	defer mu.Unlock()
	if !slices.Equal(trace, []string{"loop stopped", "shutdown: after redis", "shutdown: after databases"}) {
		t.Errorf("shutdown order %v: loops first, then the shutdown actions in reverse", trace)
	}
}
