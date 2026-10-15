package flowcatalyst_test

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
)

const jobCode = "nightly-export"

type jobFn = func(ctx context.Context, cx fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error)

func firingJSON(overrides map[string]string) string {
	fields := map[string]string{
		"jobId": `"sj_1"`, "jobCode": `"` + jobCode + `"`, "instanceId": `"sji_0HZXEQ5Y8JY5Z"`, "firedAt": `"2026-10-09T12:00:00Z"`,
		"triggerKind": `"CRON"`, "correlationId": `"corr-2"`, "tracksCompletion": `true`, "concurrent": `false`,
	}
	for k, v := range overrides {
		if v == "" {
			delete(fields, k)
		} else {
			fields[k] = v
		}
	}
	var parts []string
	for _, k := range []string{"jobId", "jobCode", "instanceId", "scheduledFor", "firedAt", "triggerKind", "correlationId", "payload", "tracksCompletion", "timeoutSeconds", "concurrent"} {
		if v, ok := fields[k]; ok {
			parts = append(parts, `"`+k+`":`+v)
		}
	}
	return "{" + strings.Join(parts, ",") + "}"
}

// jobRig starts a rig whose only scheduled-job handler is h, with a memory lock store.
func jobRig(t *testing.T, h jobFn, opts flowcatalyst.JobOptions, o rigOptions) *rig {
	t.Helper()
	if !opts.AlwaysConcurrent && o.lockFactory == nil {
		o.locks = true
	}
	o.register = func(fc *flowcatalyst.Platform) error { return fc.Receivers().OnScheduledJob(jobCode, opts, h) }
	return startRig(t, o)
}

func (r *rig) fire(body string) reply {
	r.t.Helper()
	return r.post("/fc/scheduled-jobs", body, signedHeaders(body, nil))
}

func okJob(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
	return flowcatalyst.Success{}, nil
}

func (r *rig) completions(t *testing.T, n int) []recorded {
	t.Helper()
	waitFor(t, fmt.Sprintf("%d completion call(s)", n), func() bool { return len(r.platform.calls("complete")) >= n })
	return r.platform.calls("complete")
}

func TestA202AtOnceAndTheHandlerRunsInTheBackground(t *testing.T) {
	release := make(chan struct{})
	started := make(chan struct{})
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		close(started)
		<-release
		return flowcatalyst.Success{Value: map[string]any{"rows": 3}}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	got := r.fire(firingJSON(nil))
	if got.status != 202 || got.body != `{"ok":true}` {
		t.Fatalf("%d %s", got.status, got.body)
	}
	<-started
	if n := len(r.platform.calls("complete")); n != 0 {
		t.Fatalf("completed while the handler still runs: %d", n)
	}
	close(release)
	c := r.completions(t, 1)[0]
	body := c.json(t)
	if body["status"] != "SUCCESS" || fmt.Sprint(body["result"]) != "map[rows:3]" {
		t.Fatalf("completion %v", body)
	}
	if c.path != "/api/scheduled-jobs/instances/sji_0HZXEQ5Y8JY5Z/complete" {
		t.Fatalf("path %s", c.path)
	}
}

func TestTheContextAndTheFiringCarryTheEnvelope(t *testing.T) {
	type seenT struct {
		cx fw.Context
		f  flowcatalyst.Firing
	}
	seen := &recorderOf[seenT]{}
	r := jobRig(t, func(_ context.Context, cx fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		tok, ok := f.FencingToken()
		if !ok || tok < 1 {
			return nil, fmt.Errorf("fencing token %d %v", tok, ok)
		}
		seen.add(seenT{cx, *f})
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	body := firingJSON(map[string]string{"scheduledFor": `"2026-10-09T11:59:00+00:00"`, "payload": `{"full":true}`, "timeoutSeconds": `30`, "triggerKind": `"MANUAL"`, "tracksCompletion": `false`})
	if got := r.fire(body); got.status != 202 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	waitFor(t, "the handler", func() bool { return len(seen.get()) > 0 })
	s := seen.get()[0]
	f := s.f
	if f.JobID != "sj_1" || f.JobCode != jobCode || f.InstanceID != "sji_0HZXEQ5Y8JY5Z" || f.Trigger != "MANUAL" || f.CorrelationID.String() != "corr-2" ||
		string(f.Payload) != `{"full":true}` || f.Timeout != 30*time.Second || f.TracksCompletion || f.Concurrent {
		t.Fatalf("firing %+v", f)
	}
	if !f.FiredAt.Equal(time.Date(2026, 10, 9, 12, 0, 0, 0, time.UTC)) || !f.ScheduledFor.Equal(time.Date(2026, 10, 9, 11, 59, 0, 0, time.UTC)) {
		t.Fatalf("times %v %v", f.FiredAt, f.ScheduledFor)
	}
	cx := s.cx
	if cx.Client() != flowcatalyst.PlatformClientID {
		t.Fatalf("client %v", cx.Client())
	}
	if c, _ := cx.Causation(); c.String() != "sji_0HZXEQ5Y8JY5Z" { // the instance, not the job definition
		t.Fatalf("causation %v", c)
	}
	if e, _ := cx.Execution(); e.String() != "sji_0HZXEQ5Y8JY5Z" {
		t.Fatalf("execution %v", e)
	}
	if k, _ := cx.PrincipalKind(); k != fw.PrincipalService {
		t.Fatalf("kind %v", k)
	}
	if n := len(r.platform.calls("complete")); n != 0 {
		t.Fatalf("an untracked job was completed: %d", n)
	}
}

func TestWithoutTheServicePrincipalTheFiringIs503AndNothingRunsOrIsReported(t *testing.T) {
	p := newFakePlatform(t)
	p.setToken("prn_0HUMAN", "USER")
	var ran atomic.Bool
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		ran.Store(true)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{platform: p})
	got := r.fire(firingJSON(nil))
	if got.status != 503 || got.body != `{"error":"UNAVAILABLE","message":"the application's service principal is not available"}` {
		t.Fatalf("%d %s", got.status, got.body)
	}
	time.Sleep(50 * time.Millisecond)
	if ran.Load() || len(p.calls("complete")) != 0 {
		t.Fatal("the handler ran or the instance was reported")
	}
}

func TestAPlatformThatIssuesNoTokenGets503AndTheRedeliveryRuns(t *testing.T) {
	p := newFakePlatform(t)
	seen := &recorderOf[string]{}
	r := jobRig(t, func(_ context.Context, _ fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		seen.add(f.InstanceID)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{platform: p})
	p.failTokens(500)
	r.fc.Client().ClearToken()
	if got := r.fire(firingJSON(nil)); got.status != 503 {
		t.Fatalf("%d", got.status)
	}
	p.failTokens(0)
	if got := r.fire(firingJSON(nil)); got.status != 202 {
		t.Fatalf("redelivery: %d", got.status)
	}
	waitFor(t, "the redelivered run", func() bool { return len(seen.get()) == 1 })
}

func TestAnUnknownJobIs404EvenWithoutThePrincipal(t *testing.T) {
	p := newFakePlatform(t)
	r := jobRig(t, okJob, flowcatalyst.JobOptions{}, rigOptions{platform: p})
	p.failTokens(500)
	r.fc.Client().ClearToken()
	p.clear()
	got := r.fire(firingJSON(map[string]string{"jobCode": `"other-job"`}))
	if got.status != 404 || !strings.Contains(got.body, `"NOT_FOUND"`) || !strings.Contains(got.body, "no handler for scheduled job other-job") {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if p.tokenRequests() != 0 {
		t.Fatal("an unknown job contacted the platform")
	}
}

func TestAnUnsignedOrBadlySignedFiringIs401AndNothingRuns(t *testing.T) {
	var ran atomic.Bool
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		ran.Store(true)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	body := firingJSON(nil)
	if got := r.post("/fc/scheduled-jobs", body, nil); got.status != 401 || !strings.Contains(got.body, "MISSING_SIGNATURE") {
		t.Fatalf("unsigned: %d %s", got.status, got.body)
	}
	bad := signedHeaders(body, map[string]string{"X-FlowCatalyst-Signature": strings.Repeat("a", 64)})
	if got := r.post("/fc/scheduled-jobs", body, bad); got.status != 401 || !strings.Contains(got.body, "INVALID_SIGNATURE") {
		t.Fatalf("bad: %d %s", got.status, got.body)
	}
	time.Sleep(30 * time.Millisecond)
	if ran.Load() {
		t.Fatal("a handler ran")
	}
}

func TestMalformedFiringEnvelopesAre400AndNeverReachAHandler(t *testing.T) {
	var ran atomic.Bool
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		ran.Store(true)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	cases := map[string]string{
		"not an object":             `[]`,
		"not json":                  `nope`,
		"duplicate keys":            `{"jobId":"a","jobId":"b"}`,
		"no jobId":                  firingJSON(map[string]string{"jobId": ""}),
		"jobId a number":            firingJSON(map[string]string{"jobId": `5`}),
		"jobId with a space":        firingJSON(map[string]string{"jobId": `"a b"`}),
		"jobId too long":            firingJSON(map[string]string{"jobId": `"` + strings.Repeat("a", 129) + `"`}),
		"jobCode too long":          firingJSON(map[string]string{"jobCode": `"` + strings.Repeat("a", 187) + `"`}),
		"jobCode with a slash":      firingJSON(map[string]string{"jobCode": `"a/b"`}),
		"instanceId with ?":         firingJSON(map[string]string{"instanceId": `"a?b"`}),
		"no firedAt":                firingJSON(map[string]string{"firedAt": ""}),
		"firedAt not a time":        firingJSON(map[string]string{"firedAt": `"soon"`}),
		"firedAt too long":          firingJSON(map[string]string{"firedAt": `"` + strings.Repeat("2", 65) + `"`}),
		"triggerKind unknown":       firingJSON(map[string]string{"triggerKind": `"WEBHOOK"`}),
		"no triggerKind":            firingJSON(map[string]string{"triggerKind": ""}),
		"tracksCompletion str":      firingJSON(map[string]string{"tracksCompletion": `"yes"`}),
		"no tracksCompletion":       firingJSON(map[string]string{"tracksCompletion": ""}),
		"concurrent a string":       firingJSON(map[string]string{"concurrent": `"no"`}),
		"timeoutSeconds zero":       firingJSON(map[string]string{"timeoutSeconds": `0`}),
		"timeoutSeconds a fraction": firingJSON(map[string]string{"timeoutSeconds": `1.5`}),
		"timeoutSeconds a string":   firingJSON(map[string]string{"timeoutSeconds": `"5"`}),
		"scheduledFor not a time":   firingJSON(map[string]string{"scheduledFor": `"x"`}),
		"scheduledFor a number":     firingJSON(map[string]string{"scheduledFor": `5`}),
		"correlationId a number":    firingJSON(map[string]string{"correlationId": `5`}),
	}
	for name, body := range cases {
		got := r.fire(body)
		if got.status != 400 || !strings.Contains(got.body, "the scheduled-job envelope is malformed") {
			t.Errorf("%s: %d %s", name, got.status, got.body)
		}
	}
	time.Sleep(30 * time.Millisecond)
	if ran.Load() {
		t.Fatal("a handler ran")
	}
}

func TestAbsentOptionalFieldsAreFine(t *testing.T) {
	seen := &recorderOf[flowcatalyst.Firing]{}
	r := jobRig(t, func(_ context.Context, _ fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		seen.add(*f)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	body := firingJSON(map[string]string{"correlationId": "", "concurrent": "", "tracksCompletion": `false`})
	if got := r.fire(body); got.status != 202 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	waitFor(t, "the handler", func() bool { return len(seen.get()) == 1 })
	f := seen.get()[0]
	if f.Concurrent || f.Timeout != 0 || f.Payload != nil || f.CorrelationID.IsZero() || !f.ScheduledFor.IsZero() {
		t.Fatalf("firing %+v", f)
	}
	// nulls for the optional members
	null := firingJSON(map[string]string{"concurrent": `null`, "timeoutSeconds": `null`, "scheduledFor": `null`, "correlationId": `null`, "payload": `null`, "tracksCompletion": `false`})
	if got := r.fire(null); got.status != 202 {
		t.Fatalf("nulls: %d %s", got.status, got.body)
	}
}

func TestSuccessIsReportedWithTheResultAndFailuresWithTheMessage(t *testing.T) {
	for _, c := range []struct {
		name   string
		h      jobFn
		status string
		result string // the JSON of the result, "" for none
	}{
		{"a value", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			return flowcatalyst.Success{Value: map[string]any{"rows": 3}}, nil
		}, "SUCCESS", `{"rows":3}`},
		{"no value", okJob, "SUCCESS", ""},
		{"an error", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			return nil, errors.New("disk full")
		}, "FAILURE", `{"error":"disk full"}`},
		{"an error with no message", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			return nil, errors.New("")
		}, "FAILURE", `{"error":"*errors.errorString"}`},
		{"a failure result", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			return flowcatalyst.Failure{Message: "nothing to export"}, nil
		}, "FAILURE", `{"error":"nothing to export"}`},
		{"a nil result", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			return nil, nil
		}, "FAILURE", `{"error":"the handler returned no result"}`},
		{"a panic", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			panic("boom")
		}, "FAILURE", `{"error":"the handler panicked"}`},
		{"a value that cannot be encoded", func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
			return flowcatalyst.Success{Value: make(chan int)}, nil
		}, "SUCCESS", `{"unserialisable":true}`},
	} {
		t.Run(c.name, func(t *testing.T) {
			r := jobRig(t, c.h, flowcatalyst.JobOptions{}, rigOptions{})
			if got := r.fire(firingJSON(nil)); got.status != 202 {
				t.Fatalf("%d", got.status)
			}
			body := r.completions(t, 1)[0].json(t)
			if body["status"] != c.status {
				t.Fatalf("status %v, want %s", body["status"], c.status)
			}
			if c.result == "" {
				if _, has := body["result"]; has {
					t.Fatalf("a result was sent: %v", body)
				}
				return
			}
			raw := r.completions(t, 1)[0].body
			if !strings.Contains(raw, `"result":`+c.result) {
				t.Fatalf("body %s lacks result %s", raw, c.result)
			}
		})
	}
}

func TestAnUntrackedJobIsNeverCompleted(t *testing.T) {
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		return nil, errors.New("fails")
	}, flowcatalyst.JobOptions{}, rigOptions{})
	r.fire(firingJSON(map[string]string{"tracksCompletion": `false`}))
	time.Sleep(100 * time.Millisecond)
	if n := len(r.platform.calls("complete")); n != 0 {
		t.Fatalf("%d completions", n)
	}
}

func TestALongResultIsTruncatedTo10000Characters(t *testing.T) {
	for _, c := range []struct {
		name    string
		n       int
		cut     bool
		profile string
	}{{"over the limit", 10_000, true, ""}, {"exactly the limit", 9_998, false, ""}} {
		t.Run(c.name, func(t *testing.T) {
			value := strings.Repeat("x", c.n) // JSON: the string plus two quotes
			r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
				return flowcatalyst.Success{Value: value}, nil
			}, flowcatalyst.JobOptions{}, rigOptions{})
			r.fire(firingJSON(nil))
			res := r.completions(t, 1)[0].json(t)["result"]
			if c.cut {
				m, ok := res.(map[string]any)
				if !ok || m["truncated"] != true || len([]rune(m["preview"].(string))) != 10_000 {
					t.Fatalf("result %v", res)
				}
				return
			}
			if s, ok := res.(string); !ok || len(s) != c.n {
				t.Fatalf("a result of exactly the limit was cut: %T", res)
			}
		})
	}
}

func TestACompletionIsRetriedThreeTimesAndTheSDKsOwnRetriesDoNotMultiplyIt(t *testing.T) {
	for _, status := range []int{408, 429, 500, 502, 503, 504} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			p := newFakePlatform(t)
			p.failInstanceCalls("complete", status, 3)
			r := jobRig(t, okJob, flowcatalyst.JobOptions{}, rigOptions{platform: p})
			r.fire(firingJSON(nil))
			waitFor(t, "4 completion calls", func() bool { return len(p.calls("complete")) == 4 })
			time.Sleep(50 * time.Millisecond)
			if n := len(p.calls("complete")); n != 4 {
				t.Fatalf("%d calls, want exactly 4 (the first and three retries)", n)
			}
		})
	}
}

func TestACompletionThatKeepsFailingIsLoggedAtErrorAfterFourAttempts(t *testing.T) {
	p := newFakePlatform(t)
	p.failInstanceCalls("complete", 503, 100)
	r := jobRig(t, okJob, flowcatalyst.JobOptions{}, rigOptions{platform: p})
	r.fire(firingJSON(nil))
	waitFor(t, "the ERROR line", func() bool {
		return r.logs.has("ERROR flowcatalyst: could not report SUCCESS for scheduled job nightly-export")
	})
	if n := len(p.calls("complete")); n != 4 {
		t.Fatalf("%d calls, want 4", n)
	}
}

func TestTheRetryDelayDoublesFromTheSetting(t *testing.T) {
	p := newFakePlatform(t)
	p.failInstanceCalls("complete", 503, 3)
	r := jobRig(t, okJob, flowcatalyst.JobOptions{}, rigOptions{platform: p, env: map[string]string{"FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY": "60ms"}})
	start := time.Now()
	r.fire(firingJSON(nil))
	waitFor(t, "4 completion calls", func() bool { return len(p.calls("complete")) == 4 })
	if took := time.Since(start); took < 60*time.Millisecond+120*time.Millisecond+240*time.Millisecond {
		t.Fatalf("4 calls in %v: the waits (60, 120, 240 ms) were not honoured", took)
	}
}

func TestTheLogHookPostsToTheInstanceLogAndNeverFails(t *testing.T) {
	p := newFakePlatform(t)
	p.failInstanceCalls("log", 503, 1)
	done := make(chan struct{})
	r := jobRig(t, func(_ context.Context, _ fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		f.Log(flowcatalyst.LogInfo, "failing line") // the platform fails this one, once: one call, no retry
		f.Log(flowcatalyst.LogWarn, "second")
		f.Log(flowcatalyst.LogError, strings.Repeat("y", 20_000))
		close(done)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{platform: p})
	r.fire(firingJSON(nil))
	<-done
	logs := p.calls("log")
	if len(logs) != 3 {
		t.Fatalf("%d log calls, want 3 (the failed line is not retried)", len(logs))
	}
	if b := logs[1].json(t); b["level"] != "WARN" || b["message"] != "second" {
		t.Fatalf("second line %v", b)
	}
	if m := logs[2].json(t)["message"].(string); len(m) != 10_000 {
		t.Fatalf("a line of %d characters, want it cut to 10 000", len(m))
	}
	if !r.logs.has("could not append to the log of scheduled job") {
		t.Fatal("the failure is not logged")
	}
}

func TestTheEnvelopeTimeoutCancelsTheHandlerAndReportsFailure(t *testing.T) {
	cancelled := make(chan struct{})
	r := jobRig(t, func(ctx context.Context, _ fw.Context, _ *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		<-ctx.Done()
		close(cancelled)
		return nil, ctx.Err()
	}, flowcatalyst.JobOptions{}, rigOptions{})
	r.fire(firingJSON(map[string]string{"timeoutSeconds": `1`}))
	c := r.completions(t, 1)[0]
	if b := c.json(t); b["status"] != "FAILURE" || !strings.Contains(c.body, `"error":"timed out after 1s"`) {
		t.Fatalf("completion %s", c.body)
	}
	<-cancelled
	time.Sleep(50 * time.Millisecond)
	if n := len(r.platform.calls("complete")); n != 1 {
		t.Fatalf("%d completions: the cancelled handler's return was reported too", n)
	}
}

func TestWithoutAnEnvelopeTimeoutTheSettingApplies(t *testing.T) {
	r := jobRig(t, func(ctx context.Context, _ fw.Context, _ *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		<-ctx.Done()
		return nil, ctx.Err()
	}, flowcatalyst.JobOptions{}, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_JOB_TIMEOUT": "150ms"}})
	r.fire(firingJSON(nil))
	c := r.completions(t, 1)[0]
	if !strings.Contains(c.body, `"error":"timed out after 150ms"`) {
		t.Fatalf("completion %s", c.body)
	}
}

func TestATimedOutHandlerThatIgnoresCancellationKeepsTheLockUntilItStops(t *testing.T) {
	release := make(chan struct{})
	started := make(chan struct{}, 2)
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		started <- struct{}{}
		<-release // ignores the context
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_JOB_TIMEOUT": "100ms"}})
	r.fire(firingJSON(nil))
	<-started
	r.completions(t, 1) // timed out: reported at once
	// the next firing finds the lock held
	r.fire(firingJSON(map[string]string{"instanceId": `"sji_second"`}))
	c := r.completions(t, 2)[1]
	if !strings.Contains(c.body, `"reason":"lock-held"`) {
		t.Fatalf("second completion %s", c.body)
	}
	close(release)
	waitFor(t, "the lock to be released", func() bool {
		r.fire(firingJSON(map[string]string{"instanceId": `"sji_third"`}))
		select {
		case <-started:
			return true
		case <-time.After(300 * time.Millisecond):
			return false
		}
	})
}

func TestAHeldLockSkipsTheRunAndReportsLockHeld(t *testing.T) {
	var runs atomic.Int32
	release := make(chan struct{})
	started := make(chan struct{})
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		if runs.Add(1) == 1 {
			close(started)
			<-release
		}
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	r.fire(firingJSON(nil))
	<-started
	r.fire(firingJSON(map[string]string{"instanceId": `"sji_second"`}))
	c := r.completions(t, 1)[0]
	if b := c.json(t); b["status"] != "FAILURE" || !strings.Contains(c.body, `"skipped":true`) || !strings.Contains(c.body, `"reason":"lock-held"`) {
		t.Fatalf("completion %s", c.body)
	}
	if c.path != "/api/scheduled-jobs/instances/sji_second/complete" {
		t.Fatalf("path %s", c.path)
	}
	close(release)
	if runs.Load() != 2 && runs.Load() != 1 {
		t.Fatalf("runs %d", runs.Load())
	}
	// an untracked skipped run is silent
	r.fire(firingJSON(map[string]string{"instanceId": `"sji_untracked"`, "tracksCompletion": `false`}))
}

func TestOfTenFiringsAtOnceExactlyOneRuns(t *testing.T) {
	var runs atomic.Int32
	hold := make(chan struct{})
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		runs.Add(1)
		<-hold
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	var wg sync.WaitGroup
	for i := range 10 {
		wg.Go(func() { r.fire(firingJSON(map[string]string{"instanceId": fmt.Sprintf(`"sji_%d"`, i)})) })
	}
	wg.Wait()
	cs := r.completions(t, 9) // nine skipped
	time.Sleep(100 * time.Millisecond)
	close(hold)
	if runs.Load() != 1 || len(cs) != 9 {
		t.Fatalf("%d runs and %d skips, want 1 and 9", runs.Load(), len(cs))
	}
}

// sharedStore is a memory lock store the test can reach, on a clock the test owns.
func sharedStore(clk fw.Clock) (*locks.MemoryStore, locks.StoreFactory) {
	store := locks.NewMemoryStore(clk)
	return store, func(context.Context, *fw.Boot) (locks.Store, error) { return store, nil }
}

func TestTheLockIsHeldWhileRunningAndReleasedAfterwardsWithAGrowingFencingToken(t *testing.T) {
	clk := fwtest.NewClock(testNow)
	store, factory := sharedStore(clk)
	probe := locks.New(store, locks.WithClock(clk))
	key := locks.MustServiceKey("scheduled-job:" + jobCode)
	fences := &recorderOf[int64]{}
	hold := make(chan struct{})
	started := make(chan struct{}, 2)
	r := jobRig(t, func(_ context.Context, _ fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		tok, _ := f.FencingToken()
		fences.add(tok)
		started <- struct{}{}
		<-hold
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{clock: clk, lockFactory: factory})
	r.fire(firingJSON(nil))
	<-started
	if acq, err := probe.TryAcquire(t.Context(), key, time.Minute); err != nil {
		t.Fatal(err)
	} else if _, busy := acq.(locks.Busy); !busy {
		t.Fatalf("the lock is free while the job runs: %T", acq)
	}
	hold <- struct{}{}
	r.completions(t, 1)
	waitFor(t, "the lock to be released", func() bool {
		acq, err := probe.TryAcquire(t.Context(), key, time.Minute)
		if err != nil {
			return false
		}
		if a, ok := acq.(locks.Acquired); ok {
			_, _ = probe.Release(t.Context(), a.Lease)
			return true
		}
		return false
	})
	r.fire(firingJSON(map[string]string{"instanceId": `"sji_second"`}))
	<-started
	hold <- struct{}{}
	got := fences.get()
	if len(got) != 2 || got[1] <= got[0] || got[0] < 1 {
		t.Fatalf("fencing tokens %v: they must grow", got)
	}
}

func TestAConcurrentJobTakesNoLock(t *testing.T) {
	var running, peak atomic.Int32
	hold := make(chan struct{})
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		n := running.Add(1)
		for {
			p := peak.Load()
			if n <= p || peak.CompareAndSwap(p, n) {
				break
			}
		}
		<-hold
		running.Add(-1)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{})
	for i := range 3 {
		r.fire(firingJSON(map[string]string{"concurrent": "true", "instanceId": fmt.Sprintf(`"sji_%d"`, i)}))
	}
	waitFor(t, "three runs at once", func() bool { return running.Load() == 3 })
	close(hold)
	if peak.Load() != 3 {
		t.Fatalf("peak %d", peak.Load())
	}
}

func TestALostLockCancelsTheHandlerAndReportsFailure(t *testing.T) {
	clk := fwtest.NewClock(testNow)
	store, factory := sharedStore(clk)
	cancelled := make(chan struct{})
	started := make(chan struct{})
	r := jobRig(t, func(ctx context.Context, _ fw.Context, _ *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		close(started)
		<-ctx.Done()
		close(cancelled)
		return nil, ctx.Err()
	}, flowcatalyst.JobOptions{}, rigOptions{
		clock: clk, lockFactory: factory,
		env: map[string]string{"FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE": "1s"},
	})
	r.fire(firingJSON(nil))
	<-started
	store.ExpireNow("svc:scheduled-job:" + jobCode) // the lease is gone: the next refresh finds it lost
	select {
	case <-cancelled:
	case <-time.After(10 * time.Second):
		clk.Advance(time.Second)
		<-cancelled
	}
	c := r.completions(t, 1)[0]
	if !strings.Contains(c.body, `"error":"the overlap lock was lost while the job ran"`) || c.json(t)["status"] != "FAILURE" {
		t.Fatalf("completion %s", c.body)
	}
	if !r.logs.has("lost its overlap lock") {
		t.Fatal("not logged at ERROR")
	}
}

func TestTheLeaseIsRefreshedSoALongRunKeepsTheLock(t *testing.T) {
	// real time: a lease of one second, a run of two and a half
	sys := fw.SystemClock()
	store, factory := sharedStore(sys)
	_ = store
	started := make(chan struct{})
	release := make(chan struct{})
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		close(started)
		<-release
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{clock: sys, lockFactory: factory, env: map[string]string{"FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE": "1s"}})
	fireNow := func(instance string) reply {
		ts := sys.Now().UTC().Format("2006-01-02T15:04:05.000Z")
		body := firingJSON(map[string]string{"instanceId": `"` + instance + `"`, "firedAt": `"` + ts + `"`})
		return r.post("/fc/scheduled-jobs", body, map[string]string{
			"X-FlowCatalyst-Signature": signature(testSecret, ts, body), "X-FlowCatalyst-Timestamp": ts,
		})
	}
	if got := fireNow("sji_first"); got.status != 202 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	<-started
	time.Sleep(2500 * time.Millisecond) // beyond two leases
	fireNow("sji_second")
	c := r.completions(t, 1)[0]
	if !strings.Contains(c.body, `"reason":"lock-held"`) {
		t.Fatalf("the lease was not refreshed: %s", c.body)
	}
	close(release)
}

type downStore struct {
	locks.Store
	down atomic.Bool
}

func (d *downStore) TryAcquire(ctx context.Context, key, owner string, lease int64) (int64, bool, error) {
	if d.down.Load() {
		return 0, false, fmt.Errorf("%w: store is down", locks.ErrStoreUnavailable)
	}
	return d.Store.TryAcquire(ctx, key, owner, lease)
}

func TestALockStoreThatIsDownMeansTheJobDoesNotRun(t *testing.T) {
	var ran atomic.Bool
	ds := &downStore{Store: locks.NewMemoryStore(fwtest.NewClock(testNow))}
	ds.down.Store(true)
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		ran.Store(true)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{lockFactory: func(context.Context, *fw.Boot) (locks.Store, error) { return ds, nil }})
	r.fire(firingJSON(nil))
	c := r.completions(t, 1)[0]
	if !strings.Contains(c.body, `"error":"the overlap lock could not be taken; the job did not run"`) || ran.Load() {
		t.Fatalf("completion %s (ran: %v)", c.body, ran.Load())
	}
}

func TestAnAlwaysConcurrentJobNeedsNoLockAndTakesNoneEvenWhenOneIsConfigured(t *testing.T) {
	// no lock store at all: start-up succeeds and the job runs
	ran := make(chan struct{}, 2)
	h := func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		ran <- struct{}{}
		return flowcatalyst.Success{}, nil
	}
	r := jobRig(t, h, flowcatalyst.JobOptions{AlwaysConcurrent: true}, rigOptions{})
	if got := r.fire(firingJSON(map[string]string{"concurrent": "true"})); got.status != 202 {
		t.Fatalf("%d", got.status)
	}
	<-ran
	// with a lock store configured and held by someone else, it still runs
	clk := fwtest.NewClock(testNow)
	store, factory := sharedStore(clk)
	probe := locks.New(store, locks.WithClock(clk))
	if _, err := probe.TryAcquire(t.Context(), locks.MustServiceKey("scheduled-job:"+jobCode), time.Hour); err != nil {
		t.Fatal(err)
	}
	r2 := jobRig(t, h, flowcatalyst.JobOptions{AlwaysConcurrent: true}, rigOptions{clock: clk, lockFactory: factory})
	r2.fire(firingJSON(map[string]string{"concurrent": "true"}))
	<-ran
}

func TestAnExclusiveFiringForAnAlwaysConcurrentJobIsRefusedAndReported(t *testing.T) {
	var ran atomic.Bool
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		ran.Store(true)
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{AlwaysConcurrent: true}, rigOptions{})
	r.fire(firingJSON(nil)) // concurrent=false
	c := r.completions(t, 1)[0]
	if b := c.json(t); b["status"] != "FAILURE" || !strings.Contains(c.body, `"reason":"not-registered-for-exclusive-run"`) || !strings.Contains(c.body, `"skipped":true`) {
		t.Fatalf("completion %s", c.body)
	}
	if ran.Load() || !r.logs.has("ERROR flowcatalyst: scheduled job nightly-export instance sji_0HZXEQ5Y8JY5Z was not run") {
		t.Fatalf("ran=%v\n%s", ran.Load(), r.logs.all())
	}
	// untracked: refused silently (logged), not reported
	r.platform.clear()
	r.fire(firingJSON(map[string]string{"tracksCompletion": `false`, "instanceId": `"sji_untracked"`}))
	time.Sleep(100 * time.Millisecond)
	if n := len(r.platform.calls("complete")); n != 0 {
		t.Fatalf("%d completions for an untracked refusal", n)
	}
}

func TestOnlyExclusiveJobsNeedTheLockAtStartUp(t *testing.T) {
	startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		return fc.Receivers().OnScheduledJob("free", flowcatalyst.JobOptions{AlwaysConcurrent: true}, okJob)
	}})
	_, err := tryRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		return fc.Receivers().OnScheduledJob("guarded", flowcatalyst.JobOptions{}, okJob)
	}})
	mustFail(t, err, "[guarded]", "overlap lock", "no lock store")
	_, err = tryRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		if err := fc.Receivers().OnScheduledJob("free", flowcatalyst.JobOptions{AlwaysConcurrent: true}, okJob); err != nil {
			return err
		}
		return fc.Receivers().OnScheduledJob("guarded", flowcatalyst.JobOptions{}, okJob)
	}})
	mustFail(t, err, "[guarded]")
}

func TestADuplicateOrMalformedJobCodeIsAnError(t *testing.T) {
	startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		rcv := fc.Receivers()
		opts := flowcatalyst.JobOptions{AlwaysConcurrent: true}
		if err := rcv.OnScheduledJob("a", opts, okJob); err != nil {
			return err
		}
		if err := rcv.OnScheduledJob("a", opts, okJob); err == nil || !strings.Contains(err.Error(), "already registered") {
			t.Errorf("duplicate: %v", err)
		}
		for _, bad := range []string{"", "a b", "a/b", strings.Repeat("a", 187), "a\nb"} {
			if err := rcv.OnScheduledJob(bad, opts, okJob); err == nil || !strings.Contains(err.Error(), "malformed") {
				t.Errorf("code %q: %v", bad, err)
			}
		}
		if err := rcv.OnScheduledJob(strings.Repeat("a", 186), opts, okJob); err != nil {
			t.Errorf("186 characters: %v", err)
		}
		return nil
	}})
}

func TestShutdownCancelsARunningJobAndReportsIt(t *testing.T) {
	started := make(chan struct{})
	r := jobRig(t, func(ctx context.Context, _ fw.Context, _ *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		close(started)
		<-ctx.Done()
		return nil, ctx.Err()
	}, flowcatalyst.JobOptions{}, rigOptions{})
	r.fire(firingJSON(nil))
	<-started
	if err := r.run.Shutdown(); err != nil {
		t.Fatal(err)
	}
	c := r.completions(t, 1)[0]
	if !strings.Contains(c.body, `"error":"interrupted: the application is stopping"`) {
		t.Fatalf("completion %s", c.body)
	}
	// a firing after shutdown began is not accepted
	if got := r.fire(firingJSON(map[string]string{"instanceId": `"sji_late"`})); got.status != 503 && got.status != 202 {
		t.Fatalf("late firing: %d", got.status)
	}
}

func TestALockLostAfterATimeoutIsNotReportedASecondTime(t *testing.T) {
	clk := fwtest.NewClock(testNow)
	store, factory := sharedStore(clk)
	release := make(chan struct{})
	started := make(chan struct{})
	r := jobRig(t, func(context.Context, fw.Context, *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
		close(started)
		<-release // ignores its context
		return flowcatalyst.Success{}, nil
	}, flowcatalyst.JobOptions{}, rigOptions{clock: clk, lockFactory: factory, env: map[string]string{
		"FLOWCATALYST_RECEIVERS_JOB_TIMEOUT": "100ms", "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE": "1s",
	}})
	r.fire(firingJSON(nil))
	<-started
	r.completions(t, 1) // the timeout, reported at once
	store.ExpireNow("svc:scheduled-job:" + jobCode)
	waitFor(t, "the lost lock to be noticed", func() bool { return r.logs.has("lost its overlap lock") })
	close(release)
	time.Sleep(200 * time.Millisecond)
	if n := len(r.platform.calls("complete")); n != 1 {
		t.Fatalf("%d completions: the instance was reported again", n)
	}
}
