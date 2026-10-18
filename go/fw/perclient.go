package fw

import (
	"context"
	"fmt"
	"log/slog"
)

// ClientResult is what happened for one client in [EachClient]: the task's value when Err is nil,
// else the error (or recovered panic) it ended with.
type ClientResult[R any] struct {
	Client ClientID
	Value  R
	Err    error
}

// Done reports whether the task returned without an error.
func (r ClientResult[R]) Done() bool { return r.Err == nil }

// Failed reports whether the task returned an error or panicked.
func (r ClientResult[R]) Failed() bool { return r.Err != nil }

// PanicError is the Err of a [ClientResult] whose task panicked.
type PanicError struct{ Value any }

// Error describes the panic.
func (e *PanicError) Error() string { return fmt.Sprintf("fw: client task panicked: %v", e.Value) }

// EachClient runs task for each client in order, with base.ForClient(client) (core contract
// section 3a; Java's PerClient.each). Duplicates run once. A task that returns an error (or
// panics) is logged to log (nothing is logged when log is nil) and recorded as Failed for that client, and the others still run. It stops
// starting clients when ctx is done (shutdown); clients not started are absent from the result,
// which is in the order the clients ran. It does no locking, retrying or parallelism: the
// caller's lock gate or overlap lock serialises runs. It panics on the zero base Context.
//
// A task cut short by ctx returns its error as usual and is recorded Failed, as Java records an
// InterruptedException; no further client starts.
func EachClient[R any](ctx context.Context, log *slog.Logger, base Context, clients []ClientID, task func(ctx context.Context, cx Context) (R, error)) []ClientResult[R] {
	if base.IsZero() {
		panic("fw: EachClient with the zero Context")
	}
	out := make([]ClientResult[R], 0, len(clients))
	seen := make(map[ClientID]struct{}, len(clients))
	for _, c := range clients {
		if ctx.Err() != nil {
			break
		}
		if _, dup := seen[c]; dup {
			continue
		}
		seen[c] = struct{}{}
		cx := base.ForClient(c) // panics on the zero ClientID: a programming error
		v, err := runClientTask(ctx, cx, task)
		if err != nil {
			var zero R
			v = zero
			if log != nil {
				log.LogAttrs(ctx, slog.LevelError, "work for client failed", append(cx.LogAttrs(), slog.Any("error", err))...)
			}
		}
		out = append(out, ClientResult[R]{Client: c, Value: v, Err: err})
	}
	return out
}

func runClientTask[R any](ctx context.Context, cx Context, task func(context.Context, Context) (R, error)) (v R, err error) {
	defer func() {
		if p := recover(); p != nil {
			var zero R
			v, err = zero, &PanicError{Value: p}
		}
	}()
	return task(ctx, cx)
}
