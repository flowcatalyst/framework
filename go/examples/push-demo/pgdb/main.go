// Command pgdb starts an embedded PostgreSQL 18 on a free port for the push load test, creates the
// database "pushdemo" and prints its URL (the first line of stdout, and the file named by the
// first argument when given). It runs until SIGTERM or SIGINT, then stops the server and removes
// its directory. It never uses port 15432 or 8080.
package main

import (
	"context"
	"fmt"
	"net/url"
	"os"
	"os/signal"
	"syscall"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

func main() {
	os.Exit(run())
}

func run() int {
	ctx, stopSignals := signal.NotifyContext(context.Background(), syscall.SIGTERM, os.Interrupt)
	defer stopSignals()
	raw, stop, err := pgembed.Start(ctx)
	if err != nil {
		fmt.Fprintln(os.Stderr, "pgdb:", err)
		return 1
	}
	defer stop()
	conn, err := pgx.Connect(ctx, raw)
	if err != nil {
		fmt.Fprintln(os.Stderr, "pgdb:", err)
		return 1
	}
	_, err = conn.Exec(ctx, "CREATE DATABASE pushdemo")
	_ = conn.Close(ctx)
	if err != nil {
		fmt.Fprintln(os.Stderr, "pgdb:", err)
		return 1
	}
	u, err := url.Parse(raw)
	if err != nil {
		fmt.Fprintln(os.Stderr, "pgdb:", err)
		return 1
	}
	u.Path = "/pushdemo"
	q := u.Query()
	q.Set("sslmode", "disable")
	q.Set("pool_max_conns", "24")
	u.RawQuery = q.Encode()
	fmt.Println(u.String())
	if len(os.Args) > 1 {
		//nolint:gosec // the operator's own output path
		if err := os.WriteFile(os.Args[1], []byte(u.String()+"\n"), 0o600); err != nil {
			fmt.Fprintln(os.Stderr, "pgdb:", err)
			return 1
		}
	}
	<-ctx.Done()
	return 0
}
