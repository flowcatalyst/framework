// Command push-demo is the push module's load-test server: a framework application with the
// push module (postgres driver), one public topic pattern (pushdemo:t:{id}), a ticket endpoint
// and a publish endpoint, listening on several ports at once. The load generator in ./loadgen
// drives it; docs/push-load-test-results.md records a run.
//
// A subscribe without credentials needs a ticket, so the generator fetches tickets from
// GET /api/demo/ticket and subscribes the standard way (POST /api/push/subscriptions, then
// GET /api/push/stream?st=...). Pushes are written with push.PublishAlone; the data carries the
// time the server stamped just before the write (ts, unix nanoseconds), so a client can measure
// the delay to itself. The load generator and the server run on one machine, so the clocks agree.
//
// Configuration, all from the environment:
//
//	DEMO_DATABASE_URL    postgres:// URL of an existing database (required; ./pgdb starts one)
//	DEMO_PORT            first port (default 18100)
//	DEMO_PORTS           how many consecutive ports serve the same routes (default 8): macOS gives one
//	                     source address about 16k ephemeral ports per destination address:port
//	DEMO_DEBUG_PORT      127.0.0.1 port of /debug/stats and /debug/pprof (default 18199)
//	PUSH_STREAM_SECRET   at least 32 characters (required, the same across restarts)
//	PUSH_TICKET_KEY      <kid>:<base64url of 32 bytes> (required)
//	PUSH_MAX_STREAMS     defaults to 250000 here; every other push.* key is the module's own
package main

import (
	"context"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"net/http/pprof"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/push"
)

// The demo's one client and topic family.
const (
	demoClient = "acme"
	topicRule  = "pushdemo:t:{id}"
	pushType   = "demo.tick"
)

func main() {
	os.Exit(run())
}

func run() int {
	env := map[string]string{}
	for _, kv := range os.Environ() {
		if k, v, ok := strings.Cut(kv, "="); ok {
			env[k] = v
		}
	}
	dbURL := env["DEMO_DATABASE_URL"]
	if dbURL == "" {
		fmt.Fprintln(os.Stderr, "push-demo: DEMO_DATABASE_URL is required")
		return 2
	}
	first := intEnv(env, "DEMO_PORT", 18100)
	ports := intEnv(env, "DEMO_PORTS", 8)
	debug := intEnv(env, "DEMO_DEBUG_PORT", 18199)
	setDefault(env, "APP_ENV", "local")
	setDefault(env, "HTTP_HOST", "127.0.0.1")
	setDefault(env, "HTTP_PORT", strconv.Itoa(first))
	setDefault(env, "HTTP_METRICS_ENABLED", "true")
	setDefault(env, "PUSH_MAX_STREAMS", "250000")
	setDefault(env, "PUSH_RELAY_INTERVAL", "100ms")
	setDefault(env, "PUSH_STREAM_MAX_LIFETIME", "24h")

	logger := slog.New(slog.NewTextHandler(os.Stderr, &slog.HandlerOptions{Level: slog.LevelWarn}))
	app := fw.New("pushdemo", fw.WithEnvironment(env), fw.WithConfigDir(env["DEMO_CONFIG_DIR"]), fw.WithLogger(logger))
	web := httpfw.Declare(app)
	ld := locks.Declare(app)
	pd := push.Declare(app)
	pd.PublicTopic(topicRule)

	build := func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		db, err := database.Open(ctx, "main", dbURL)
		if err != nil {
			return nil, err
		}
		// the shutdown's phases in the log (a load-test measurement): actions run in reverse order,
		// so this one runs last, after the database closed
		b.OnShutdown("demo:mark-end", func(context.Context) error {
			logger.Warn("demo: shutdown actions done")
			return nil
		})
		b.OnShutdown("db", func(context.Context) error {
			start := time.Now()
			db.Close()
			logger.Warn("demo: database closed", slog.Duration("took", time.Since(start)))
			return nil
		})
		if err := database.ApplyMigrations(ctx, db, []fw.MigrationSet{
			{Module: dblock.ModuleID, Files: dblock.Migrations()},
			{Module: push.ModuleID, Files: push.Migrations()},
		}, logger); err != nil {
			return nil, err
		}
		lk, err := locks.Build(ctx, b, ld, locks.WithStore("main", func(context.Context, *fw.Boot) (locks.Store, error) {
			return dblock.NewStore(db), nil
		}))
		if err != nil {
			return nil, err
		}
		pushes, err := pd.Build(ctx, b, db, lk, push.BasePath("/api"))
		if err != nil {
			return nil, err
		}
		demo := httpfw.NewRoutes("demo")
		d := &demoRoutes{pushes: pushes, acme: fw.ContextFor(fw.MustClientID(demoClient))}
		demo.Handle("GET /api/demo/ticket", d.ticket)
		demo.Handle("POST /api/demo/publish", d.publish)
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{pushes.Routes(), demo})
		if err != nil {
			return nil, err
		}
		extra := &extraPorts{handler: srv.Handler(), first: first, count: ports}
		b.OnStarted("demo:ports", extra.start)
		b.OnShutdown("demo:ports", func(ctx context.Context) error {
			start := time.Now()
			err := extra.stop(ctx)
			logger.Warn("demo: further ports closed", slog.Duration("took", time.Since(start)))
			return err
		})
		dbg := &debugServer{addr: net.JoinHostPort("127.0.0.1", strconv.Itoa(debug))}
		b.OnStarted("demo:debug", dbg.start)
		b.OnShutdown("demo:debug", dbg.stop)
		// runs first among the actions: the loops have stopped and the server has drained
		b.OnShutdown("demo:mark-start", func(context.Context) error {
			logger.Warn("demo: loops stopped and server drained; shutdown actions begin")
			return nil
		})
		return srv, nil
	}
	return app.Launch(context.Background(), os.Args[1:], build)
}

func intEnv(env map[string]string, key string, def int) int {
	if v, err := strconv.Atoi(env[key]); err == nil && v > 0 {
		return v
	}
	return def
}

func setDefault(env map[string]string, key, v string) {
	if env[key] == "" {
		env[key] = v
	}
}

// demoRoutes holds the demo's own routes.
type demoRoutes struct {
	pushes *push.Pushes
	acme   fw.Context
}

// ticket is GET /api/demo/ticket: a ticket for any pushdemo topic, for the demo's client.
func (d *demoRoutes) ticket(w http.ResponseWriter, _ *http.Request) error {
	t, err := d.pushes.Ticket(d.acme, topicRule)
	if err != nil {
		return err
	}
	return httpfw.JSON(w, http.StatusOK, map[string]string{"ticket": t})
}

type publishItem struct {
	Seq   int64  `json:"seq"`
	Topic string `json:"topic"`
}

type publishBody struct {
	Items []publishItem `json:"items"`
	Pad   int           `json:"pad"`
}

type publishAnswer struct {
	Published int   `json:"published"`
	Failed    []int `json:"failed"`
}

// publish is POST /api/demo/publish: each item is one push written with PublishAlone, up to eight
// at a time. The data is {"seq":n,"ts":<unix nanoseconds just before the write>} plus padding.
func (d *demoRoutes) publish(w http.ResponseWriter, r *http.Request) error {
	body, err := httpfw.DecodeJSON[publishBody](r)
	if err != nil {
		return err
	}
	pad := strings.Repeat("x", max(0, min(body.Pad, 8192)))
	var mu sync.Mutex
	var failed []int
	sem := make(chan struct{}, 8)
	var wg sync.WaitGroup
	for i, it := range body.Items {
		sem <- struct{}{}
		wg.Go(func() {
			defer func() { <-sem }()
			data := `{"seq":` + strconv.FormatInt(it.Seq, 10) + `,"ts":` + strconv.FormatInt(time.Now().UnixNano(), 10) + `,"pad":"` + pad + `"}`
			p, err := push.New(it.Topic, pushType, []byte(data))
			if err == nil {
				err = d.pushes.PublishAlone(r.Context(), d.acme, p)
			}
			if err != nil {
				mu.Lock()
				failed = append(failed, i)
				mu.Unlock()
			}
		})
	}
	wg.Wait()
	return httpfw.JSON(w, http.StatusOK, publishAnswer{Published: len(body.Items) - len(failed), Failed: failed})
}

// extraPorts serves the same handler on count-1 further consecutive ports (the first is the
// framework server's own).
type extraPorts struct {
	handler http.Handler
	first   int
	count   int
	servers []*http.Server
}

func (e *extraPorts) start(ctx context.Context) error {
	var lc net.ListenConfig
	for i := 1; i < e.count; i++ {
		addr := net.JoinHostPort("127.0.0.1", strconv.Itoa(e.first+i))
		ln, err := lc.Listen(ctx, "tcp", addr)
		if err != nil {
			return fmt.Errorf("cannot listen on %s: %w", addr, err)
		}
		s := &http.Server{Handler: e.handler, ReadHeaderTimeout: 30 * time.Second}
		e.servers = append(e.servers, s)
		go func() { _ = s.Serve(ln) }()
	}
	return nil
}

func (e *extraPorts) stop(context.Context) error {
	for _, s := range e.servers {
		_ = s.Close()
	}
	return nil
}

// debugServer serves /debug/stats and /debug/pprof on a loopback port.
type debugServer struct {
	addr string
	srv  *http.Server
}

func (d *debugServer) start(ctx context.Context) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/debug/pprof/", pprof.Index)
	mux.HandleFunc("/debug/pprof/profile", pprof.Profile)
	mux.HandleFunc("/debug/pprof/symbol", pprof.Symbol)
	mux.HandleFunc("/debug/pprof/trace", pprof.Trace)
	mux.HandleFunc("/debug/stats", stats)
	var lc net.ListenConfig
	ln, err := lc.Listen(ctx, "tcp", d.addr)
	if err != nil {
		return fmt.Errorf("cannot listen on %s: %w", d.addr, err)
	}
	d.srv = &http.Server{Handler: mux, ReadHeaderTimeout: 30 * time.Second}
	go func() { _ = d.srv.Serve(ln) }()
	return nil
}

func (d *debugServer) stop(context.Context) error {
	if d.srv != nil {
		return d.srv.Close()
	}
	return nil
}

// stats answers runtime.MemStats, the goroutine count and the open file descriptors.
func stats(w http.ResponseWriter, _ *http.Request) {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	fds := -1
	if ents, err := os.ReadDir("/dev/fd"); err == nil {
		fds = len(ents)
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprintf(w, `{"heapAlloc":%d,"heapInuse":%d,"heapSys":%d,"sys":%d,"stackInuse":%d,"numGC":%d,"pauseTotalNs":%d,"goroutines":%d,"fds":%d}`,
		m.HeapAlloc, m.HeapInuse, m.HeapSys, m.Sys, m.StackInuse, m.NumGC, m.PauseTotalNs, runtime.NumGoroutine(), fds)
}
