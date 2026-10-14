package fwtest

import (
	"context"
	"errors"
	"fmt"
	"math/rand/v2"
	"net/url"
	"os"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

// EnvDatabaseURL names the variable that points the tests at an existing PostgreSQL server
// instead of an embedded one: a postgres:// URL of a role that may CREATE DATABASE.
const EnvDatabaseURL = "FW_TEST_DATABASE_URL"

// Option configures [Main].
type Option func(*options)

type options struct {
	template func(ctx context.Context, url string) error
}

// Template sets the function that prepares the template database once per test binary, for
// example by applying migrations. It receives the template's postgres:// URL and must close every
// connection it opens before returning; each test's database is cloned from the result.
func Template(setup func(ctx context.Context, url string) error) Option {
	return func(o *options) { o.template = setup }
}

// Database is a fresh database for one test, cloned from the template.
type Database struct {
	// Name is the database name.
	Name string
	// URL is a postgres:// URL of the database (user, password, host, port, sslmode=disable).
	URL string
}

var state struct {
	mu         sync.Mutex
	mainCalled bool
	opts       options

	pgOnce  sync.Once
	pg      *pgServer
	pgErr   error
	tplOnce sync.Once
	tplName string
	tplErr  error
	cloneMu sync.Mutex
	counter atomic.Int64

	redisOnce sync.Once
	redis     *redisServer
	redisErr  error
}

// Main runs the test binary's tests, then stops the servers this package started and removes
// their files. Use it as the TestMain of every package whose tests call [NewDatabase] or
// [NewRedis].
func Main(m *testing.M, opts ...Option) {
	os.Exit(run(m, opts))
}

func run(m *testing.M, opts []Option) int {
	state.mu.Lock()
	state.mainCalled = true
	for _, o := range opts {
		o(&state.opts)
	}
	state.mu.Unlock()
	code := m.Run()
	stopRedis()
	stopPostgres()
	return code
}

// NewDatabase returns a fresh database for this test, cloned from the template database, and
// drops it when the test ends. The first call in a test binary starts the server and prepares
// the template.
func NewDatabase(t testing.TB) Database {
	t.Helper()
	state.mu.Lock()
	called := state.mainCalled
	state.mu.Unlock()
	if !called {
		t.Fatal("fwtest: Main was not called; add `func TestMain(m *testing.M) { fwtest.Main(m) }` to the package's tests")
	}
	pg, err := startPostgres()
	if err != nil {
		t.Fatalf("fwtest: PostgreSQL: %v", err)
	}
	tpl, err := prepareTemplate(pg)
	if err != nil {
		t.Fatalf("fwtest: template database: %v", err)
	}
	name := fmt.Sprintf("t_%d_%d", os.Getpid(), state.counter.Add(1))
	ctx := context.Background()
	state.cloneMu.Lock()
	err = pg.exec(ctx, fmt.Sprintf("CREATE DATABASE %s TEMPLATE %s", name, tpl))
	state.cloneMu.Unlock()
	if err != nil {
		t.Fatalf("fwtest: create database %s: %v", name, err)
	}
	t.Cleanup(func() {
		if err := pg.exec(context.Background(), fmt.Sprintf("DROP DATABASE IF EXISTS %s WITH (FORCE)", name)); err != nil {
			t.Logf("fwtest: drop database %s: %v", name, err)
		}
	})
	return Database{Name: name, URL: pg.urlFor(name)}
}

type pgServer struct {
	base *url.URL // the server's URL, any database
	stop func()
}

func (s *pgServer) urlFor(database string) string {
	u := *s.base
	u.Path = "/" + database
	q := u.Query()
	if q.Get("sslmode") == "" {
		q.Set("sslmode", "disable")
	}
	u.RawQuery = q.Encode()
	return u.String()
}

// exec runs one statement on the server's maintenance database.
func (s *pgServer) exec(ctx context.Context, sql string) error {
	ctx, cancel := context.WithTimeout(ctx, 60*time.Second)
	defer cancel()
	conn, err := pgx.Connect(ctx, s.urlFor("postgres"))
	if err != nil {
		return err
	}
	defer conn.Close(context.Background())
	_, err = conn.Exec(ctx, sql)
	return err
}

func startPostgres() (*pgServer, error) {
	state.pgOnce.Do(func() {
		state.pg, state.pgErr = newPostgres()
	})
	return state.pg, state.pgErr
}

func newPostgres() (*pgServer, error) {
	if raw := os.Getenv(EnvDatabaseURL); raw != "" {
		if err := checkDatabaseURL(raw); err != nil {
			return nil, err
		}
		u, err := url.Parse(raw)
		if err != nil {
			return nil, err
		}
		return &pgServer{base: u, stop: func() {}}, nil
	}
	raw, stop, err := pgembed.Start(context.Background())
	if err != nil {
		return nil, err
	}
	base, err := url.Parse(raw)
	if err != nil {
		stop()
		return nil, err
	}
	return &pgServer{base: base, stop: stop}, nil
}

func stopPostgres() {
	if state.pg == nil {
		return
	}
	if state.tplName != "" && state.tplErr == nil {
		ctx := context.Background()
		_ = state.pg.exec(ctx, fmt.Sprintf("ALTER DATABASE %s IS_TEMPLATE false", state.tplName))
		_ = state.pg.exec(ctx, fmt.Sprintf("DROP DATABASE IF EXISTS %s WITH (FORCE)", state.tplName))
	}
	state.pg.stop()
}

func prepareTemplate(pg *pgServer) (string, error) {
	state.tplOnce.Do(func() {
		name := fmt.Sprintf("fw_tpl_%d_%d", os.Getpid(), rand.Uint32())
		ctx := context.Background()
		if err := pg.exec(ctx, "CREATE DATABASE "+name); err != nil {
			state.tplErr = err
			return
		}
		state.tplName = name
		if setup := state.opts.template; setup != nil {
			if err := setup(ctx, pg.urlFor(name)); err != nil {
				state.tplErr = fmt.Errorf("Template function: %w", err)
				return
			}
		}
		// Nobody may connect to the template from now on, so cloning never finds it in use.
		for _, sql := range []string{
			fmt.Sprintf("ALTER DATABASE %s WITH ALLOW_CONNECTIONS false IS_TEMPLATE true", name),
			fmt.Sprintf("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '%s'", name),
		} {
			if err := pg.exec(ctx, sql); err != nil {
				state.tplErr = err
				return
			}
		}
	})
	if state.tplErr != nil {
		return "", state.tplErr
	}
	if state.tplName == "" {
		return "", errors.New("not prepared")
	}
	return state.tplName, nil
}
