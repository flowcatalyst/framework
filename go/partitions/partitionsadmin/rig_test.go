package partitionsadmin_test

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/objectstore"
	"github.com/flowcatalyst/framework/go/partitions"
	"github.com/flowcatalyst/framework/go/partitions/partitionsadmin"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{{Module: partitions.ModuleID, Files: partitions.Migrations()}},
			slog.New(slog.DiscardHandler))
	}))
}

var t0 = time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)

// resolver is the test's ContextResolver: X-Perms are the caller's permissions (a request without the
// header is not signed in) and X-Tier its tier (absent: no tier).
type resolver struct{}

func (resolver) Resolve(r *http.Request) (fw.Context, error) {
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
	cx := fw.ContextFor(fw.MustClientID("demo")).WithPrincipalOfKind(fw.MustPrincipalID("prn_TESTOP"), fw.PrincipalUser)
	if tier := r.Header.Get("X-Tier"); tier != "" {
		cx = cx.WithTier(tier)
	}
	return cx.WithPermissions(perms...), nil
}

type logBuf struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *logBuf) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *logBuf) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

const (
	idTable  = `CREATE TABLE readings (id text COLLATE "C" PRIMARY KEY, payload text) PARTITION BY RANGE (id)`
	colTable = `CREATE TABLE samples (id text NOT NULL, recorded_at timestamptz NOT NULL, payload text, PRIMARY KEY (id, recorded_at)) PARTITION BY RANGE (recorded_at)`
)

// rig is an application "acme" with the admin skeleton, the partitions module over two tables and the
// partitions section, on a database of its own.
type rig struct {
	t       *testing.T
	db      *database.DB
	clock   *fwtest.Clock
	logs    *logBuf
	handler http.Handler
	adm     *admin.Declared
	web     *httpfw.Declared
	ps      *partitions.Partitions
	// lockStore is the lock store the partitions module's loop is gated by.
	lockStore locks.Store
}

func startRig(t *testing.T) *rig {
	t.Helper()
	d := fwtest.NewDatabase(t)
	db, err := database.Open(t.Context(), "main", d.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	for _, ddl := range []string{idTable, colTable} {
		if _, err := db.Pool().Exec(t.Context(), ddl); err != nil {
			t.Fatal(err)
		}
	}
	r := &rig{t: t, db: db, clock: fwtest.NewClock(t0), logs: &logBuf{}}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte("database:\n  instances:\n    main:\n      url: set-by-the-test\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	app := fw.New("pa-test", fw.WithEnvironment(map[string]string{
		"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0", "DATABASE_INSTANCES_MAIN_URL": d.URL,
	}), fw.WithConfigDir(dir), fw.WithClock(r.clock), fw.WithOutput(io.Discard, io.Discard),
		fw.WithLogger(slog.New(slog.NewTextHandler(r.logs, &slog.HandlerOptions{Level: slog.LevelInfo}))))
	r.web = httpfw.Declare(app)
	r.adm = admin.Declare(r.web, fw.MustApplicationCode("acme"))
	pa := partitionsadmin.Declare(r.adm)
	dbDecl := database.Declare(app)
	pd := partitions.Declare(app)
	od := objectstore.Declare(app)
	ld := locks.Declare(app)
	r.lockStore = locks.NewMemoryStore(r.clock)
	run, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		dbs, err := database.Connect(ctx, b, dbDecl)
		if err != nil {
			return nil, err
		}
		stores, err := objectstore.Build(ctx, b, od, objectstore.WithStore("archive", objectstore.Memory()))
		if err != nil {
			return nil, err
		}
		lk, err := locks.Build(ctx, b, ld, locks.WithStore("main", func(context.Context, *fw.Boot) (locks.Store, error) { return r.lockStore, nil }))
		if err != nil {
			return nil, err
		}
		r.ps, err = partitions.Build(ctx, b, pd, dbs, partitions.WithObjectStores(stores), partitions.WithLocks(lk),
			partitions.WithTable(partitions.Table{
				Name: "readings", By: partitions.ByID("rdg"), Interval: partitions.Month, Ahead: 2,
				Retention: partitions.ArchiveThenDropAfter(90*24*time.Hour, "archive"),
			}),
			partitions.WithTable(partitions.Table{
				Name: "samples", By: partitions.ByColumn("recorded_at"), Interval: partitions.Week, Ahead: 1,
				Retention: partitions.DropAfter(30 * 24 * time.Hour),
			}))
		if err != nil {
			return nil, err
		}
		skeleton, err := r.adm.Build(b)
		if err != nil {
			return nil, err
		}
		routes, err := pa.Build(r.ps)
		if err != nil {
			return nil, err
		}
		srv, err := httpfw.NewServer(b, r.web, []*httpfw.Routes{skeleton, routes}, httpfw.WithResolver(resolver{}))
		if err != nil {
			return nil, err
		}
		r.handler = srv.Handler()
		return srv, nil
	})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return r
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

func (a answer) errorCode() string {
	if e, ok := a.tree()["error"].(map[string]any); ok {
		s, _ := e["code"].(string)
		return s
	}
	s, _ := a.tree()["error"].(string)
	return s
}

func (a answer) errorMessage() string {
	if e, ok := a.tree()["error"].(map[string]any); ok {
		s, _ := e["message"].(string)
		return s
	}
	s, _ := a.tree()["message"].(string)
	return s
}

// caller is who sends a request: its permissions (nil: not signed in) and its tier ("": none).
type caller struct {
	perms []string
	tier  string
}

var (
	operatorWithBoth = caller{perms: []string{"acme:admin:console:view", "acme:admin:partitions:view", "acme:admin:partitions:manage"}, tier: "ANCHOR"}
	operatorWild     = caller{perms: []string{"acme:admin:*:*"}, tier: "ANCHOR"}
	operatorNoPerms  = caller{perms: []string{"acme:admin:console:view"}, tier: "ANCHOR"}
	operatorViewOnly = caller{perms: []string{"acme:admin:console:view", "acme:admin:partitions:view"}, tier: "ANCHOR"}
)

func withTier(c caller, tier string) caller { return caller{perms: c.perms, tier: tier} }

func (r *rig) send(method, target, body string, c caller) answer {
	r.t.Helper()
	var reader io.Reader = http.NoBody
	if body != "" {
		reader = strings.NewReader(body)
	}
	req := httptest.NewRequest(method, target, reader)
	if body != "" {
		req.Header.Set("Content-Type", "application/json")
	}
	if c.perms != nil {
		req.Header.Set("X-Perms", strings.Join(c.perms, ","))
	}
	if c.tier != "" {
		req.Header.Set("X-Tier", c.tier)
	}
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return answer{status: rec.Code, body: string(b), header: rec.Result().Header}
}

func (r *rig) exec(sql string, args ...any) {
	r.t.Helper()
	if _, err := r.db.Pool().Exec(r.t.Context(), sql, args...); err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
}
