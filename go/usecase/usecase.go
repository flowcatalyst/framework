package usecase

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
)

// UseCase is one business operation (write-path contract section 3): validate the command's
// shape, authorize the caller, then execute, which loads what it needs once and returns a plan.
// The interface has all four methods, so a use case without an authorization step does not
// compile. Register it with [Define].
type UseCase[C any, E event.Event] interface {
	// Name names the use case for logs, metrics and the audit row.
	Name() Name
	// Validate checks the command without loading data; nil when there is nothing to check.
	// Return a *Error (usually VALIDATION) to refuse.
	Validate(ctx context.Context, cx fw.Context, cmd C) error
	// Authorize decides whether the caller may run it: a Grant from Public or Require, or an
	// error (usually an AUTHORIZATION *Error).
	Authorize(ctx context.Context, cx fw.Context, cmd Valid[C]) (Grant, error)
	// Execute loads what it needs (once), applies the rules and returns the plan to write, or a
	// *Error. It never begins, commits or writes anything itself.
	Execute(ctx context.Context, cx fw.Context, cmd Authorized[C], db *database.DB) (*Plan[E], error)
}

// Valid is a command that passed Validate. Only the runner makes a non-zero one.
type Valid[C any] struct {
	cmd C
	ok  bool
}

// Command returns the command.
func (v Valid[C]) Command() C { return v.cmd }

// Authorized is a command that passed Authorize; Execute receives only this, so business logic
// never runs on an unauthorised command through the runner. Only the runner makes a non-zero one.
type Authorized[C any] struct {
	cmd C
	ok  bool
}

// Command returns the command.
func (a Authorized[C]) Command() C { return a.cmd }

// Grant is Authorize's permission to proceed. The zero Grant is a refusal.
type Grant struct{ ok bool }

// Public declares an operation deliberately open to every caller (Java's Authorize.PUBLIC):
// greppable, and a choice made in code.
func Public() Grant { return Grant{ok: true} }

// PermissionRequired is the code of the error Require returns.
var PermissionRequired = MustErrorCode("PERMISSION_REQUIRED")

// Require grants when the Context holds a permission matching p, and returns an AUTHORIZATION
// error otherwise.
func Require(cx fw.Context, p fw.Permission) (Grant, error) {
	if cx.HasPermission(p) {
		return Grant{ok: true}, nil
	}
	return Grant{}, Authorization(PermissionRequired, "The permission "+p.String()+" is required.")
}

// Committed is the result of a run that committed: the plan's first event. Only the runner
// makes a non-zero one.
type Committed[E any] struct {
	event E
	ok    bool
}

// Event returns the committed result event.
func (c Committed[E]) Event() E { return c.event }

// ConflictPolicy is what to do when an attempt finds the data changed underneath it (a stale
// aggregate, a serialization failure, a deadlock).
type ConflictPolicy struct {
	attempts int
	backoff  time.Duration
}

// Fail fails with CONFLICT / STALE_AGGREGATE at the first conflict (the default).
func Fail() ConflictPolicy { return ConflictPolicy{attempts: 1} }

// Retry runs the whole use case again, up to attempts in total (2 to 10), waiting backoff
// between attempts, each wait randomised between half and one and a half times it.
func Retry(attempts int, backoff time.Duration) (ConflictPolicy, error) {
	if attempts < 2 || attempts > 10 {
		return ConflictPolicy{}, fmt.Errorf("retry attempts must be 2 to 10: %d", attempts)
	}
	if backoff < 0 {
		return ConflictPolicy{}, fmt.Errorf("retry backoff must not be negative: %v", backoff)
	}
	return ConflictPolicy{attempts: attempts, backoff: backoff}, nil
}

// DefineOption configures [Define].
type DefineOption func(*definition)

type definition struct {
	policy ConflictPolicy
}

// WithConflictPolicy sets the use case's conflict policy.
func WithConflictPolicy(p ConflictPolicy) DefineOption {
	return func(d *definition) { d.policy = p }
}

// Defined is a use case registered with a runner; Run runs it.
type Defined[C any, E event.Event] struct {
	runner *Runner
	uc     phases[C, E]
	name   string
	policy ConflictPolicy
}

// phases is what the runner calls: the first two phases of every use case, and the rest (Execute,
// or Load, AuthorizeLoaded and Execute) as one step that records which phase it is in, so a failure
// carries that phase's code.
type phases[C any, E event.Event] interface {
	Name() Name
	Validate(ctx context.Context, cx fw.Context, cmd C) error
	Authorize(ctx context.Context, cx fw.Context, cmd Valid[C]) (Grant, error)
	// run executes after Authorize; it sets *step before each phase it calls.
	run(ctx context.Context, cx fw.Context, cmd Authorized[C], db *database.DB, step *string) (*Plan[E], error)
}

// plain adapts a [UseCase] to the runner's phases.
type plain[C any, E event.Event] struct{ UseCase[C, E] }

func (p plain[C, E]) run(ctx context.Context, cx fw.Context, cmd Authorized[C], db *database.DB, step *string) (*Plan[E], error) {
	*step = stepExecute
	return p.Execute(ctx, cx, cmd, db)
}

// Define registers a use case with the runner. It fails for a zero name or a zero policy.
func Define[C any, E event.Event](r *Runner, uc UseCase[C, E], opts ...DefineOption) (*Defined[C, E], error) {
	return define[C, E](r, plain[C, E]{uc}, opts)
}

func define[C any, E event.Event](r *Runner, uc phases[C, E], opts []DefineOption) (*Defined[C, E], error) {
	if r == nil {
		return nil, fmt.Errorf("usecase.Define: no runner")
	}
	name := uc.Name()
	if name.IsZero() {
		return nil, fmt.Errorf("usecase.Define: the use case has the zero Name")
	}
	d := definition{policy: Fail()}
	for _, o := range opts {
		o(&d)
	}
	if d.policy.attempts < 1 {
		return nil, fmt.Errorf("usecase.Define %s: the zero ConflictPolicy (use Fail or Retry)", name)
	}
	return &Defined[C, E]{runner: r, uc: uc, name: name.String(), policy: d.policy}, nil
}

// Name returns the use case's name.
func (d *Defined[C, E]) Name() string { return d.name }

// Run runs the use case (write-path contract section 4) and returns the committed result event,
// or an error that is always a *Error: a failure the use case reported, or INTERNAL with the
// step's code. It never panics for an expected outcome; a panic inside a phase is recovered as
// INTERNAL.
func (d *Defined[C, E]) Run(ctx context.Context, cx fw.Context, cmd C) (Committed[E], error) {
	return d.run(ctx, cx, cmd, nil)
}

// RunJoined runs the use case inside the caller's transaction instead of its own (write-path
// contract 4a, owner decision 3): the plan's writes, the events and the audit row go on joined's
// connection under a savepoint, and are committed or rolled back when the caller commits or rolls
// back. Committed then means "applied in your transaction". A stale aggregate, a serialization
// failure, a deadlock, a unique violation or any failed step rolls the attempt back to the savepoint,
// so the caller's earlier work survives and its transaction stays usable; the result and the
// conflict policy are those of [Defined.Run], and a retry runs the whole attempt again on the same
// transaction. The runner never begins, commits or rolls back the caller's transaction. The
// isolation level is the caller's: retrying a conflict assumes READ COMMITTED (what
// flowcatalyst.Once uses). Reads through a repository's Find use the database's own connections and
// do not see the caller's uncommitted writes.
//
// joined is the *database.Tx a framework helper hands its work (flowcatalyst.Once); only the
// framework makes one, so an arbitrary connection cannot be passed and, unlike Java's autocommit
// argument error, there is no such misuse to report. A nil joined is a programming error, returned
// as a plain error (not a *Error). (Go has no generic methods, so this is on the defined use case
// rather than on Runner.)
func (d *Defined[C, E]) RunJoined(ctx context.Context, cx fw.Context, cmd C, joined *database.Tx) (Committed[E], error) {
	if joined == nil {
		return Committed[E]{}, errors.New("usecase: RunJoined needs the transaction of a framework helper such as flowcatalyst.Once")
	}
	return d.run(ctx, cx, cmd, joined)
}

func (d *Defined[C, E]) run(ctx context.Context, cx fw.Context, cmd C, joined *database.Tx) (Committed[E], error) {
	start := d.runner.clock.Now()
	ev, err := run(ctx, d, cx, cmd, joined)
	outcome := "committed"
	if err != nil {
		outcome = lower(err.Kind().String())
	}
	d.runner.runs.With(d.name, outcome).Inc()
	d.runner.durations.With(d.name).Record(d.runner.clock.Now().Sub(start))
	if err != nil {
		return Committed[E]{}, err
	}
	return Committed[E]{event: ev, ok: true}, nil
}

func lower(s string) string {
	b := []byte(s)
	for i, c := range b {
		if c >= 'A' && c <= 'Z' {
			b[i] = c + ('a' - 'A')
		}
	}
	return string(b)
}
