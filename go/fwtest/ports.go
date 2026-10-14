package fwtest

import (
	"fmt"

	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

// FreePort returns a TCP port on 127.0.0.1 that was free a moment ago, never 15432 or 8080.
func FreePort() (int, error) {
	p, err := pgembed.FreePort()
	if err != nil {
		return 0, fmt.Errorf("fwtest: %w", err)
	}
	return p, nil
}

func allowedPort(p int) bool { return pgembed.AllowedPort(p) }

// checkDatabaseURL refuses a server URL a test must not use: one on port 15432, the platform's
// development database, or one that does not parse.
func checkDatabaseURL(raw string) error {
	if err := pgembed.CheckURL(EnvDatabaseURL, raw); err != nil {
		return fmt.Errorf("fwtest: %w", err)
	}
	return nil
}
