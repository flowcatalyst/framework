package mustliteral

import (
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/usecase"
)

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
	"github.com/flowcatalyst/framework/go/event.MustType":         errOf(event.ParseType),
	"github.com/flowcatalyst/framework/go/usecase.MustName":       errOf(usecase.ParseName),
	"github.com/flowcatalyst/framework/go/usecase.MustErrorCode":  errOf(usecase.ParseErrorCode),
	"github.com/flowcatalyst/framework/go/locks.MustServiceKey":   errOf(locks.ServiceKey),
}

func errOf[T any](parse func(string) (T, error)) func(string) error {
	return func(s string) error {
		_, err := parse(s)
		return err
	}
}
