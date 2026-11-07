// Command fwsql is the orders example's "fw sql": the framework's checked-SQL tooling with the
// application's migration sets, the framework's outbox set first, then orders (their apply order).
// The application's set is read from its directory by path (sqlfw.MigrationDir), so this command
// compiles before the queries generated from it exist.
//
//	go run ./tools/fwsql generate   # schema dir, sqlc code, jet table code
//	go run ./tools/fwsql check      # fails when any of them is stale or a query does not prepare
package main

import (
	"context"
	"os"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/sqlfw"
	"github.com/flowcatalyst/framework/go/usecase"
)

func main() {
	os.Exit(sqlfw.Main(context.Background(), os.Args[1:], sqlfw.Config{
		Sets: []fw.MigrationSet{
			{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()},
			sqlfw.MigrationDir("orders", "orders/migrations"), // by path: the command needs no application code
		},
		Jet: &sqlfw.JetConfig{Dir: "internal/jet"},
	}))
}
