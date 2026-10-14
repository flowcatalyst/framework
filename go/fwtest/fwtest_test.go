package fwtest

import (
	"context"
	"net/url"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
)

func TestMain(m *testing.M) {
	Main(m, Template(func(ctx context.Context, u string) error {
		conn, err := pgx.Connect(ctx, u)
		if err != nil {
			return err
		}
		defer conn.Close(ctx)
		_, err = conn.Exec(ctx, "CREATE TABLE from_template (n int); INSERT INTO from_template VALUES (42)")
		return err
	}))
}

func TestFreePortNeverReturnsReservedPorts(t *testing.T) {
	t.Parallel()
	for _, p := range []int{15432, 8080} {
		if allowedPort(p) {
			t.Errorf("port %d must never be handed to a test", p)
		}
	}
	for range 200 {
		p, err := FreePort()
		if err != nil {
			t.Fatal(err)
		}
		if p == 15432 || p == 8080 {
			t.Fatalf("FreePort returned reserved port %d", p)
		}
	}
}

func TestDatabaseURLOnPort15432IsRefused(t *testing.T) {
	for _, raw := range []string{
		"postgres://postgres:pw@localhost:15432/postgres",
		"postgresql://u@127.0.0.1:15432",
		"mysql://u@localhost:3306/x",
	} {
		if err := checkDatabaseURL(raw); err == nil {
			t.Errorf("%s: accepted, want refused", raw)
		}
	}
	if err := checkDatabaseURL("postgres://postgres:postgres@127.0.0.1:5432/postgres"); err != nil {
		t.Errorf("port 5432 refused: %v", err)
	}
	t.Setenv(EnvDatabaseURL, "postgres://postgres:postgres@127.0.0.1:15432/postgres")
	if _, err := newPostgres(); err == nil || !strings.Contains(err.Error(), "15432") {
		t.Errorf("newPostgres with %s on 15432: want a refusal naming the port, got %v", EnvDatabaseURL, err)
	}
}

func TestEachTestGetsItsOwnDatabaseClonedFromTheTemplate(t *testing.T) {
	t.Parallel()
	ctx := context.Background()
	a, b := NewDatabase(t), NewDatabase(t)
	if a.Name == b.Name {
		t.Fatalf("two databases with one name %s", a.Name)
	}
	for _, db := range []Database{a, b} {
		u, err := url.Parse(db.URL)
		if err != nil {
			t.Fatal(err)
		}
		if p, _ := strconv.Atoi(u.Port()); p == 15432 || p == 8080 {
			t.Fatalf("database on reserved port %d", p)
		}
		conn, err := pgx.Connect(ctx, db.URL)
		if err != nil {
			t.Fatal(err)
		}
		var n, version int
		if err := conn.QueryRow(ctx, "SELECT n, current_setting('server_version_num')::int FROM from_template").Scan(&n, &version); err != nil {
			t.Fatal(err)
		}
		if n != 42 {
			t.Errorf("%s: template row: got %d", db.Name, n)
		}
		if version/10000 != 18 {
			t.Errorf("%s: want PostgreSQL 18, got server_version_num %d", db.Name, version)
		}
		_ = conn.Close(ctx)
	}
	// A write in one database is not seen in the other.
	connA, err := pgx.Connect(ctx, a.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer connA.Close(ctx)
	if _, err := connA.Exec(ctx, "INSERT INTO from_template VALUES (1)"); err != nil {
		t.Fatal(err)
	}
	connB, err := pgx.Connect(ctx, b.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer connB.Close(ctx)
	var count int
	if err := connB.QueryRow(ctx, "SELECT count(*) FROM from_template").Scan(&count); err != nil {
		t.Fatal(err)
	}
	if count != 1 {
		t.Errorf("database %s sees %d rows, want 1 (isolation from %s)", b.Name, count, a.Name)
	}
}

func TestRedisServerAnswers(t *testing.T) {
	t.Parallel()
	r := NewRedis(t)
	r2 := NewRedis(t)
	if r.Prefix == r2.Prefix {
		t.Fatalf("two tests share the key prefix %q", r.Prefix)
	}
	u, err := url.Parse(r.URL)
	if err != nil {
		t.Fatal(err)
	}
	if p, _ := strconv.Atoi(u.Port()); p == 15432 || p == 8080 {
		t.Fatalf("Redis on reserved port %d", p)
	}
	if err := ping(u.Host); err != nil {
		t.Fatal(err)
	}
}

func TestClock(t *testing.T) {
	t.Parallel()
	start := time.Date(2026, 10, 8, 10, 15, 30, 0, time.UTC)
	c := NewClock(start)
	c.Advance(1500 * time.Millisecond)
	if got := c.Now(); !got.Equal(start.Add(1500 * time.Millisecond)) {
		t.Errorf("after Advance: %v", got)
	}
	c.Set(start)
	if got := c.Now(); !got.Equal(start) {
		t.Errorf("after Set: %v", got)
	}
}
