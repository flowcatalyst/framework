// Command orders-demo is the Java framework's orders-demo on the Go framework: the same table,
// use cases, events and outbox rows.
//
//	orders-demo            places an order, adds two lines, cancels it, tries to add a line after
//	                       the cancel, and prints the summaries (the Java OrdersDemo)
//	orders-demo serve      serves the orders API, its OpenAPI-described session endpoints and the
//	                       embedded single-page application (the Java OrdersWeb); needs
//	                       ORDERS_DEMO_TOKEN
//	orders-demo openapi    writes the OpenAPI document (--out web/openapi.json), with no
//	                       configuration or database
//
// Configuration is config/application.yaml; DATABASE_INSTANCES_MAIN_URL and HTTP_PORT override.
package main

import (
	"context"
	"os"
)

func main() {
	app := newApp()
	d := declare(app)
	if len(os.Args) > 1 && os.Args[1] == "serve" {
		os.Exit(app.Launch(context.Background(), nil, buildWeb(d, deps{})))
	}
	os.Exit(app.Launch(context.Background(), os.Args[1:], build(d, deps{out: os.Stdout})))
}
