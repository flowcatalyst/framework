package mustliteral

import "github.com/flowcatalyst/framework/go/fw"

// checks maps each framework Must... function to the Parse... function it wraps. A Must...
// function missing here is reported where it is called, so the table cannot fall behind.
var checks = map[string]func(string) error{
	"github.com/flowcatalyst/framework/go/fw.MustClientID":        errOf(fw.ParseClientID),
	"github.com/flowcatalyst/framework/go/fw.MustPrincipalID":     errOf(fw.ParsePrincipalID),
	"github.com/flowcatalyst/framework/go/fw.MustApplicationCode": errOf(fw.ParseApplicationCode),
	"github.com/flowcatalyst/framework/go/fw.MustCorrelationID":   errOf(fw.ParseCorrelationID),
	"github.com/flowcatalyst/framework/go/fw.MustCausationID":     errOf(fw.ParseCausationID),
	"github.com/flowcatalyst/framework/go/fw.MustExecutionID":     errOf(fw.ParseExecutionID),
	"github.com/flowcatalyst/framework/go/fw.MustPermission":      errOf(fw.ParsePermission),
	"github.com/flowcatalyst/framework/go/fw.MustIDPrefix":        errOf(fw.ParseIDPrefix),
}

func errOf[T any](parse func(string) (T, error)) func(string) error {
	return func(s string) error {
		_, err := parse(s)
		return err
	}
}
