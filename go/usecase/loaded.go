package usecase

import (
	"context"
	"errors"
	"fmt"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
)

// LoadedUseCase is a use case that works on one stored record (write-path contract section 3a):
// after Authorize (which sees the command and the caller only) the runner calls Load, then the
// record-level check if the use case has one, and Execute receives the record that was checked.
// Order inside one attempt: Validate, Authorize, Load, AuthorizeLoaded, Execute. Register it with
// [DefineLoaded]. Like [UseCase] it has Authorize, so one without it does not compile.
//
// A use case may also implement [RecordAuthorizer], the record-level check, which is optional.
type LoadedUseCase[C any, E event.Event, L any] interface {
	// Name names the use case for logs, metrics and the audit row.
	Name() Name
	// Validate checks the command without loading data; nil when there is nothing to check.
	Validate(ctx context.Context, cx fw.Context, cmd C) error
	// Authorize is the permission-level check, as for [UseCase]; a caller refused here causes no read.
	Authorize(ctx context.Context, cx fw.Context, cmd Valid[C]) (Grant, error)
	// Load finds the record once per attempt, filtered by the Context's client (another client's
	// record is missing). found=false ends the run with [LoadedUseCase.WhenMissing]'s error
	// (conventionally NOT_FOUND); the record check and Execute do not run. An error is INTERNAL / LOAD.
	Load(ctx context.Context, cx fw.Context, cmd Authorized[C], db *database.DB) (record L, found bool, err error)
	// WhenMissing is the error of a missing record.
	WhenMissing() *Error
	// Execute receives the record that was loaded and checked, applies the rules and returns the plan.
	Execute(ctx context.Context, cx fw.Context, cmd Authorized[C], record L, db *database.DB) (*Plan[E], error)
}

// RecordAuthorizer is the optional record-level access check of a [LoadedUseCase]
// (authorizeLoaded). Return a *Error to refuse (conventionally AUTHORIZATION); a rule that must not
// reveal that the record exists returns the use case's WhenMissing error instead. Any other error
// is INTERNAL / AUTHORIZE, the cause logged and never shown.
type RecordAuthorizer[C any, L any] interface {
	AuthorizeLoaded(ctx context.Context, cx fw.Context, cmd Authorized[C], record L) error
}

// loadedPhases adapts a [LoadedUseCase] to the runner's phases.
type loadedPhases[C any, E event.Event, L any] struct {
	LoadedUseCase[C, E, L]
}

func (p loadedPhases[C, E, L]) run(ctx context.Context, cx fw.Context, cmd Authorized[C], db *database.DB, step *string) (*Plan[E], error) {
	*step = stepLoad
	record, found, err := p.Load(ctx, cx, cmd, db)
	if err != nil {
		return nil, err
	}
	if !found {
		missing := p.WhenMissing()
		if missing == nil {
			return nil, errors.New("WhenMissing returned no error")
		}
		return nil, missing
	}
	if ra, ok := any(p.LoadedUseCase).(RecordAuthorizer[C, L]); ok {
		*step = stepAuthorize
		if err := ra.AuthorizeLoaded(ctx, cx, cmd, record); err != nil {
			return nil, err
		}
	}
	*step = stepExecute
	return p.Execute(ctx, cx, cmd, record, db)
}

// DefineLoaded registers a [LoadedUseCase] with the runner; the returned [Defined] runs it like any
// other. It fails for a zero name, a zero policy, or a use case whose WhenMissing is nil.
func DefineLoaded[C any, E event.Event, L any](r *Runner, uc LoadedUseCase[C, E, L], opts ...DefineOption) (*Defined[C, E], error) {
	if uc.WhenMissing() == nil {
		return nil, fmt.Errorf("usecase.DefineLoaded %s: WhenMissing returns no error", uc.Name())
	}
	return define[C, E](r, loadedPhases[C, E, L]{uc}, opts)
}
