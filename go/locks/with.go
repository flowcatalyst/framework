package locks

import (
	"context"
	"errors"
	"log/slog"
	"sync"
	"time"
)

// Outcome is how [With] ended.
type Outcome int

const (
	// Completed means the action returned and the lock was held throughout.
	Completed Outcome = iota + 1
	// NotAcquired means the lock could not be had within the wait; the action did not run.
	NotAcquired
	// Lost means the lock was lost while the action ran; treat the work as having run without
	// exclusion.
	Lost
)

func (o Outcome) String() string {
	switch o {
	case Completed:
		return "completed"
	case NotAcquired:
		return "not acquired"
	case Lost:
		return "lost"
	}
	return "unknown"
}

// WithResult is the result of [With]: the outcome and, for Completed and Lost, the action's
// value (Lost carries it when the action still returned without an error).
type WithResult[T any] struct {
	Outcome Outcome
	Value   T
}

// With runs action while holding the key (locks contract section 4): it acquires with
// Acquire(k, lease, maxWait); runs the action with a context that is cancelled when the lease is
// lost; refreshes the lease every lease/3 meanwhile; and releases it afterwards unless it was
// lost (a release failure is logged and does not change the result).
//
// The lease is lost when a refresh finds it gone, or when refreshes keep failing until its local
// deadline has passed. An action that ignores its context keeps running, as a Java thread that
// ignores interrupts does; the result is then Lost.
//
// An error from the action is returned beside the outcome, unwrapped; except that the action's
// context error caused by the loss is not an error: the result is Lost.
func With[T any](ctx context.Context, l *Lock, k Key, lease, maxWait time.Duration,
	action func(ctx context.Context, lease Lease) (T, error),
) (WithResult[T], error) {
	acq, err := l.Acquire(ctx, k, lease, maxWait)
	if err != nil {
		return WithResult[T]{}, err
	}
	a, ok := acq.(Acquired)
	if !ok {
		return WithResult[T]{Outcome: NotAcquired}, nil
	}
	return runHeld(ctx, l, a.Lease, lease, action)
}

type held struct {
	mu      sync.Mutex
	current Lease
	lost    bool
	done    bool
	cancel  context.CancelFunc
}

func (h *held) signalLoss() {
	h.mu.Lock()
	defer h.mu.Unlock()
	if !h.done && !h.lost {
		h.lost = true
		h.cancel()
	}
}

func (h *held) endAction() bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.done = true
	return h.lost
}

func runHeld[T any](ctx context.Context, l *Lock, first Lease, leaseLength time.Duration,
	action func(ctx context.Context, lease Lease) (T, error),
) (WithResult[T], error) {
	actionCtx, cancel := context.WithCancel(ctx)
	defer cancel()
	h := &held{current: first, cancel: cancel}
	stopRefresh := make(chan struct{})
	refresherDone := make(chan struct{})
	go func() {
		defer close(refresherDone)
		l.refreshLoop(context.WithoutCancel(ctx), h, leaseLength, stopRefresh)
	}()

	value, panicked, actionErr := invoke(actionCtx, first, action)
	lost := h.endAction()
	close(stopRefresh)
	<-refresherDone

	if !lost {
		h.mu.Lock()
		current := h.current
		h.mu.Unlock()
		if _, err := l.Release(context.WithoutCancel(ctx), current); err != nil {
			l.log.Warn("could not release lock", slog.String("key", first.key.Stored()), slog.String("error", err.Error()))
		}
	}
	if panicked != nil {
		panic(panicked)
	}
	switch {
	case lost && actionErr != nil && errors.Is(actionErr, context.Canceled) && ctx.Err() == nil:
		return WithResult[T]{Outcome: Lost}, nil
	case lost:
		return WithResult[T]{Outcome: Lost, Value: value}, actionErr
	default:
		return WithResult[T]{Outcome: Completed, Value: value}, actionErr
	}
}

// invoke runs the action, turning a panic into a value so the lock is still released (or its
// loss recorded) before the panic continues.
func invoke[T any](ctx context.Context, lease Lease, action func(ctx context.Context, lease Lease) (T, error)) (v T, panicked any, err error) {
	defer func() {
		if p := recover(); p != nil {
			panicked = p
		}
	}()
	v, err = action(ctx, lease)
	return v, nil, err
}

func (l *Lock) refreshLoop(ctx context.Context, h *held, leaseLength time.Duration, stop <-chan struct{}) {
	period := max(time.Millisecond, leaseLength/3)
	retry := max(10*time.Millisecond, min(period, 250*time.Millisecond))
	wait := period
	for {
		timer := time.NewTimer(wait)
		select {
		case <-stop:
			timer.Stop()
			return
		case <-timer.C:
		}
		h.mu.Lock()
		current := h.current
		h.mu.Unlock()
		next, ok, err := l.Refresh(ctx, current, leaseLength)
		switch {
		case err != nil:
			if !l.clock.Now().Before(current.deadline) {
				l.log.Warn("lock lost: refresh kept failing until the lease deadline",
					slog.String("key", current.key.Stored()), slog.String("error", err.Error()))
				h.signalLoss()
				return
			}
			l.log.Warn("refreshing lock failed; will retry until its deadline",
				slog.String("key", current.key.Stored()), slog.String("error", err.Error()))
			wait = retry
		case !ok:
			h.signalLoss()
			return
		default:
			h.mu.Lock()
			h.current = next
			h.mu.Unlock()
			wait = period
		}
	}
}
