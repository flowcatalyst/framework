// Package pgembed starts an embedded PostgreSQL 18 server for tests and tools: on a free port in
// a temporary directory, never on the platform's development ports (15432 for PostgreSQL, 8080
// for HTTP). fwtest and sqlfw share it.
package pgembed

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"time"

	embeddedpostgres "github.com/fergusstrange/embedded-postgres"
)

// Ports nobody started here may touch: the platform's development PostgreSQL and its HTTP server.
const (
	devDatabasePort = 15432
	devHTTPPort     = 8080
)

// AllowedPort reports whether p may be used by a server started here.
func AllowedPort(p int) bool { return p != devDatabasePort && p != devHTTPPort }

// FreePort returns a TCP port on 127.0.0.1 that was free a moment ago, never 15432 or 8080.
func FreePort() (int, error) {
	for range 20 {
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return 0, fmt.Errorf("free port: %w", err)
		}
		addr, ok := l.Addr().(*net.TCPAddr)
		_ = l.Close()
		if !ok {
			return 0, errors.New("free port: not a TCP address")
		}
		if AllowedPort(addr.Port) {
			return addr.Port, nil
		}
	}
	return 0, errors.New("free port: only reserved ports were offered")
}

// CheckURL refuses a server URL that must not be used: one that does not parse, is not a
// postgres:// URL, or points at port 15432, the development database. name is the setting the
// URL came from, for the message.
func CheckURL(name, raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("%s does not parse: %w", name, err)
	}
	if u.Scheme != "postgres" && u.Scheme != "postgresql" {
		return fmt.Errorf("%s must be a postgres:// URL, got scheme %q", name, u.Scheme)
	}
	port := u.Port()
	if port == "" {
		port = "5432"
	}
	if n, err := strconv.Atoi(port); err != nil || n == devDatabasePort {
		return fmt.Errorf("%s points at port %s; this never uses the development database on %d", name, port, devDatabasePort)
	}
	return nil
}

// Start starts an embedded PostgreSQL 18 server on a free port in a temporary directory and
// returns the postgres:// URL of its maintenance database "postgres" (user postgres, password
// postgres). stop shuts the server down and removes the directory; call it exactly once.
func Start(ctx context.Context) (string, func(), error) {
	if err := ctx.Err(); err != nil {
		return "", nil, err
	}
	tmp, err := os.MkdirTemp("", "fw-pg-*")
	if err != nil {
		return "", nil, err
	}
	port, err := FreePort()
	if err != nil {
		_ = os.RemoveAll(tmp)
		return "", nil, err
	}
	var log bytes.Buffer // the server's own output, shown only when it fails to start
	pg := embeddedpostgres.NewDatabase(embeddedpostgres.DefaultConfig().
		Version(embeddedpostgres.V18).
		Logger(&log).
		Port(uint32(port)). //nolint:gosec // a TCP port fits in uint32
		DataPath(filepath.Join(tmp, "data")).
		RuntimePath(filepath.Join(tmp, "runtime")).
		Username("postgres").Password("postgres").Database("postgres").
		StartTimeout(90 * time.Second))
	if err := pg.Start(); err != nil {
		_ = os.RemoveAll(tmp)
		return "", nil, fmt.Errorf("start embedded PostgreSQL 18 on port %d: %w\n%s", port, err, log.String())
	}
	return fmt.Sprintf("postgres://postgres:postgres@127.0.0.1:%d/postgres", port), func() {
		_ = pg.Stop()
		_ = os.RemoveAll(tmp)
	}, nil
}
