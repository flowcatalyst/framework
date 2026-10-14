package background

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"runtime/debug"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/metrics"
)

const (
	nameMax          = 100
	minInterval      = 10 * time.Millisecond
	maxDuration      = 24 * time.Hour
	defaultDownAfter = 3
	maxDownAfter     = 1000
)

type loops struct {
	logger    *slog.Logger
	clock     Clock
	runs      *metrics.Counter
	durations *metrics.Timer

	mu      sync.Mutex
	byName  map[string]*loop
	started bool
	closing bool
	root    context.Context
	cancel  context.CancelFunc
}

func newLoops(logger *slog.Logger, reg *metrics.Registry, clock Clock) (*loops, error) {
	if reg == nil {
		return nil, errors.New("background: a metrics registry is required")
	}
	if clock == nil {
		return nil, errors.New("background: a clock is required")
	}
	if logger == nil {
		logger = slog.New(slog.DiscardHandler)
	}
	runs, err := reg.Counter("fw_background_runs_total",
		"Runs of background loops by outcome: success, failure, skipped, lost.", "loop", "outcome")
	if err != nil {
		return nil, err
	}
	durations, err := reg.Timer("fw_background_run_duration_seconds",
		"Duration of background loop runs (runs a gate skipped are not timed).", "loop")
	if err != nil {
		return nil, err
	}
	return &loops{logger: logger, clock: clock, runs: runs, durations: durations, byName: map[string]*loop{}}, nil
}

func validLoopName(name string) bool {
	if name == "" || len(name) > nameMax || name[0] < 'a' || name[0] > 'z' {
		return false
	}
	for i := 1; i < len(name); i++ {
		c := name[i]
		if (c < 'a' || c > 'z') && (c < '0' || c > '9') && c != '-' {
			return false
		}
	}
	return true
}

func checkOptions(o *Options) error {
	if o.Interval < minInterval || o.Interval > maxDuration {
		return errors.New("loop interval must be 10ms to 24h")
	}
	if o.InitialDelay < 0 || o.InitialDelay > maxDuration {
		return errors.New("loop initial delay must be zero to 24h")
	}
	if o.DownAfter == 0 {
		o.DownAfter = defaultDownAfter
	}
	if o.DownAfter < 1 || o.DownAfter > maxDownAfter {
		return errors.New("downAfterFailures must be 1 to 1000")
	}
	if o.StuckAfter != 0 && (o.StuckAfter < 0 || o.StuckAfter > maxDuration) {
		return errors.New("stuckAfter must be positive and at most 24h")
	}
	return nil
}

func (l *loops) register(name string, opts Options, run Func) error {
	if !validLoopName(name) {
		return fmt.Errorf("loop name must match [a-z][a-z0-9-]*, at most %d characters: %s", nameMax, name)
	}
	if err := checkOptions(&opts); err != nil {
		return err
	}
	if run == nil {
		return fmt.Errorf("loop %s: the task must not be nil", name)
	}
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.closing {
		return fmt.Errorf("the application is closing; loop %s not added", name)
	}
	if _, dup := l.byName[name]; dup {
		return fmt.Errorf("loop %s already exists", name)
	}
	lp := &loop{
		owner: l, name: name, opts: opts, task: run, done: make(chan struct{}),
		success: l.runs.With(name, "success"), failure: l.runs.With(name, "failure"),
		skipped: l.runs.With(name, "skipped"), lost: l.runs.With(name, "lost"),
		duration: l.durations.With(name),
	}
	l.byName[name] = lp
	if l.started {
		lp.launch(l.root)
	}
	return nil
}

func (l *loops) start(ctx context.Context) {
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.started || l.closing {
		return
	}
	l.started = true
	l.root, l.cancel = context.WithCancel(ctx)
	for _, lp := range l.byName {
		lp.launch(l.root)
	}
}

func (l *loops) stop(drain time.Duration) {
	l.mu.Lock()
	l.closing = true
	cancel := l.cancel
	var launched []*loop
	for _, lp := range l.byName {
		if lp.launched {
			launched = append(launched, lp)
		}
	}
	l.mu.Unlock()
	if cancel != nil {
		cancel()
	}
	timer := time.NewTimer(drain)
	defer timer.Stop()
	expired := false
	for _, lp := range launched {
		if !expired {
			select {
			case <-lp.done:
				continue
			case <-timer.C:
				expired = true
			}
		}
		select {
		case <-lp.done:
		default:
			l.logger.Warn("loop did not stop within the drain period; abandoning it",
				slog.String("loop", lp.name), slog.Duration("drain", drain))
		}
	}
}

func (l *loops) check(name string) func(ctx context.Context) error {
	return func(context.Context) error {
		l.mu.Lock()
		lp := l.byName[name]
		l.mu.Unlock()
		if lp == nil {
			return fmt.Errorf("loop %s is not registered", name)
		}
		return lp.check()
	}
}

type loop struct {
	owner *loops
	name  string
	opts  Options
	task  Func
	done  chan struct{}

	success, failure, skipped, lost *metrics.CounterSeries
	duration                        *metrics.TimerSeries

	launched bool // guarded by owner.mu

	mu                  sync.Mutex
	running             bool
	runStart            time.Time
	runNumber           uint64
	consecutiveFailures int
	lastFailure         string
	endedBy             string
}

func (lp *loop) launch(ctx context.Context) {
	lp.launched = true
	go lp.body(ctx)
}

func (lp *loop) body(ctx context.Context) {
	defer close(lp.done)
	if !pause(ctx, lp.opts.InitialDelay) {
		return
	}
	for ctx.Err() == nil {
		if !lp.runOnce(ctx) {
			return
		}
		if !pause(ctx, lp.opts.Interval) {
			return
		}
	}
}

// pause waits d, ending early when ctx ends. It reports whether the loop should go on.
func pause(ctx context.Context, d time.Duration) bool {
	if d > 0 {
		t := time.NewTimer(d)
		defer t.Stop()
		select {
		case <-ctx.Done():
		case <-t.C:
		}
	}
	return ctx.Err() == nil
}

// runOnce makes one run. It reports false when the loop must end (the task panicked).
func (lp *loop) runOnce(ctx context.Context) (cont bool) {
	log := lp.owner.logger
	clock := lp.owner.clock
	start := clock.Now()
	lp.mu.Lock()
	lp.runNumber++
	number := lp.runNumber
	lp.running = true
	lp.runStart = start
	lp.mu.Unlock()
	timed := true
	defer func() {
		if p := recover(); p != nil {
			lp.mu.Lock()
			lp.endedBy = fmt.Sprintf("%T", p)
			ended := lp.endedBy
			lp.mu.Unlock()
			lp.failure.Inc()
			log.Error("loop run panicked; the loop has ended", slog.String("loop", lp.name),
				slog.Uint64("run", number), slog.String("panic", ended), slog.Any("value", p),
				slog.String("stack", string(debug.Stack())))
			cont = false
		}
		lp.mu.Lock()
		lp.running = false
		lp.mu.Unlock()
		if timed {
			lp.duration.Record(clock.Now().Sub(start))
		}
	}()

	var err error
	var outcome Outcome = Ran{}
	if gate := lp.opts.Gate; gate == nil {
		err = lp.task(ctx, Tick{Loop: lp.name, Run: number})
	} else {
		outcome, err = gate.Enter(ctx, lp.name, func(c context.Context, fence int64, hasFence bool) error {
			return lp.task(c, Tick{Loop: lp.name, Run: number, fence: fence, hasFence: hasFence})
		})
	}
	if err != nil {
		lp.fail(fmt.Sprintf("%T", err))
		lp.failure.Inc()
		if ctx.Err() != nil && errors.Is(err, context.Canceled) {
			log.Debug("loop run cancelled while stopping", slog.String("loop", lp.name), slog.Uint64("run", number))
		} else {
			log.Error("loop run failed", slog.String("loop", lp.name), slog.Uint64("run", number), slog.Any("error", err))
		}
		return true
	}
	switch o := outcome.(type) {
	case nil:
		lp.fail("*background.nilOutcome")
		lp.failure.Inc()
		log.Error("loop gate returned neither an outcome nor an error (a gate bug)", slog.String("loop", lp.name), slog.Uint64("run", number))
	case Ran:
		lp.mu.Lock()
		lp.consecutiveFailures = 0
		lp.mu.Unlock()
		lp.success.Inc()
	case Skipped:
		timed = false
		lp.skipped.Inc()
		log.Debug("loop run skipped", slog.String("loop", lp.name), slog.Uint64("run", number), slog.String("reason", o.Reason))
	case Lost:
		lp.fail("lost its gate while running")
		lp.lost.Inc()
		log.Error("loop lost its lock while running; it ran without exclusion", slog.String("loop", lp.name), slog.Uint64("run", number))
	}
	return true
}

func (lp *loop) fail(what string) {
	lp.mu.Lock()
	lp.consecutiveFailures++
	lp.lastFailure = what
	lp.mu.Unlock()
}

// check is the health check: an error when the loop is not healthy, naming at most a type.
func (lp *loop) check() error {
	lp.mu.Lock()
	defer lp.mu.Unlock()
	if lp.endedBy != "" {
		return fmt.Errorf("the loop ended after %s", lp.endedBy)
	}
	if lp.consecutiveFailures >= lp.opts.DownAfter {
		return fmt.Errorf("%d runs in a row failed; last: %s", lp.consecutiveFailures, lp.lastFailure)
	}
	if stuck := lp.opts.StuckAfter; stuck > 0 && lp.running {
		if took := lp.owner.clock.Now().Sub(lp.runStart); took > stuck {
			return fmt.Errorf("run %d has been running for %ds", lp.runNumber, int64(took/time.Second))
		}
	}
	return nil
}
