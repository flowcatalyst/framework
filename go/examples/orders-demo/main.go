// Command orders-demo is the Java framework's orders-demo on the Go framework: the same table,
// use cases, events and outbox rows.
//
//	orders-demo            places an order, adds two lines, cancels it, tries to add a line after
//	                       the cancel, and prints the summaries (the Java OrdersDemo)
//	orders-demo serve      serves the orders API behind sessions and login (the database session
//	                       store; needs AUTH_ISSUER, AUTH_CLIENT_ID and AUTH_PUBLIC_BASE_URL), the
//	                       embedded single-page application and the admin area without the sections
//	                       that read the platform (the Java OrdersWeb); the command of the
//	                       flowcatalyst module, go/flowcatalyst/examples/orders-demo, has them too
//	orders-demo e2e        the browser end-to-end launcher (ordersweb/e2e.go): <app-port> <control-port> [--dist dir]
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

	"github.com/flowcatalyst/framework/go/examples/orders-demo/ordersweb"
)

func main() {
	app := ordersweb.NewApp()
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "serve":
			os.Exit(app.Launch(context.Background(), nil, ordersweb.BuildWeb(ordersweb.Declare(app, nil), ordersweb.Deps{})))
		case "openapi":
			os.Exit(app.Launch(context.Background(), os.Args[1:], ordersweb.BuildWeb(ordersweb.Declare(app, nil), ordersweb.Deps{})))
		case "e2e":
			ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
			code := ordersweb.E2EMain(ctx, os.Args[2:], nil)
			stop()
			os.Exit(code)
		}
	}
	os.Exit(app.Launch(context.Background(), os.Args[1:], ordersweb.BuildScenario(ordersweb.DeclareScenario(app), ordersweb.Deps{Out: os.Stdout})))
}
