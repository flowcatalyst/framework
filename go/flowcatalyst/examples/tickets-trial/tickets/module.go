// Package tickets is the tickets service's application code on the Go framework (the Java
// examples/tickets-trial, docs/m4f-trial-service.md): support tickets with a status machine and
// comments, a per-client SLA in the framework's client settings, six use cases, nine typed
// operations, the definitions the platform needs, a dispatch receiver that notifies an assignee, a
// scheduled-job receiver that sweeps for tickets past their SLA, a gauge loop, and an admin section.
package tickets

import (
	"embed"
	"io/fs"

	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the tickets module's id, the module of its migration set (as in Java).
const ModuleID = "tickets"

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the tickets migration set (the Java service's two files, byte for byte).
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	return sub
}

// Declare declares the tickets module and its migration set.
func Declare(app *fw.App) { app.Module(ModuleID).Migrations(Migrations()) }
