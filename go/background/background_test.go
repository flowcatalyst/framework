package background_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/metrics"
)

// bubbleClock reads time.Now, which inside a synctest bubble is the fake clock.
type bubbleClock struct{}

func (bubbleClock) Now() time.Time { return time.Now() }

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

type env struct {
	loops *background.Loops
	reg   *metrics.Registry
	log   *syncBuffer
}

func newEnv(t *testing.T) *env {
	t.Helper()
	log := &syncBuffer{}
	reg, err := metrics.NewRegistry()
	if err != nil {
		t.Fatal(err)
	}
	l, err := background.NewLoops(slog.New(slog.NewTextHandler(log, &slog.HandlerOptions{Level: slog.LevelDebug})), reg, bubbleClock{})
	if err != nil {
		t.Fatal(err)
	}
	return &env{loops: l, reg: reg, log: log}
}

func (e *env) runs(loop, outcome string) float64 {
	for _, f := range e.reg.Snapshot().Families {
		if f.Name == "fw_background_runs_total" {
			for _, s := range f.Samples {
				if s.LabelValues[0] == loop && s.LabelValues[1] == outcome {
					return s.Value
				}
			}
		}
	}
	return 0
}

func (e *env) duration(loop string) (count uint64, sum float64) {
	for _, f := range e.reg.Snapshot().Families {
		if f.Name == "fw_background_run_duration_seconds" {
			for _, s := range f.Samples {
				if s.LabelValues[0] == loop {
					return s.Count, s.Value
				}
			}
		}
	}
	return 0, 0
}

func (e *env) register(t *testing.T, name string, opts background.Options, f background.Func) {
	t.Helper()
	if err := e.loops.Register(name, opts, f); err != nil {
		t.Fatal(err)
	}
}

func every(d time.Duration) background.Options { return background.Options{Interval: d} }

// settle lets every goroutine in the bubble block, then advances fake time by d.
func settle(d time.Duration) {
	synctest.Wait()
	time.Sleep(d)
	synctest.Wait()
}

func mustErr(t *testing.T, err error, want string) {
	t.Helper()
	if err == nil || !strings.Contains(err.Error(), want) {
		t.Fatalf("error %v, want containing %q", err, want)
	}
}

func TestLoopsRegisteredBeforeStartBeginAtStartAndLaterOnesAtOnce(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var early, late atomic.Int32
		e.register(t, "early", every(time.Hour), func(context.Context, background.Tick) error { early.Add(1); return nil })
		settle(time.Minute)
		if early.Load() != 0 {
			t.Fatal("ran before Start")
		}
		e.loops.Start(t.Context())
		synctest.Wait()
		if early.Load() != 1 {
			t.Fatalf("early %d", early.Load())
		}
		e.register(t, "late", every(time.Hour), func(context.Context, background.Tick) error { late.Add(1); return nil })
		synctest.Wait()
		if late.Load() != 1 {
			t.Fatalf("late %d", late.Load())
		}
		e.loops.Stop(time.Second)
		if e.runs("early", "success") != 1 {
			t.Fatal("success not counted")
		}
	})
}

func TestFixedDelayAfterTheRunEndedAndNeverOverlapping(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var concurrent, maxConcurrent atomic.Int32
		var mu sync.Mutex
		var starts []time.Time
		var runNumbers []uint64
		e.register(t, "busy", every(10*time.Millisecond), func(_ context.Context, tick background.Tick) error {
			n := concurrent.Add(1)
			if n > maxConcurrent.Load() {
				maxConcurrent.Store(n)
			}
			mu.Lock()
			starts = append(starts, time.Now())
			runNumbers = append(runNumbers, tick.Run)
			mu.Unlock()
			if _, ok := tick.Fence(); ok || tick.Loop != "busy" {
				t.Error("tick", tick)
			}
			time.Sleep(20 * time.Millisecond)
			concurrent.Add(-1)
			return nil
		})
		e.loops.Start(t.Context())
		settle(125 * time.Millisecond)
		e.loops.Stop(time.Second)
		mu.Lock()
		defer mu.Unlock()
		if len(starts) != 5 {
			t.Fatalf("runs %d", len(starts))
		}
		for i := 1; i < len(starts); i++ {
			if d := starts[i].Sub(starts[i-1]); d != 30*time.Millisecond {
				t.Fatalf("gap %v, want run time 20ms + interval 10ms", d)
			}
			if runNumbers[i] != uint64(i+1) {
				t.Fatalf("run numbers %v", runNumbers)
			}
		}
		if maxConcurrent.Load() != 1 {
			t.Fatalf("overlap %d", maxConcurrent.Load())
		}
		if c, sum := e.duration("busy"); c != 5 || sum < 0.0999 || sum > 0.1001 {
			t.Fatalf("durations count %d sum %v", c, sum)
		}
	})
}

func TestTheFirstRunWaitsForTheInitialDelay(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		started := time.Now()
		var first atomic.Int64
		e.register(t, "late", background.Options{Interval: time.Hour, InitialDelay: 200 * time.Millisecond},
			func(context.Context, background.Tick) error {
				first.CompareAndSwap(0, int64(time.Since(started)))
				return nil
			})
		e.loops.Start(t.Context())
		settle(199 * time.Millisecond)
		if first.Load() != 0 {
			t.Fatal("ran early")
		}
		settle(time.Millisecond)
		if time.Duration(first.Load()) != 200*time.Millisecond {
			t.Fatalf("first run at %v", time.Duration(first.Load()))
		}
		e.loops.Stop(time.Second)
	})
}

func TestAFailedRunIsLoggedWithoutItsMessageHealthGoesDownAfterTheThresholdAndRecovers(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var fail atomic.Bool
		fail.Store(true)
		e.register(t, "flaky", every(10*time.Millisecond), func(context.Context, background.Tick) error {
			if fail.Load() {
				return errors.New("secret detail: password=hunter2")
			}
			return nil
		})
		check := e.loops.Check("flaky")
		e.loops.Start(t.Context())
		settle(0)
		// Run 1 failed at once; runs 2 and 3 follow at 10ms and 20ms.
		if err := check(t.Context()); err != nil {
			t.Fatalf("one failure must not be down: %v", err)
		}
		settle(10 * time.Millisecond)
		if err := check(t.Context()); err != nil {
			t.Fatalf("two failures must not be down: %v", err)
		}
		settle(10 * time.Millisecond)
		err := check(t.Context())
		if err == nil {
			t.Fatal("three failures in a row must be down")
		}
		if got := err.Error(); got != "3 runs in a row failed; last: *errors.errorString" {
			t.Fatalf("reason %q", got)
		}
		if e.runs("flaky", "failure") != 3 {
			t.Fatalf("failures %v", e.runs("flaky", "failure"))
		}
		out := e.log.String()
		if !strings.Contains(out, "level=ERROR") || !strings.Contains(out, "loop=flaky") || !strings.Contains(out, "run=3") {
			t.Fatalf("log: %s", out)
		}
		fail.Store(false)
		settle(10 * time.Millisecond)
		if err := check(t.Context()); err != nil {
			t.Fatalf("a success must recover: %v", err)
		}
		e.loops.Stop(time.Second)
	})
}

func TestDownAfterOneAndTheDefaultOfThree(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		fail := func(context.Context, background.Tick) error { return errors.New("x") }
		e.register(t, "one", background.Options{Interval: time.Hour, DownAfter: 1}, fail)
		e.register(t, "dflt", background.Options{Interval: 10 * time.Millisecond}, fail)
		e.loops.Start(t.Context())
		settle(0)
		if e.loops.Check("one")(t.Context()) == nil {
			t.Fatal("one failure with DownAfter 1 must be down")
		}
		if e.loops.Check("dflt")(t.Context()) != nil {
			t.Fatal("default must not be down after one")
		}
		e.loops.Stop(time.Second)
	})
}

func TestAPanicEndsTheLoopAndTheHealthCheckSaysSo(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var runs atomic.Int32
		e.register(t, "doomed", every(10*time.Millisecond), func(context.Context, background.Tick) error {
			runs.Add(1)
			panic("kaboom secret")
		})
		e.loops.Start(t.Context())
		settle(time.Second)
		if runs.Load() != 1 {
			t.Fatalf("runs %d", runs.Load())
		}
		err := e.loops.Check("doomed")(t.Context())
		if err == nil || err.Error() != "the loop ended after string" {
			t.Fatalf("reason %v", err)
		}
		if strings.Contains(err.Error(), "secret") {
			t.Fatal("panic value leaked into health")
		}
		if e.runs("doomed", "failure") != 1 {
			t.Fatal("panic not counted as failure")
		}
		if out := e.log.String(); !strings.Contains(out, "level=ERROR") || !strings.Contains(out, "loop=doomed") {
			t.Fatalf("log: %s", out)
		}
		e.loops.Stop(time.Second)
	})
}

func TestARunLastingLongerThanStuckAfterMakesTheCheckDown(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		release := make(chan struct{})
		e.register(t, "slow", background.Options{Interval: time.Hour, StuckAfter: 50 * time.Millisecond},
			func(context.Context, background.Tick) error { <-release; return nil })
		check := e.loops.Check("slow")
		e.loops.Start(t.Context())
		settle(50 * time.Millisecond)
		if err := check(t.Context()); err != nil {
			t.Fatalf("exactly at the limit is not stuck: %v", err)
		}
		settle(time.Millisecond)
		err := check(t.Context())
		if err == nil || err.Error() != "run 1 has been running for 0s" {
			t.Fatalf("reason %v", err)
		}
		close(release)
		synctest.Wait()
		if err := check(t.Context()); err != nil {
			t.Fatalf("after the run ended: %v", err)
		}
		e.loops.Stop(time.Second)
	})
}

func TestStopEndsWaitingLoopsAtOnceAndTasksSeeCancellation(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var sawCancel atomic.Bool
		var idleRuns atomic.Int32
		e.register(t, "idle", every(time.Hour), func(context.Context, background.Tick) error { idleRuns.Add(1); return nil })
		e.register(t, "polite", every(time.Hour), func(ctx context.Context, _ background.Tick) error {
			<-ctx.Done()
			sawCancel.Store(true)
			return ctx.Err()
		})
		e.loops.Start(t.Context())
		synctest.Wait()
		before := time.Now()
		e.loops.Stop(5 * time.Second)
		if d := time.Since(before); d != 0 {
			t.Fatalf("stop took %v of fake time", d)
		}
		if !sawCancel.Load() {
			t.Fatal("task did not see cancellation")
		}
		if idleRuns.Load() != 1 {
			t.Fatal("idle ran again")
		}
		if strings.Contains(e.log.String(), "level=ERROR") {
			t.Fatalf("a cancelled run while stopping must not be an ERROR: %s", e.log.String())
		}
	})
}

func TestStopWaitsAtMostTheDrainForATaskThatIgnoresCancellation(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		e.register(t, "stubborn", every(time.Hour), func(context.Context, background.Tick) error {
			time.Sleep(30 * time.Second)
			return nil
		})
		e.register(t, "stubborn2", every(time.Hour), func(context.Context, background.Tick) error {
			time.Sleep(30 * time.Second)
			return nil
		})
		e.loops.Start(t.Context())
		synctest.Wait()
		before := time.Now()
		e.loops.Stop(300 * time.Millisecond)
		if d := time.Since(before); d != 300*time.Millisecond {
			t.Fatalf("stop took %v, want exactly the drain (shared by both loops)", d)
		}
		out := e.log.String()
		if strings.Count(out, "level=WARN") != 2 || !strings.Contains(out, "loop=stubborn2") || !strings.Contains(out, "loop=stubborn ") {
			t.Fatalf("overrun warnings: %s", out)
		}
		settle(time.Minute) // the abandoned tasks finish; the bubble needs every goroutine gone
	})
}

func TestRegistrationRules(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		noop := func(context.Context, background.Tick) error { return nil }
		for _, bad := range []string{"", "Bad", "1x", "a_b", strings.Repeat("x", 101)} {
			mustErr(t, e.loops.Register(bad, every(time.Second), noop), "loop name must match [a-z][a-z0-9-]*, at most 100 characters")
		}
		if err := e.loops.Register(strings.Repeat("x", 100), every(time.Second), noop); err != nil {
			t.Fatal(err)
		}
		e.register(t, "one", every(time.Hour), noop)
		mustErr(t, e.loops.Register("one", every(time.Hour), noop), "loop one already exists")
		e.register(t, "a-b-2", every(time.Hour), noop)
		mustErr(t, e.loops.Register("nil-func", every(time.Hour), nil), "must not be nil")
		e.loops.Start(t.Context())
		e.loops.Stop(time.Second)
		mustErr(t, e.loops.Register("two", every(time.Hour), noop), "the application is closing; loop two not added")
	})
}

func TestOptionRanges(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		noop := func(context.Context, background.Tick) error { return nil }
		cases := map[string]background.Options{
			"loop interval must be 10ms to 24h":            {Interval: 9 * time.Millisecond},
			"loop interval must be 10ms to 24h ":           {Interval: 25 * time.Hour},
			"loop initial delay must be zero to 24h":       {Interval: time.Second, InitialDelay: -1},
			"loop initial delay must be zero to 24h ":      {Interval: time.Second, InitialDelay: 25 * time.Hour},
			"downAfterFailures must be 1 to 1000":          {Interval: time.Second, DownAfter: -1},
			"downAfterFailures must be 1 to 1000 ":         {Interval: time.Second, DownAfter: 1001},
			"stuckAfter must be positive and at most 24h":  {Interval: time.Second, StuckAfter: -1},
			"stuckAfter must be positive and at most 24h ": {Interval: time.Second, StuckAfter: 25 * time.Hour},
		}
		for want, o := range cases {
			mustErr(t, e.loops.Register("x", o, noop), strings.TrimSpace(want))
		}
		ok := background.Options{Interval: 10 * time.Millisecond, DownAfter: 1000, StuckAfter: 24 * time.Hour, InitialDelay: 24 * time.Hour}
		if err := e.loops.Register("ok", ok, noop); err != nil {
			t.Fatal(err)
		}
	})
}

type fakeGate struct {
	mu       sync.Mutex
	calls    int
	decide   func(n int, run func(context.Context, int64, bool) error) (background.Outcome, error)
	received []string
}

func (g *fakeGate) Enter(ctx context.Context, loop string, run func(context.Context, int64, bool) error) (background.Outcome, error) {
	g.mu.Lock()
	g.calls++
	n := g.calls
	g.received = append(g.received, loop)
	g.mu.Unlock()
	return g.decide(n, func(c context.Context, f int64, h bool) error { return run(ctx, f, h) })
}

func TestGateOutcomesSkippedIsNotAFailureLostIsAndTheFenceReachesTheTask(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var mu sync.Mutex
		type fence struct {
			v  int64
			ok bool
		}
		var fences []fence
		gate := &fakeGate{decide: func(n int, run func(context.Context, int64, bool) error) (background.Outcome, error) {
			switch n {
			case 1:
				return background.Ran{}, run(context.Background(), 42, true)
			case 2:
				return background.Skipped{Reason: "someone else"}, nil
			default:
				return background.Lost{}, run(context.Background(), 0, false)
			}
		}}
		e.register(t, "gated", background.Options{Interval: 10 * time.Millisecond, Gate: gate, DownAfter: 1000},
			func(_ context.Context, tick background.Tick) error {
				f, ok := tick.Fence()
				mu.Lock()
				fences = append(fences, fence{f, ok})
				mu.Unlock()
				time.Sleep(5 * time.Millisecond)
				return nil
			})
		e.loops.Start(t.Context())
		settle(20 * time.Millisecond) // run at 0 (ran), 15ms (skipped; not timed), 25ms (lost) not yet
		settle(10 * time.Millisecond)
		e.loops.Stop(time.Second)
		if e.runs("gated", "success") != 1 || e.runs("gated", "skipped") != 1 || e.runs("gated", "lost") != 1 {
			t.Fatalf("success %v skipped %v lost %v", e.runs("gated", "success"), e.runs("gated", "skipped"), e.runs("gated", "lost"))
		}
		if e.runs("gated", "failure") != 0 {
			t.Fatal("a skipped or lost run is not a 'failure' outcome")
		}
		mu.Lock()
		defer mu.Unlock()
		if len(fences) != 2 || fences[0] != (fence{42, true}) || fences[1].ok {
			t.Fatalf("fences %v", fences)
		}
		if c, _ := e.duration("gated"); c != 2 {
			t.Fatalf("timed runs %d, want 2 (skipped not timed)", c)
		}
		if gate.received[0] != "gated" {
			t.Fatal("gate got the wrong loop name")
		}
	})
}

func TestLostRunsCountTowardsHealthSkippedRunsDoNot(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		lost := &fakeGate{decide: func(_ int, run func(context.Context, int64, bool) error) (background.Outcome, error) {
			return background.Lost{}, run(context.Background(), 1, true)
		}}
		skipped := &fakeGate{decide: func(int, func(context.Context, int64, bool) error) (background.Outcome, error) {
			return background.Skipped{Reason: "held"}, nil
		}}
		task := func(context.Context, background.Tick) error { return nil }
		e.register(t, "losing", background.Options{Interval: 10 * time.Millisecond, Gate: lost, DownAfter: 2}, task)
		e.register(t, "skipping", background.Options{Interval: 10 * time.Millisecond, Gate: skipped, DownAfter: 1}, task)
		e.loops.Start(t.Context())
		settle(0)
		if e.loops.Check("losing")(t.Context()) != nil {
			t.Fatal("one lost run is not enough")
		}
		settle(10 * time.Millisecond)
		err := e.loops.Check("losing")(t.Context())
		if err == nil || err.Error() != "2 runs in a row failed; last: lost its gate while running" {
			t.Fatalf("reason %v", err)
		}
		if e.loops.Check("skipping")(t.Context()) != nil {
			t.Fatal("skipped runs must not make the check down")
		}
		if !strings.Contains(e.log.String(), "level=ERROR") {
			t.Fatal("a lost run is logged at ERROR")
		}
		e.loops.Stop(time.Second)
	})
}

func TestAGateErrorIsAFailedRun(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		gate := &fakeGate{decide: func(int, func(context.Context, int64, bool) error) (background.Outcome, error) {
			return nil, errors.New("lock store down")
		}}
		e.register(t, "g", background.Options{Interval: time.Hour, Gate: gate, DownAfter: 1},
			func(context.Context, background.Tick) error { t.Error("must not run"); return nil })
		e.loops.Start(t.Context())
		settle(0)
		err := e.loops.Check("g")(t.Context())
		if err == nil || err.Error() != "1 runs in a row failed; last: *errors.errorString" {
			t.Fatalf("reason %v", err)
		}
		if e.runs("g", "failure") != 1 {
			t.Fatal("not counted")
		}
		e.loops.Stop(time.Second)
	})
}

func TestATaskErrorThroughAGateIsAFailure(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		gate := &fakeGate{decide: func(_ int, run func(context.Context, int64, bool) error) (background.Outcome, error) {
			return background.Ran{}, run(context.Background(), 1, true)
		}}
		e.register(t, "g", background.Options{Interval: time.Hour, Gate: gate},
			func(context.Context, background.Tick) error { return errors.New("task failed") })
		e.loops.Start(t.Context())
		settle(0)
		if e.runs("g", "failure") != 1 || e.runs("g", "success") != 0 {
			t.Fatalf("failure %v success %v", e.runs("g", "failure"), e.runs("g", "success"))
		}
		e.loops.Stop(time.Second)
	})
}

func TestStopBeforeStartNeverRunsLoopsAndTheRootContextEndsLoops(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		var runs atomic.Int32
		e.register(t, "a", every(10*time.Millisecond), func(context.Context, background.Tick) error { runs.Add(1); return nil })
		e.loops.Stop(time.Second)
		e.loops.Start(t.Context())
		settle(time.Second)
		if runs.Load() != 0 {
			t.Fatal("ran after Stop")
		}

		e2 := newEnv(t)
		ctx, cancel := context.WithCancel(t.Context())
		e2.register(t, "b", every(10*time.Millisecond), func(context.Context, background.Tick) error { runs.Add(1); return nil })
		e2.loops.Start(ctx)
		settle(35 * time.Millisecond)
		cancel()
		synctest.Wait()
		n := runs.Load()
		settle(time.Second)
		if runs.Load() != n {
			t.Fatal("the loop outlived its context")
		}
		e2.loops.Stop(time.Second)
	})
}

func TestCheckOfAnUnknownLoop(t *testing.T) {
	e := newEnv(t)
	mustErr(t, e.loops.Check("nope")(context.Background()), "not registered")
}

func TestNewLoopsRejectsMissingParts(t *testing.T) {
	reg, _ := metrics.NewRegistry()
	if _, err := background.NewLoops(nil, nil, bubbleClock{}); err == nil {
		t.Fatal("nil registry")
	}
	if _, err := background.NewLoops(nil, reg, nil); err == nil {
		t.Fatal("nil clock")
	}
	if _, err := background.NewLoops(nil, reg, bubbleClock{}); err != nil {
		t.Fatal(err)
	}
}

func TestAGateReturningNothingIsAFailedRun(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		e := newEnv(t)
		gate := &fakeGate{decide: func(int, func(context.Context, int64, bool) error) (background.Outcome, error) {
			return nil, nil //nolint:nilnil // the bug under test
		}}
		e.register(t, "g", background.Options{Interval: time.Hour, Gate: gate},
			func(context.Context, background.Tick) error { return nil })
		e.loops.Start(t.Context())
		settle(0)
		if e.runs("g", "failure") != 1 || !strings.Contains(e.log.String(), "gate bug") {
			t.Fatalf("failure %v log %s", e.runs("g", "failure"), e.log.String())
		}
		e.loops.Stop(time.Second)
	})
}
