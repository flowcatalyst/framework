package fw_test

import (
	"context"
	"errors"
	"io"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// graceServe takes a while to return after it is cancelled and says how long it needs.
type graceServe struct {
	grace  time.Duration
	finish time.Duration
}

func (g graceServe) Serve(ctx context.Context) error {
	<-ctx.Done()
	time.Sleep(g.finish)
	return errors.New("served and drained")
}

func (g graceServe) ShutdownGrace() time.Duration { return g.grace }

type plainServe struct{ finish time.Duration }

func (p plainServe) Serve(ctx context.Context) error {
	<-ctx.Done()
	time.Sleep(p.finish)
	return errors.New("served and drained")
}

func TestShutdownWaitsForAServeThatNamesItsOwnGrace(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_SHUTDOWN_DRAIN": "50ms"})
	r, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) {
		return graceServe{grace: 400 * time.Millisecond, finish: 200 * time.Millisecond}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	began := time.Now()
	if err := r.Shutdown(); err == nil || err.Error() != "served and drained" {
		t.Fatalf("Shutdown should wait for the Serve's own grace and return its error, got %v", err)
	}
	if took := time.Since(began); took < 150*time.Millisecond {
		t.Errorf("returned after %v, before the serve finished", took)
	}
}

func TestShutdownAbandonsAServeThatOverrunsAppShutdownDrain(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"APP_SHUTDOWN_DRAIN": "50ms"})
	r, err := app.Start(t.Context(), func(context.Context, *fw.Boot) (fw.Serve, error) {
		return plainServe{finish: 400 * time.Millisecond}, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	began := time.Now()
	if err := r.Shutdown(); err != nil {
		t.Fatalf("an abandoned serve has no result yet: %v", err)
	}
	if took := time.Since(began); took > 300*time.Millisecond {
		t.Errorf("waited %v for a serve that overran the drain", took)
	}
}

func TestADeclarationCommandRunsBeforeConfigurationAndBuild(t *testing.T) {
	t.Parallel()
	app := newApp(t, map[string]string{"SOME_BROKEN_KEY": "x"})
	ran := false
	app.Module("tools").DeclarationCommand("dump", func(_ context.Context, args []string, _ io.Writer) int {
		ran = len(args) == 1 && args[0] == "--flag"
		return 7
	})
	code := app.Launch(t.Context(), []string{"dump", "--flag"}, func(context.Context, *fw.Boot) (fw.Serve, error) {
		t.Error("build must not run")
		return nil, nil
	})
	if code != 7 || !ran {
		t.Fatalf("exit %d, ran %v", code, ran)
	}
}
