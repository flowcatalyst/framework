// Package loadedwithauth is the control of loadednoauth: the same use case with its Authorize method.
package loadedwithauth

import (
	"context"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

type ev struct{ Meta event.Metadata }

func (e ev) Metadata() event.Metadata { return e.Meta }

type open struct{}

func (open) Name() usecase.Name                                 { return usecase.MustName("open") }
func (open) Validate(context.Context, fw.Context, string) error { return nil }
func (open) Authorize(context.Context, fw.Context, usecase.Valid[string]) (usecase.Grant, error) {
	return usecase.Public(), nil
}

func (open) Load(context.Context, fw.Context, usecase.Authorized[string], *database.DB) (int, bool, error) {
	return 0, true, nil
}
func (open) WhenMissing() *usecase.Error { return nil }
func (open) Execute(context.Context, fw.Context, usecase.Authorized[string], int, *database.DB) (*usecase.Plan[ev], error) {
	return nil, nil
}

func define(r *usecase.Runner) {
	_, _ = usecase.DefineLoaded[string, ev, int](r, open{})
}
