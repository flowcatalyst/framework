// Command fwsql is the fixture application's "fw sql": the few lines an application writes to run
// the framework's checked-SQL tooling with its own migration sets.
package main

import (
	"context"
	"os"

	"github.com/flowcatalyst/framework/go/sqlfw"
	"github.com/flowcatalyst/framework/go/sqlfw/testdata/app"
)

func main() {
	os.Exit(sqlfw.Main(context.Background(), os.Args[1:], sqlfw.Config{
		Sets: app.Sets(),
		Jet:  &sqlfw.JetConfig{Dir: "jet"},
	}))
}
