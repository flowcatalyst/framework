package fwtest

import (
	"errors"
	"fmt"
	"net"
	"net/url"
	"strconv"
)

// Ports a test must never touch: the platform's development PostgreSQL and its HTTP server.
const (
	devDatabasePort = 15432
	devHTTPPort     = 8080
)

// FreePort returns a TCP port on 127.0.0.1 that was free a moment ago, never 15432 or 8080.
func FreePort() (int, error) {
	for range 20 {
		l, err := net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return 0, fmt.Errorf("fwtest: free port: %w", err)
		}
		addr, ok := l.Addr().(*net.TCPAddr)
		_ = l.Close()
		if !ok {
			return 0, errors.New("fwtest: free port: not a TCP address")
		}
		if allowedPort(addr.Port) {
			return addr.Port, nil
		}
	}
	return 0, errors.New("fwtest: free port: only reserved ports were offered")
}

func allowedPort(p int) bool { return p != devDatabasePort && p != devHTTPPort }

// checkDatabaseURL refuses a server URL a test must not use: one on port 15432, the platform's
// development database, or one that does not parse.
func checkDatabaseURL(raw string) error {
	u, err := url.Parse(raw)
	if err != nil {
		return fmt.Errorf("fwtest: FW_TEST_DATABASE_URL does not parse: %w", err)
	}
	if u.Scheme != "postgres" && u.Scheme != "postgresql" {
		return fmt.Errorf("fwtest: FW_TEST_DATABASE_URL must be a postgres:// URL, got scheme %q", u.Scheme)
	}
	port := u.Port()
	if port == "" {
		port = "5432"
	}
	if n, err := strconv.Atoi(port); err != nil || n == devDatabasePort {
		return fmt.Errorf("fwtest: FW_TEST_DATABASE_URL points at port %s; tests never use the development database on %d", port, devDatabasePort)
	}
	return nil
}
