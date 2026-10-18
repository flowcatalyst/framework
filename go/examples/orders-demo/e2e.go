package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/internal/pgembed"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

// The browser end-to-end launcher: `orders-demo e2e <app-port> <control-port> [<public-origin>]`.
//
// It is the Go counterpart of the Java OrdersE2e (examples/orders-demo, test sources) on the shared
// kit (docs/guide/e2e-kit.md): the real application serving the built single-page application, on an
// embedded PostgreSQL 18 (never 15432), with authtest.Provider standing in for the platform's
// sign-in, and the kit's control endpoints on the control port. Playwright's web server starts it
// and waits for GET /control/ready; it prints READY and runs until SIGTERM or SIGINT.
//
// `--dist <dir>` (after the ports) serves the SPA from a directory (ui/apps/orders writes its build
// to examples/orders-demo/web/dist); without it the embedded build is served, which is stale unless
// the binary was compiled after `vite build`.
//
// Control endpoints (any method): ready, sign-in-as, provider-down, authorize-error, end-sessions,
// reset (deletes every order; sessions stay) and login-rate-limit (a real limiter of one request a
// minute on the sign-in routes). platform-reads answers ok and does nothing: the Go demo has no
// platform client or admin area yet (G6), so there are no platform reads to fail.

const e2eClientID = "orders-web"

func e2eMain(ctx context.Context, args []string) int {
	if err := runE2e(ctx, args, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "orders-demo e2e:", err)
		return 1
	}
	return 0
}

// switchableLimit is the sign-in rate limit that is off until the suite asks.
type switchableLimit struct {
	current atomic.Pointer[httpfw.Middleware]
}

func (s *switchableLimit) middleware() httpfw.Middleware {
	return func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			if m := s.current.Load(); m != nil {
				return (*m)(next)(w, r)
			}
			return next(w, r)
		}
	}
}

// runE2e runs the launcher until ctx ends; out receives the READY line.
func runE2e(ctx context.Context, args []string, out io.Writer) error {
	if len(args) < 2 {
		return errors.New("usage: orders-demo e2e <app-port> <control-port> [--dist <dir>] [<public-origin>]")
	}
	appPort, err := strconv.Atoi(args[0])
	if err != nil {
		return fmt.Errorf("app port: %w", err)
	}
	controlPort, err := strconv.Atoi(args[1])
	if err != nil {
		return fmt.Errorf("control port: %w", err)
	}
	if !pgembed.AllowedPort(appPort) || !pgembed.AllowedPort(controlPort) {
		return errors.New("ports 8080 and 15432 belong to the developer's platform")
	}
	origin := "http://127.0.0.1:" + args[0]
	var dist fs.FS
	for rest := args[2:]; len(rest) > 0; {
		if rest[0] == "--dist" && len(rest) > 1 {
			dist = os.DirFS(rest[1])
			if _, err := fs.Stat(dist, "index.html"); err != nil {
				return fmt.Errorf("--dist has no index.html: %w", err)
			}
			rest = rest[2:]
			continue
		}
		origin, rest = strings.TrimSuffix(rest[0], "/"), rest[1:]
	}
	provider := authtest.Start(fw.SystemClock())
	defer provider.Close()
	provider.Client(e2eClientID, "", origin+"/auth/callback")
	provider.SignInAs(e2ePerson("orders-demo:clerk", "Ada Lovelace", "", ""))

	pgURL, stopPG, err := pgembed.Start(ctx)
	if err != nil {
		return err
	}
	defer stopPG()

	env := map[string]string{
		"APP_ENV":                          "test",
		"HTTP_HOST":                        "127.0.0.1",
		"HTTP_PORT":                        args[0],
		"AUTH_ISSUER":                      provider.Issuer(),
		"AUTH_CLIENT_ID":                   e2eClientID,
		"AUTH_PUBLIC_BASE_URL":             origin,
		"DATABASE_INSTANCES_MAIN_URL":      pgURL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
	}
	limit := &switchableLimit{}
	var (
		mu sync.Mutex
		db *database.DB
	)
	app := newApp(fw.WithEnvironment(env), fw.WithConfigDir("config"))
	d := declare(app)
	run, err := app.Start(ctx, buildWeb(d, deps{
		spa:         dist,
		authOptions: []auth.Option{auth.RateLimit(limit.middleware())},
		served: func(_ *auth.Module, handle *database.DB) {
			mu.Lock()
			db = handle
			mu.Unlock()
		},
	}))
	if err != nil {
		return err
	}
	defer func() { _ = run.Shutdown() }()

	execute := func(sql string) error {
		mu.Lock()
		handle := db
		mu.Unlock()
		if handle == nil {
			return errors.New("the database is not ready")
		}
		_, err := handle.Pool().Exec(ctx, sql)
		return err
	}
	var limiters atomic.Int32
	control := map[string]func(q map[string]string) (string, error){
		"/control/ready": func(map[string]string) (string, error) { return "ready", nil },
		"/control/sign-in-as": func(q map[string]string) (string, error) {
			name := orDefault(q["name"], "Ada Lovelace")
			if _, ok := q["client"]; ok || q["sub"] != "" {
				provider.SignInAs(e2ePerson(q["roles"], name, orDefault(q["client"], "acme"), orDefault(q["sub"], "prn_ada")))
			} else {
				provider.SignInAs(e2ePerson(q["roles"], name, "", ""))
			}
			return "ok", nil
		},
		"/control/provider-down": func(q map[string]string) (string, error) {
			provider.Down(q["value"] == "true")
			return "ok", nil
		},
		"/control/authorize-error": func(q map[string]string) (string, error) {
			provider.AuthorizeError(q["value"])
			return "ok", nil
		},
		"/control/end-sessions": func(map[string]string) (string, error) {
			return "ok", execute("DELETE FROM fw_session")
		},
		"/control/reset": func(map[string]string) (string, error) {
			return "ok", execute("TRUNCATE order_lines, orders, outbox_messages CASCADE")
		},
		"/control/login-rate-limit": func(q map[string]string) (string, error) {
			if q["value"] != "true" {
				limit.current.Store(nil)
				return "ok", nil
			}
			l, err := ratelimit.NewMemory(fmt.Sprintf("auth-e2e-%d", limiters.Add(1)), ratelimit.MustPolicy(1, time.Minute, 1))
			if err != nil {
				return "", err
			}
			m, err := ratelimit.Middleware(l, ratelimit.ClientAddress())
			if err != nil {
				return "", err
			}
			limit.current.Store(&m)
			return "ok", nil
		},
		"/control/platform-reads": func(map[string]string) (string, error) { return "ok", nil },
	}
	mux := http.NewServeMux()
	for path, handler := range control {
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			q := make(map[string]string)
			for k, v := range r.URL.Query() {
				if len(v) > 0 {
					q[k] = v[0]
				}
			}
			body, err := handler(q)
			status := http.StatusOK
			if err != nil {
				status, body = http.StatusInternalServerError, err.Error()
			}
			w.WriteHeader(status)
			_, _ = io.WriteString(w, body)
		})
	}
	listener, err := (&net.ListenConfig{}).Listen(ctx, "tcp", "127.0.0.1:"+args[1])
	if err != nil {
		return err
	}
	controlServer := &http.Server{Handler: mux, ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = controlServer.Serve(listener) }()
	defer func() { _ = controlServer.Close() }()
	fmt.Fprintf(out, "READY app=http://127.0.0.1:%d public=%s control=http://127.0.0.1:%d\n", appPort, origin, controlPort)
	<-ctx.Done()
	return nil
}

func orDefault(v, fallback string) string {
	if v == "" {
		return fallback
	}
	return v
}

// e2ePerson is the Java E2eLauncher.person: with no client a platform-wide user (the provider's
// defaults), otherwise one client's user.
func e2ePerson(roles, name, client, sub string) map[string]any {
	list := []any{}
	for r := range strings.SplitSeq(roles, ",") {
		if strings.TrimSpace(r) != "" {
			list = append(list, r)
		}
	}
	u := map[string]any{"name": name, "roles": list}
	if sub == "" {
		u["email"] = "ada@example.test"
	} else {
		u["sub"] = sub
		u["email"] = sub + "@example.test"
	}
	if client != "" {
		u["type"], u["tier"], u["clients"] = "USER", "CLIENT", []any{"clt_" + client + ":" + client}
	}
	return u
}
