// Command orders-demo is the orders demo with its whole admin area: the command of
// go/examples/orders-demo plus the sections that read the FlowCatalyst platform (event types, roles,
// users and invitations, events and dispatch jobs), which need this module because only it imports
// the platform client. The Java counterpart is OrdersWeb with the platform modules listed.
//
//	orders-demo serve      serves the application and its admin area; needs AUTH_ISSUER,
//	                       AUTH_CLIENT_ID, AUTH_PUBLIC_BASE_URL and the flowcatalyst.* settings
//	orders-demo e2e        the browser end-to-end launcher: <app-port> <control-port> [--dist dir];
//	                       stands up a fake platform of its own and the kit's control endpoints
//	orders-demo openapi    writes the OpenAPI document (--out file), with no configuration or database;
//	                       it is the shared vector conformance/openapi/orders-demo-admin.json
//
// Configuration is config/application.yaml of go/examples/orders-demo (run from that directory).
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
			os.Exit(app.Launch(context.Background(), nil, ordersweb.BuildWeb(ordersweb.Declare(app, &extension{}), ordersweb.Deps{})))
		case "openapi":
			os.Exit(app.Launch(context.Background(), os.Args[1:], ordersweb.BuildWeb(ordersweb.Declare(app, &extension{}), ordersweb.Deps{})))
		case "e2e":
			ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
			code := ordersweb.E2EMain(ctx, os.Args[2:], newE2EExtension())
			stop()
			os.Exit(code)
		}
	}
	os.Stderr.WriteString("usage: orders-demo serve | e2e <app-port> <control-port> | openapi [--out file]\n") //nolint:errcheck,gosec // nothing to do about a failed usage line
	os.Exit(2)
}
