// Command fwvet runs the framework's analyzers: checks Go's compiler cannot make, run at lint
// time (docs/go/design.md section 1.7). Use it with go vet:
//
//	go build -o bin/fwvet ./cmd/fwvet && go vet -vettool=$(pwd)/bin/fwvet ./...
//
// Analyzers:
//
//   - mustliteral: the argument of a framework Must... function (fw.MustClientID,
//     fw.MustPermission, ...) is a constant that its Parse... function accepts.
package main

import (
	"golang.org/x/tools/go/analysis/unitchecker"

	"github.com/flowcatalyst/framework/go/cmd/fwvet/mustliteral"
)

func main() {
	unitchecker.Main(mustliteral.Analyzer)
}
