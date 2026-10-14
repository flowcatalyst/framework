// Command orders-demo is the Java framework's orders-demo on the Go framework: the same table,
// use cases, events and outbox rows. It places an order, adds two lines, cancels it, tries to add
// a line after the cancel, and prints the summaries.
//
//	go run ./examples/orders-demo     (config/application.yaml; DATABASE_INSTANCES_MAIN_URL overrides)
package main

import (
	"context"
	"os"
)

func main() {
	app := newApp()
	d := declare(app)
	os.Exit(app.Launch(context.Background(), os.Args[1:], build(d, deps{out: os.Stdout})))
}
