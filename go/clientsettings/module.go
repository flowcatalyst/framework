package clientsettings

import (
	"embed"
	"io/fs"

	"github.com/flowcatalyst/framework/go/fw"
)

// ModuleID is the module that contributes the fw_client_settings migration set (the Java module's id).
const ModuleID = "client-settings"

//go:embed migrations/*.sql
var migrationFiles embed.FS

// Migrations returns the module's migration set (the Java module's file, byte for byte).
func Migrations() fs.FS {
	sub, err := fs.Sub(migrationFiles, "migrations")
	if err != nil {
		panic(err) // embedded above
	}
	return sub
}

// Declared is the module's declaration: the proof that the migration set is part of the application.
type Declared struct{}

// Declare adds the module's migration set to the application (applied to the default database
// instance). Declare it after the database module, before Start.
func Declare(app *fw.App) *Declared {
	app.Module(ModuleID).Migrations(Migrations())
	return &Declared{}
}
