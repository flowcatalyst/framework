// Package background runs the application's loops (background-and-metrics contract section 1):
// named work that runs again and again on its own goroutine with a fixed delay between runs,
// owned by the application's lifecycle. Loops start after every started action and stop first at
// shutdown. Each has a health check (background:<name>, informational by default) and metrics.
//
// An error returned by a run is logged and the loop continues (Java's exception); a panic is
// recovered, logged, and ends the loop with its health check down (Java's Error).
package background

import (
	"context"
	"log/slog"
	"time"

	"github.com/flowcatalyst/framework/go/metrics"
)

// Clock gives the wall time; fw.Clock satisfies it.
type Clock interface {
	Now() time.Time
}

// Tick describes one run of a loop.
type Tick struct {
	// Loop is the loop's name.
	Loop string
	// Run counts runs from 1.
	Run      uint64
	fence    int64
	hasFence bool
}

// Fence returns the fencing token of the lock that admitted the run, when a lock gate did.
func (t Tick) Fence() (int64, bool) { return t.fence, t.hasFence }

// Func is a loop's task. ctx is cancelled when the application shuts down or a gate loses its
// lock; a long task checks it and returns.
type Func func(ctx context.Context, tick Tick) error

// Options configure a loop.
type Options struct {
	// InitialDelay is the wait before the first run (default zero).
	InitialDelay time.Duration
	// Interval is the wait after a run ends before the next starts: 10ms to 24h.
	Interval time.Duration
	// Gate decides before each run whether it may run (nil: always).
	Gate Gate
	// Critical makes the background:<name> health check critical (default informational).
	Critical bool
	// DownAfter is how many consecutive failed runs make the health check down: 1 to 1000;
	// zero means 3.
	DownAfter int
	// StuckAfter, when not zero, makes the health check down while a run lasts longer.
	StuckAfter time.Duration
}

// Outcome is what a gate decided about one run.
//
//sumtype:decl
type Outcome interface{ outcome() }

// Ran says the task ran (with exclusion held throughout, for a lock gate).
type Ran struct{}

// Skipped says the task did not run this time, for example because another replica holds the
// lock.
type Skipped struct{ Reason string }

// Lost says the task ran but lost exclusion while running; it counts as a failed run.
type Lost struct{}

func (Ran) outcome()     {}
func (Skipped) outcome() {}
func (Lost) outcome()    {}

// Gate decides whether a run may happen. locks.Gate is the framework's.
type Gate interface {
	// Enter runs run when the run is admitted, passing a fencing token when there is one
	// (hasFence false otherwise), and reports the outcome. An error from run is returned as
	// the error, with the outcome of the gate.
	//
	// Any non-nil error is a failed run (counted as "failure"), whatever the outcome, and the
	// outcome may then be nil: the gate itself failed, for example because the lock store is
	// down and the task never ran. A nil outcome with a nil error is a gate bug and also a
	// failed run.
	Enter(ctx context.Context, loop string, run func(ctx context.Context, fence int64, hasFence bool) error) (Outcome, error)
}

// Loops owns the application's loops.
type Loops struct{ impl *loops }

// NewLoops returns the loops of one application: they log through logger, record
// fw_background_runs_total and fw_background_run_duration_seconds in reg and read clock for
// stuck detection.
func NewLoops(logger *slog.Logger, reg *metrics.Registry, clock Clock) (*Loops, error) {
	l, err := newLoops(logger, reg, clock)
	if err != nil {
		return nil, err
	}
	return &Loops{impl: l}, nil
}

// Register adds a loop. Names match [a-z][a-z0-9-]*, at most 100 characters, unique. A loop
// registered before Start begins at Start; one registered after Start begins at once; after Stop
// began, Register fails.
func (l *Loops) Register(name string, opts Options, run Func) error {
	return l.impl.register(name, opts, run)
}

// Start begins the loops registered so far. ctx is the parent of every loop's context: when it
// ends, the loops end.
func (l *Loops) Start(ctx context.Context) { l.impl.start(ctx) }

// Stop cancels every loop at once and waits for each running task up to drain; a task still
// running after that is logged at WARN as an overrun and abandoned.
func (l *Loops) Stop(drain time.Duration) { l.impl.stop(drain) }

// Check returns the health check of a registered loop: nil while it is up, an error naming the
// count of failed runs and the error's type (never its message) when it is down.
func (l *Loops) Check(name string) func(ctx context.Context) error { return l.impl.check(name) }
