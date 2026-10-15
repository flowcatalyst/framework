// Command receivers-demo is an application that takes deliveries from the FlowCatalyst platform:
// the Go counterpart of what the Java receivers guide shows. It subscribes to the orders demo's
// order-placed events, records each delivery once (flowcatalyst.Once) in its own table, and runs a
// scheduled job that reports how many it has recorded.
//
//	receivers-demo serve   serves the receivers (POST /fc/dispatch and /fc/scheduled-jobs)
//	receivers-demo sync    pushes the definitions to the platform (sync --dry-run to preview)
//
// Configuration is config/application.yaml; the environment overrides it
// (DATABASE_INSTANCES_MAIN_URL, FLOWCATALYST_*, HTTP_PORT).
package main

import (
	"context"
	"embed"
	"io/fs"
	"os"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
)

//go:embed migrations/*.sql
var migrationFiles embed.FS

func main() {
	app := fw.New("receivers-demo")
	d := declare(app)
	os.Exit(app.Launch(context.Background(), os.Args[1:], build(d)))
}

type declared struct {
	db    *database.Declared
	locks *locks.Declared
	fc    *flowcatalyst.Declared
	web   *httpfw.Declared
}

func declare(app *fw.App) declared {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	d := declared{db: database.Declare(app), locks: locks.Declare(app)}
	dblock.Declare(app)
	app.Module("receivers-demo").Migrations(sub)
	d.fc = flowcatalyst.Declare(app)
	d.web = httpfw.Declare(app)
	// what this application tells the platform it has: the order-placed event it receives is the
	// orders demo's, and a nightly report job it runs itself
	defs := d.fc.Definitions("receivers-demo")
	defs.EventTypeOf(orders.Placed, "Order placed", "A customer placed an order.").
		Connection("main", "The demo's own endpoint").
		Subscription(flowcatalyst.NewSubscription("on-order-placed", "Record placed orders", "/fc/dispatch", orders.Placed.String()).ViaConnection("main")).
		ScheduledJob(flowcatalyst.NewScheduledJob("orders-report", "Report recorded orders", "0 0 2 * * *").Tracking(true).
			Targeting("http://localhost:8081/fc/scheduled-jobs")) // a job target is an absolute URL: this application's own receiver
	return d
}

func build(d declared) fw.BuildFunc {
	return func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		dbs, err := database.Connect(ctx, b, d.db)
		if err != nil {
			return nil, err
		}
		if err := database.Migrate(ctx, b, dbs); err != nil {
			return nil, err
		}
		db, ok := dbs.Default()
		if !ok {
			return nil, errNoDatabase
		}
		all, err := locks.Build(ctx, b, d.locks, locks.WithStore("main", dblock.Store(dbs)))
		if err != nil {
			return nil, err
		}
		fc, err := flowcatalyst.Build(ctx, b, d.fc, flowcatalyst.WithDatabases(dbs), flowcatalyst.WithLocks(all))
		if err != nil {
			return nil, err
		}
		if err := register(fc, db); err != nil {
			return nil, err
		}
		routes, err := fc.Routes()
		if err != nil {
			return nil, err
		}
		return httpfw.NewServer(b, d.web, []*httpfw.Routes{routes})
	}
}
