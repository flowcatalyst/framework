// Command fwsql is the fixture application's "fw sql": the few lines an application writes to run
// the framework's checked-SQL tooling with its own migration sets. The set is read from its
// directory by path, so this command compiles before the application's generated queries exist.
package main

import (
	"context"
	"os"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/sqlfw"
)

func main() {
	os.Exit(sqlfw.Main(context.Background(), os.Args[1:], sqlfw.Config{
		Sets: []fw.MigrationSet{sqlfw.MigrationDir("fixture", "migrations")},
		Jet:  &sqlfw.JetConfig{Dir: "jet"},
	}))
}
