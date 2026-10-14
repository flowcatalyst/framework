package database_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"maps"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"testing/fstest"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

var quiet = slog.New(slog.DiscardHandler)

func open(t *testing.T) *database.DB {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	return db
}

func set(module string, files map[string]string) fw.MigrationSet {
	m := fstest.MapFS{}
	for name, body := range files {
		m[name] = &fstest.MapFile{Data: []byte(body)}
	}
	return fw.MigrationSet{Module: module, Files: m}
}

func problems(t *testing.T, err error) string {
	t.Helper()
	se, ok := errors.AsType[*fw.StartupError](err)
	if !ok {
		t.Fatalf("err %v, want *fw.StartupError", err)
	}
	return se.Error()
}

func TestMigrationsApplyInOrderOnceAndRecordHistory(t *testing.T) {
	t.Parallel()
	db := open(t)
	sets := []fw.MigrationSet{
		set("first", map[string]string{"V0002__add_b.sql": "ALTER TABLE a ADD COLUMN b int;", "V0001__create_a.sql": "CREATE TABLE a (id int); CREATE INDEX a_id ON a (id);", "README.txt": "ignored"}),
		set("second", map[string]string{"V0001__uses_a.sql": "INSERT INTO a (id, b) VALUES (1, 2)"}),
	}
	for range 2 { // the second run applies nothing
		if err := database.ApplyMigrations(t.Context(), db, sets, quiet); err != nil {
			t.Fatal(err)
		}
	}
	var n int
	if err := db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM a").Scan(&n); err != nil || n != 1 {
		t.Fatalf("rows in a: %d %v", n, err)
	}
	rows, err := db.Pool().Query(t.Context(), "SELECT module || ':' || version || ':' || description FROM fw_schema_history ORDER BY applied_at, module, version")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var got []string
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		got = append(got, s)
	}
	if strings.Join(got, ",") != "first:1:create a,first:2:add b,second:1:uses a" {
		t.Errorf("history %v", got)
	}
}

func TestConcurrentRunnersApplyEachFileOnce(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	s := []fw.MigrationSet{set("m", map[string]string{"V1__create.sql": "CREATE TABLE once (id int)"})}
	var wg sync.WaitGroup
	errs := make(chan error, 4)
	for range 4 {
		wg.Go(func() {
			db, err := database.Open(context.WithoutCancel(t.Context()), "main", url)
			if err != nil {
				errs <- err
				return
			}
			defer db.Close()
			errs <- database.ApplyMigrations(t.Context(), db, s, quiet)
		})
	}
	wg.Wait()
	close(errs)
	for err := range errs {
		if err != nil {
			t.Errorf("a runner failed (the advisory lock must serialise them): %v", err)
		}
	}
}

func TestMigrationStartupFailures(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	base := map[string]string{"V0001__one.sql": "CREATE TABLE one (id int)", "V0003__three.sql": "CREATE TABLE three (id int)"}
	cases := []struct {
		name  string
		after map[string]string
		want  string
	}{
		{
			"changed checksum",
			map[string]string{"V0001__one.sql": "CREATE TABLE one (id bigint)", "V0003__three.sql": base["V0003__three.sql"]},
			"module 'm': key 'V0001__one.sql': changed after it was applied (applied checksum ",
		},
		{
			"file gone",
			map[string]string{"V0003__three.sql": base["V0003__three.sql"]},
			"module 'm': key 'V1': version 1 (one) was applied but its file is gone",
		},
		{
			"added below the applied ones",
			map[string]string{"V0001__one.sql": base["V0001__one.sql"], "V0002__two.sql": "SELECT 1", "V0003__three.sql": base["V0003__three.sql"]},
			"module 'm': key 'V0002__two.sql': was never applied, but a higher version (3) already was; versions must not be added below the applied ones",
		},
		{
			"duplicate version",
			map[string]string{"V0001__one.sql": base["V0001__one.sql"], "V0003__three.sql": base["V0003__three.sql"], "V3__again.sql": "SELECT 1"},
			"has the same version (3) as",
		},
		{
			"bad file name",
			map[string]string{"V0001__one.sql": base["V0001__one.sql"], "V0003__three.sql": base["V0003__three.sql"], "create.sql": "SELECT 1"},
			"module 'm': key 'create.sql': does not match V<version>__<description>.sql",
		},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			db := open(t)
			if err := database.ApplyMigrations(ctx, db, []fw.MigrationSet{set("m", base)}, quiet); err != nil {
				t.Fatal(err)
			}
			err := database.ApplyMigrations(ctx, db, []fw.MigrationSet{set("m", c.after)}, quiet)
			if text := problems(t, err); !strings.Contains(text, c.want) {
				t.Errorf("problems:\n%s\nwant %q", text, c.want)
			}
		})
	}
}

func TestAFailingFileRollsBackWithItsHistoryRow(t *testing.T) {
	t.Parallel()
	db := open(t)
	err := database.ApplyMigrations(t.Context(), db, []fw.MigrationSet{set("m", map[string]string{
		"V1__good.sql": "CREATE TABLE good (id int)",
		"V2__bad.sql":  "CREATE TABLE half (id int); SELEC broken",
	})}, quiet)
	if text := problems(t, err); !strings.Contains(text, "module 'm': key 'V2__bad.sql': migration failed and was rolled back: ") {
		t.Errorf("problems: %s", text)
	}
	var half bool
	var versions int
	_ = db.Pool().QueryRow(t.Context(), "SELECT to_regclass('half') IS NOT NULL, (SELECT count(*) FROM fw_schema_history)").Scan(&half, &versions)
	if half || versions != 1 {
		t.Errorf("table half exists=%v, history rows=%d; want the bad file rolled back and the good one kept", half, versions)
	}
}

func TestConnectMigrateAndHealthThroughAnApplication(t *testing.T) {
	t.Parallel()
	url := fwtest.NewDatabase(t).URL
	env := map[string]string{
		"APP_ENV":                          "test",
		"DATABASE_INSTANCES_MAIN_URL":      "jdbc:" + strings.Replace(url, "postgres://postgres:postgres@", "postgresql://", 1),
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
	}
	app := fw.New("db-demo", fw.WithEnvironment(env), fw.WithConfigDir(configDir(t)), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	declared := database.Declare(app)
	app.Module("orders").Migrations(fstest.MapFS{"V0001__create_orders.sql": {Data: []byte("CREATE TABLE orders (id text)")}})
	var main *database.DB
	r, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		dbs, err := database.Connect(ctx, b, declared)
		if err != nil {
			return nil, err
		}
		if err := database.Migrate(ctx, b, dbs); err != nil {
			return nil, err
		}
		db, ok := dbs.Default()
		if !ok {
			return nil, errors.New("no default instance")
		}
		main = db
		return nil, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if main == nil {
		t.Fatal("build did not run")
	}
	if ok, err := main.TableExists(t.Context(), "orders"); err != nil || !ok {
		t.Errorf("orders not migrated: %v %v", ok, err)
	}
	if res, ok := r.Health(t.Context()).Result("database:main"); !ok || res.Status != fw.Up || res.Criticality != fw.Critical {
		t.Errorf("health database:main %+v", res)
	}
	_ = r.Shutdown()
	if err := main.Ping(context.Background()); err == nil {
		t.Error("the pool is still open after shutdown")
	}
}

func TestConnectFailures(t *testing.T) {
	t.Parallel()
	cases := map[string]struct{ url, want string }{
		"key=value is refused": {"host=127.0.0.1 dbname=x", "url must be jdbc:postgresql://"},
		"nobody answers":       {"postgres://u:secret-pw@127.0.0.1:1/x?connect_timeout=1", "cannot connect to database instance 'main'"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			app := fw.New("db-demo", fw.WithEnvironment(map[string]string{"DATABASE_INSTANCES_MAIN_URL": c.url}),
				fw.WithConfigDir(configDir(t)), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
			declared := database.Declare(app)
			_, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
				_, err := database.Connect(ctx, b, declared)
				return nil, err
			})
			if err == nil || !strings.Contains(err.Error(), c.want) {
				t.Fatalf("err %v, want %q", err, c.want)
			}
			if strings.Contains(err.Error(), "secret-pw") {
				t.Errorf("the password leaked into the error: %v", err)
			}
		})
	}
}

// configDir writes a configuration with one database instance, main, whose values the tests
// give as environment variables (an instance exists only when a file names it).
func configDir(t *testing.T) string {
	t.Helper()
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, "application.yaml"), []byte("database:\n  instances:\n    main:\n      url: set-by-the-test\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	return dir
}

func TestFilter(t *testing.T) {
	t.Parallel()
	var f database.Filter
	f.Eq("client_id", "acme")
	status := "OPEN"
	f.EqPtr("status", &status)
	f.EqPtr("customer", nil)
	f.Any("sku", []string{"a", "b"})
	f.Clause("customer LIKE $%d", "%x%")
	f.Cond("deleted_at IS NULL")
	limit := f.Arg(10)
	if got := f.Where(); got != " WHERE client_id = $1 AND status = $2 AND sku = ANY($3) AND customer LIKE $4 AND deleted_at IS NULL" {
		t.Errorf("Where %q", got)
	}
	if limit != 5 || len(f.Args()) != 5 {
		t.Errorf("limit position %d, args %v", limit, f.Args())
	}
	var empty database.Filter
	if empty.Where() != "" {
		t.Error("an empty filter has a WHERE")
	}
}

func TestPoolRangesAreStartupProblems(t *testing.T) {
	t.Parallel()
	cases := map[string]struct {
		env  map[string]string
		want []string
	}{
		"pool.max too large": {
			map[string]string{"DATABASE_INSTANCES_MAIN_POOL_MAX": "5000"},
			[]string{"key 'database.instances.main.pool.max' (from environment variable DATABASE_INSTANCES_MAIN_POOL_MAX): must be 1 to 1000, was 5000"},
		},
		"connect-timeout too short, not raised silently": {
			map[string]string{"DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT": "100ms"},
			[]string{"key 'database.instances.main.pool.connect-timeout'", "must be 250ms to 5m0s, was 100ms"},
		},
		"connect-timeout too long": {
			map[string]string{"DATABASE_INSTANCES_MAIN_POOL_CONNECT_TIMEOUT": "6m"},
			[]string{"must be 250ms to 5m0s, was 6m0s"},
		},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			env := map[string]string{"DATABASE_INSTANCES_MAIN_URL": "postgres://u@127.0.0.1:1/x"}
			maps.Copy(env, c.env)
			app := fw.New("db-demo", fw.WithEnvironment(env), fw.WithConfigDir(configDir(t)), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
			database.Declare(app)
			_, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) { return nil, nil })
			if err == nil {
				t.Fatal("want a start-up problem")
			}
			for _, w := range c.want {
				if !strings.Contains(err.Error(), w) {
					t.Errorf("missing %q in %v", w, err)
				}
			}
		})
	}
}

func TestMinIdleAboveMaxIsOneProblemBeforeAnyConnection(t *testing.T) {
	t.Parallel()
	env := map[string]string{
		"DATABASE_INSTANCES_MAIN_URL":           "postgres://u@127.0.0.1:1/x", // nobody answers: a connection attempt would say so
		"DATABASE_INSTANCES_MAIN_POOL_MAX":      "3",
		"DATABASE_INSTANCES_MAIN_POOL_MIN_IDLE": "9",
	}
	app := fw.New("db-demo", fw.WithEnvironment(env), fw.WithConfigDir(configDir(t)), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	declared := database.Declare(app)
	_, err := app.Start(t.Context(), func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		_, err := database.Connect(ctx, b, declared)
		return nil, err
	})
	se, ok := errors.AsType[*fw.StartupError](err)
	if !ok || len(se.Problems) != 1 || se.Problems[0].Key != "database.instances.main.pool.min-idle" ||
		se.Problems[0].Message != "must be 0 to pool.max (3), was 9" {
		t.Fatalf("%v", err)
	}
}
