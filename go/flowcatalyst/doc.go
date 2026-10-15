// Package flowcatalyst connects an application built on the Go framework to the FlowCatalyst
// platform (flowcatalyst contract): the platform client as the application's service account, the
// definitions the application declares and the sync that pushes them, the flowcatalyst and outbox
// health checks, and the two receivers, for dispatch jobs and for scheduled-job firings, that run
// handlers as the application's service principal.
//
//	d := flowcatalyst.Declare(app)                           // in main: settings, migration, the sync command
//	d.Definitions("orders").EventType("orders:sales:order:placed", "Order placed", "")
//
//	fc, err := flowcatalyst.Build(ctx, b, d, flowcatalyst.WithDatabases(dbs), flowcatalyst.WithLocks(all))
//	flowcatalyst.OnDispatch(fc.Receivers(), "orders:billing:invoice:paid", handler)
//	routes, err := fc.Routes()                               // hand to httpfw.NewServer
//
// It is its own module, github.com/flowcatalyst/framework/go/flowcatalyst, because it is the only
// part of the Go framework that imports the platform SDK (fcsdk): an application that never uses
// FlowCatalyst does not get fcsdk in its module graph. Types of fcsdk never appear in its
// signatures.
//
// Every setting of the module is configuration (flowcatalyst.*, config-and-code contract section
// 3.5); the module claims the flowcatalyst component, so a misspelt key fails start-up listing the
// real ones.
package flowcatalyst
