// Command tickets-trial is the Java framework's tickets trial service (examples/tickets-trial,
// docs/m4f-trial-service.md) on the Go framework: support tickets with a status machine and comments,
// per-client SLA settings, six use cases, nine typed operations, definitions synced from code, a
// dispatch receiver that notifies an assignee, a scheduled-job SLA sweep, a gauge loop and an admin
// section. It is the parity gate of the Go framework (docs/go/g76-tickets-friction.md).
//
//	tickets-trial          serves the application (the same as serve); needs AUTH_ISSUER,
//	                       AUTH_CLIENT_ID, AUTH_PUBLIC_BASE_URL and, for the platform, the
//	                       flowcatalyst.* settings (FLOWCATALYST_ENABLED=true)
//	tickets-trial sync     pushes the definitions to the platform (sync --dry-run to preview)
//	tickets-trial openapi  writes the OpenAPI document (--out web/openapi.json), with no
//	                       configuration or database; it is the Java service's document, byte for byte
//	tickets-trial e2e      the browser end-to-end launcher: <app-port> <control-port> [--dist dir]
//
// Configuration is config/application.yaml (run from this directory); the environment overrides it.
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"

	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/ticketsweb"
)

func main() {
	if len(os.Args) > 1 && os.Args[1] == "e2e" {
		ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
		code := ticketsweb.E2EMain(ctx, os.Args[2:])
		stop()
		os.Exit(code)
	}
	args := os.Args[1:]
	if len(args) > 0 && args[0] == "serve" {
		args = nil
	}
	app := ticketsweb.NewApp()
	os.Exit(app.Launch(context.Background(), args, ticketsweb.BuildWeb(ticketsweb.Declare(app), ticketsweb.Deps{})))
}
