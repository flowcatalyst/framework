// Command locks-demo is the Java framework's locks-demo on the Go framework: three lock
// stores, one per driver (database, redis, memory), each taking the same service key through
// locks.With, then the health report. A loop gated by the default instance's lock shows the
// shutdown order: loops stop first, then the shutdown actions run in reverse.
//
//	go run ./examples/locks-demo     (config/application.yaml; override with environment variables)
package main

import (
	"context"
	"fmt"
	"io"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/locks/redislock"
	"github.com/flowcatalyst/framework/go/redisfw"
)

// declared holds the handles from the declare phase.
type declared struct {
	db    *database.Declared
	redis *redisfw.Declared
	locks *locks.Declared
}

// declare declares what the demo uses: databases, Redis and locks (whose stores are code).
func declare(app *fw.App) declared {
	d := declared{
		db:    database.Declare(app),
		redis: redisfw.Declare(app),
		locks: locks.Declare(app),
	}
	dblock.Declare(app) // the fw_lock migration
	return d
}

// deps are what a test replaces: where the report goes and a hook that sees lifecycle events.
type deps struct {
	out   io.Writer
	trace func(event string)
}

// build constructs the services in order and returns the demo as what to serve: it runs once and
// returns, so the application then shuts down.
func build(d declared, x deps) fw.BuildFunc {
	return func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		dbs, err := database.Connect(ctx, b, d.db)
		if err != nil {
			return nil, err
		}
		b.OnShutdown("trace:databases-closed-next", func(context.Context) error { x.trace("shutdown: after databases"); return nil })
		if err := database.Migrate(ctx, b, dbs); err != nil {
			return nil, err
		}
		clients, err := redisfw.Connect(ctx, b, d.redis)
		if err != nil {
			return nil, err
		}
		b.OnShutdown("trace:redis-closed-next", func(context.Context) error { x.trace("shutdown: after redis"); return nil })
		// three stores, one per driver: the stores and their policies are a fact of the code
		all, err := locks.Build(ctx, b, d.locks,
			locks.WithStore("main", dblock.Store(dbs)),
			locks.WithStore("shared", redislock.Store(clients, redislock.Connection("default"), redislock.Prefix("fw:lock:"))),
			locks.WithStore("local", locks.Memory()),
			locks.DefaultStore("main"))
		if err != nil {
			return nil, err
		}
		gate, err := locks.ServiceGate(all, "locks-demo-sweeper", 5*time.Second)
		if err != nil {
			return nil, err
		}
		b.Loop("sweeper", background.Options{Interval: 50 * time.Millisecond, Gate: gate},
			func(ctx context.Context, _ background.Tick) error {
				<-ctx.Done()
				x.trace("loop stopped")
				return nil
			})
		return fw.ServeFunc(func(ctx context.Context) error {
			for _, line := range run(ctx, all) {
				_, _ = fmt.Fprintln(x.out, line)
			}
			// the health report, once the loop has had its first run
			time.Sleep(100 * time.Millisecond)
			return nil
		}), nil
	}
}

// run takes the demo key on every instance and reports what happened.
func run(ctx context.Context, all *locks.Locks) []string {
	key := locks.MustServiceKey("locks-demo")
	var report []string
	for _, name := range all.Names() {
		lock, err := all.Get(name)
		if err != nil {
			report = append(report, name+": "+err.Error())
			continue
		}
		res, err := locks.With(ctx, lock, key, 10*time.Second, 5*time.Second,
			func(_ context.Context, lease locks.Lease) (string, error) {
				return fmt.Sprintf("token %d", lease.Fence()), nil
			})
		switch {
		case err != nil:
			report = append(report, name+": failed: "+err.Error())
		case res.Outcome == locks.Completed:
			report = append(report, name+": completed with "+res.Value)
		case res.Outcome == locks.NotAcquired:
			report = append(report, name+": not acquired")
		case res.Outcome == locks.Lost:
			report = append(report, name+": lost while running")
		}
	}
	return report
}

// healthLine formats a health report as the Java demo does.
func healthLine(r fw.HealthReport) string {
	names := make([]string, 0, len(r.Results))
	for _, x := range r.Results {
		names = append(names, x.Name)
	}
	state := "DOWN"
	if r.IsUp() {
		state = "up"
	}
	return "health: " + state + " [" + strings.Join(names, ", ") + "]"
}
