// Command orders-demo is the Java framework's orders-demo on the Go framework: the same table,
// use cases, events and outbox rows.
//
//	orders-demo            places an order, adds two lines, cancels it, tries to add a line after
//	                       the cancel, and prints the summaries (the Java OrdersDemo)
//	orders-demo serve      serves the orders API behind sessions and login (the database session
//	                       store; needs AUTH_ISSUER, AUTH_CLIENT_ID and AUTH_PUBLIC_BASE_URL) and the
//	                       embedded single-page application (the Java OrdersWeb)
//	orders-demo e2e        the browser end-to-end launcher (e2e.go): <app-port> <control-port> [--dist dir]
//	orders-demo openapi    writes the OpenAPI document (--out web/openapi.json), with no
//	                       configuration or database
//
// Configuration is config/application.yaml; DATABASE_INSTANCES_MAIN_URL and HTTP_PORT override.
package main

import (
	"context"
	"os"
	"os/signal"
	"syscall"
)

func main() {
	app := newApp()
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "serve":
			os.Exit(app.Launch(context.Background(), nil, buildWeb(declare(app), deps{})))
		case "openapi":
			os.Exit(app.Launch(context.Background(), os.Args[1:], buildWeb(declare(app), deps{})))
		case "e2e":
			ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
			code := e2eMain(ctx, os.Args[2:])
			stop()
			os.Exit(code)
		}
	}
	os.Exit(app.Launch(context.Background(), os.Args[1:], build(declareScenario(app), deps{out: os.Stdout})))
}
