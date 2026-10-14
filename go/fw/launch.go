package fw

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"slices"
	"sync"
	"syscall"
	"time"
)

// Launch runs the application as its main function does, and returns the process exit code:
//
//  1. configure: read config files, the environment and secrets.local.yaml, check every declared
//     setting and instance section; every problem is reported at once (exit 1);
//  2. build: call build, which constructs the services and registers lifecycle work;
//  3. when the first argument is a command claimed during build, run it, shut down and return
//     its exit code (no started actions, loops or serving);
//  4. otherwise start: the started actions in order, then the loops, then serve;
//  5. run until SIGTERM or SIGINT, or until serve returns;
//  6. shut down: not ready, loops stopped first, serve drained, then the shutdown actions in
//     reverse order, each bounded by app.shutdown.drain.
func (a *App) Launch(ctx context.Context, args []string, build BuildFunc) int {
	ctx, stop := signal.NotifyContext(ctx, syscall.SIGTERM, os.Interrupt)
	defer stop()
	b, serve, err := a.configureAndBuild(ctx, build)
	if err != nil {
		a.report(err)
		return 1
	}
	if len(args) > 0 {
		if cmd, ok := b.command(args[0]); ok {
			code := runCommand(ctx, cmd, args[1:], a)
			r := &Running{boot: b}
			r.runShutdownActions()
			return code
		}
	}
	r, err := a.start(ctx, b, serve)
	if err != nil {
		a.report(err)
		return 1
	}
	select {
	case <-ctx.Done():
	case <-r.Done():
	}
	if err := r.Shutdown(); err != nil {
		a.opts.logger.Error("application failed", slog.String("error", err.Error()))
		return 1
	}
	return 0
}

// Start configures, builds and starts the application (Launch's steps 1, 2 and 4) and returns
// it running; serve runs on its own goroutine. Tests use it with [Running.Shutdown]. A failure
// shuts down what was built and returns the error (a *StartupError for configuration problems).
func (a *App) Start(ctx context.Context, build BuildFunc) (*Running, error) {
	b, serve, err := a.configureAndBuild(ctx, build)
	if err != nil {
		return nil, err
	}
	return a.start(ctx, b, serve)
}

func (a *App) configureAndBuild(ctx context.Context, build BuildFunc) (*Boot, Serve, error) {
	b, err := a.newBoot()
	if err != nil {
		return nil, nil, err
	}
	serve, err := callBuild(ctx, build, b)
	if err == nil {
		if problems := b.takeProblems(); len(problems) > 0 {
			err = &StartupError{Problems: problems}
		}
	}
	if err != nil {
		(&Running{boot: b}).runShutdownActions()
		return nil, nil, err
	}
	return b, serve, nil
}

func callBuild(ctx context.Context, build BuildFunc, b *Boot) (s Serve, err error) {
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("build panicked: %v", p)
		}
	}()
	return build(ctx, b)
}

func runCommand(ctx context.Context, cmd CommandFunc, args []string, a *App) (code int) {
	defer func() {
		if p := recover(); p != nil {
			a.opts.logger.Error("command panicked", slog.Any("panic", p))
			code = 1
		}
	}()
	return cmd(ctx, args, a.opts.stdout)
}

func (a *App) report(err error) {
	if se, ok := errors.AsType[*StartupError](err); ok {
		_, _ = fmt.Fprintln(a.opts.stderr, se.Error())
		return
	}
	_, _ = fmt.Fprintln(a.opts.stderr, "Application failed to start: "+err.Error())
}

// start runs the started actions, starts the loops and serve.
func (a *App) start(ctx context.Context, b *Boot, serve Serve) (*Running, error) {
	r := &Running{boot: b, done: make(chan struct{})}
	for _, act := range b.started {
		if err := callAction(ctx, act); err != nil {
			r.runShutdownActions()
			return nil, fmt.Errorf("started action %s: %w", act.name, err)
		}
	}
	for _, n := range b.health.unmatched() {
		a.opts.logger.Warn("app.health.critical / app.health.informational name a check that does not exist",
			slog.String("check", n))
	}
	b.loops.Start(context.WithoutCancel(ctx))
	b.ready.Store(true)
	serveCtx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	r.cancelServe = cancel
	go func() {
		defer close(r.done)
		if serve == nil {
			<-serveCtx.Done()
			return
		}
		r.serveErr = callServe(serveCtx, serve)
	}()
	return r, nil
}

func callServe(ctx context.Context, s Serve) (err error) {
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("serve panicked: %v", p)
		}
	}()
	return s.Serve(ctx)
}

func callAction(ctx context.Context, act namedAction) (err error) {
	defer func() {
		if p := recover(); p != nil {
			err = fmt.Errorf("panicked: %v", p)
		}
	}()
	return act.run(ctx)
}

// Running is a started application.
type Running struct {
	boot        *Boot
	done        chan struct{}
	cancelServe context.CancelFunc
	serveErr    error
	once        sync.Once
	shutdownErr error
}

// Boot returns the application's Boot (for tests that read its metrics, clock or ids).
func (r *Running) Boot() *Boot { return r.boot }

// Done is closed when serve has returned.
func (r *Running) Done() <-chan struct{} { return r.done }

// Ready reports whether the application is ready: started and not shutting down. The HTTP
// layer answers /health/ready from it and the critical checks.
func (r *Running) Ready() bool { return r.boot.Ready() }

// Health runs every health check.
func (r *Running) Health(ctx context.Context) HealthReport { return r.boot.HealthReport(ctx) }

// Shutdown stops the application once (later calls return the first result): not ready from
// now on; loops stopped first, each given up to app.shutdown.drain; serve cancelled and waited
// for up to app.shutdown.drain; then the shutdown actions in reverse registration order, each
// bounded by app.shutdown.drain, a failure logged and the rest still run. It returns serve's
// error, if any.
func (r *Running) Shutdown() error {
	r.once.Do(func() {
		r.boot.ready.Store(false)
		drain := r.boot.app.core.drain.Get(r.boot)
		log := r.boot.app.opts.logger
		r.boot.loops.Stop(drain)
		if r.cancelServe != nil {
			r.cancelServe()
			timer := time.NewTimer(drain)
			select {
			case <-r.done:
			case <-timer.C:
				log.Warn("serve did not return within app.shutdown.drain; abandoned", slog.Duration("drain", drain))
			}
			timer.Stop()
			r.shutdownErr = r.serveErr
		}
		r.runShutdownActions()
	})
	return r.shutdownErr
}

func (r *Running) runShutdownActions() {
	b := r.boot
	drain := b.app.core.drain.Get(b)
	log := b.app.opts.logger
	b.mu.Lock()
	actions := slices.Clone(b.shutdown)
	b.shutdown = nil
	b.mu.Unlock()
	slices.Reverse(actions)
	for _, act := range actions {
		ctx, cancel := context.WithTimeout(context.Background(), drain) //nolint:forbidigo // shutdown runs after the root context is done
		done := make(chan error, 1)
		go func() { done <- callAction(ctx, act) }()
		select {
		case err := <-done:
			if err != nil {
				log.Error("shutdown action failed", slog.String("action", act.name), slog.String("error", err.Error()))
			}
		case <-ctx.Done():
			log.Warn("shutdown action overran app.shutdown.drain; continuing", slog.String("action", act.name),
				slog.Duration("drain", drain))
		}
		cancel()
	}
}
