// Package orders is the orders example's application code on the Go framework: the Java
// orders-demo's table, use cases (place-order, add-line, cancel-order), events and so the same
// outbox rows; a repository on sqlc's generated queries; and the read model's list query written
// both ways, with go-jet and with database.Filter (docs/go/orders-showcase.md).
package orders

import (
	"embed"
	"io/fs"

	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// ModuleID is the orders module's id, the module of its migration set (as in Java).
const ModuleID = "orders"

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the orders migration set (the Java demo's V0001__create_orders.sql, byte for
// byte).
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	return sub
}

// Declare declares the orders module and its migration set.
func Declare(app *fw.App) { app.Module(ModuleID).Migrations(Migrations()) }

// The event types (the Java demo's OrderEvents).
var (
	Placed    = event.MustType("orders:sales:order:placed")
	LineAdded = event.MustType("orders:sales:order:line-added")
	Cancelled = event.MustType("orders:sales:order:cancelled")
)

// The error codes of the use cases.
var (
	OrderNotFound     = usecase.MustErrorCode("ORDER_NOT_FOUND")
	OrderNotOpen      = usecase.MustErrorCode("ORDER_NOT_OPEN")
	PrincipalRequired = usecase.MustErrorCode("PRINCIPAL_REQUIRED")
)
