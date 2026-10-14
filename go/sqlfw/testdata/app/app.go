// Package app is the fixture application of package sqlfw's tests: one migration set, sqlc
// queries, the generated sqlc code (db) and the generated jet code (jet).
package app

import (
	"embed"
	"io/fs"

	"github.com/flowcatalyst/framework/go/fw"
)

//go:embed migrations/*.sql
var migrations embed.FS

// Sets returns the application's migration sets, in apply order.
func Sets() []fw.MigrationSet {
	files, err := fs.Sub(migrations, "migrations")
	if err != nil {
		panic(err)
	}
	return []fw.MigrationSet{{Module: "fixture", Files: files}}
}
