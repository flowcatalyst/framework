package flowcatalyst

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf16"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
	"github.com/flowcatalyst/framework/go/locks"
)

const (
	// completionRetries is the retries after the first completion call: four calls in all.
	completionRetries = 3
	lockPrefix        = "scheduled-job:"
)

var idRe = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

// jobReceiver is POST {prefix}/scheduled-jobs.
type jobReceiver struct {
	receiverDeps
	runner   *jobRunner
	platform *PlatformClient
}

func (j *jobReceiver) handle(w http.ResponseWriter, r *http.Request) error {
	if !j.ready.Load() {
		return unavailable("the receivers are not ready")
	}
	body := bodyOnce(r)
	if err := j.refuse(r, body); err != nil {
		return err
	}
	raw, err := body()
	if err != nil {
		return err
	}
	parsed, merr := parseFiring(raw)
	if merr != "" {
		return httpfw.Validation("the scheduled-job envelope is malformed: " + merr)
	}
	firing := &parsed
	h := j.recv.jobHandler(firing.JobCode)
	if h == nil {
		j.log.Warn(fmt.Sprintf("flowcatalyst: no handler for scheduled job %s (instance %s)", firing.JobCode, firing.InstanceID))
		return httpfw.NotFound("no handler for scheduled job " + firing.JobCode)
	}
	// before the 202: a firing that cannot run as the service principal is redelivered
	principal, err := j.principal(r.Context())
	if err != nil {
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s was not run: %s (%v); answered 503, the platform redelivers",
			firing.JobCode, firing.InstanceID, noPrincipal, err))
		return unavailable(noPrincipal)
	}
	if !j.runner.start(firing, h, principal) {
		return unavailable("the application is stopping")
	}
	httpfw.Bytes(w, http.StatusAccepted, "application/json", []byte(`{"ok":true}`))
	return nil
}

func parseFiring(body []byte) (Firing, string) {
	var root map[string]jsontext.Value
	if err := json.Unmarshal(body, &root); err != nil {
		return Firing{}, "the body must be one JSON object with unique keys"
	}
	required := func(field string, re *regexp.Regexp) (string, string) {
		v := root[field]
		if v.Kind() != '"' {
			return "", field + " must be a string"
		}
		s := textOf(v)
		if re != nil && !re.MatchString(s) {
			return "", field + " is not well formed"
		}
		return s, ""
	}
	var f Firing
	var msg string
	if f.JobID, msg = required("jobId", idRe); msg != "" {
		return Firing{}, msg
	}
	if f.JobCode, msg = required("jobCode", jobCodeRe); msg != "" {
		return Firing{}, msg
	}
	if f.InstanceID, msg = required("instanceId", idRe); msg != "" {
		return Firing{}, msg
	}
	fired, msg := required("firedAt", nil)
	if msg != "" {
		return Firing{}, msg
	}
	var ok bool
	if f.FiredAt, ok = parseInstant(fired); !ok {
		return Firing{}, "firedAt must be an RFC 3339 time"
	}
	trigger, msg := required("triggerKind", nil)
	if msg != "" {
		return Firing{}, msg
	}
	if trigger != "CRON" && trigger != "MANUAL" {
		return Firing{}, "triggerKind must be CRON or MANUAL"
	}
	f.Trigger = trigger
	tracks := root["tracksCompletion"]
	if tracks.Kind() != 't' && tracks.Kind() != 'f' {
		return Firing{}, "tracksCompletion must be a boolean"
	}
	f.TracksCompletion = tracks.Kind() == 't'
	if c, present := root["concurrent"]; present && c.Kind() != 'n' {
		if c.Kind() != 't' && c.Kind() != 'f' {
			return Firing{}, "concurrent must be a boolean"
		}
		f.Concurrent = c.Kind() == 't'
	}
	if t, present := root["timeoutSeconds"]; present && t.Kind() != 'n' {
		secs, ok := positiveInt(t)
		if !ok {
			return Firing{}, "timeoutSeconds must be a positive integer"
		}
		f.Timeout = time.Duration(secs) * time.Second
	}
	scheduled, msg := optionalString(root, "scheduledFor")
	if msg != "" {
		return Firing{}, msg
	}
	if scheduled != "" {
		if f.ScheduledFor, ok = parseInstant(scheduled); !ok {
			return Firing{}, "scheduledFor must be an RFC 3339 time"
		}
	}
	correlation, msg := optionalString(root, "correlationId")
	if msg != "" {
		return Firing{}, msg
	}
	f.CorrelationID = correlationOf(correlation)
	if p, present := root["payload"]; present && p.Kind() != 'n' {
		f.Payload = bytes.Clone(p)
	}
	return f, ""
}

func parseInstant(s string) (time.Time, bool) {
	if len(s) > 64 {
		return time.Time{}, false
	}
	t, err := time.Parse(time.RFC3339, s)
	return t, err == nil
}

// jobRunner runs the accepted firings, one goroutine each.
type jobRunner struct {
	platform   *PlatformClient
	app        fw.ApplicationCode
	locks      *locks.Locks
	defTimeout time.Duration
	lease      time.Duration
	retryDelay time.Duration
	log        *slog.Logger
	ctx        context.Context
	cancel     context.CancelFunc
	wg         sync.WaitGroup
	stopping   atomic.Bool
}

func newJobRunner(ctx context.Context, p *PlatformClient, app fw.ApplicationCode, l *locks.Locks, defTimeout, lease, retryDelay time.Duration, log *slog.Logger) *jobRunner {
	rctx, cancel := context.WithCancel(context.WithoutCancel(ctx))
	return &jobRunner{platform: p, app: app, locks: l, defTimeout: defTimeout, lease: lease, retryDelay: retryDelay, log: log, ctx: rctx, cancel: cancel}
}

// start runs a firing in the background; false when the runner is stopping.
func (j *jobRunner) start(f *Firing, h *jobHandler, principal fw.PrincipalID) bool {
	if j.stopping.Load() {
		return false
	}
	j.wg.Go(func() {
		defer func() {
			if p := recover(); p != nil {
				j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s ended unexpectedly: %v\n%s", f.JobCode, f.InstanceID, p, debug.Stack()))
			}
		}()
		j.run(f, h, principal)
	})
	return true
}

// stop cancels the running firings (each is reported failed, "interrupted: the application is
// stopping") and waits for them up to drain.
func (j *jobRunner) stop(drain time.Duration) {
	j.stopping.Store(true)
	j.cancel()
	done := make(chan struct{})
	go func() { j.wg.Wait(); close(done) }()
	t := time.NewTimer(drain)
	defer t.Stop()
	select {
	case <-done:
	case <-t.C:
		j.log.Warn("flowcatalyst: scheduled jobs still running after the drain; abandoned")
	}
}

// outcome is how one firing ended, as reported to the platform.
type outcome struct {
	status          Completion
	result          []byte
	alreadyReported bool
}

func (j *jobRunner) run(f *Firing, h *jobHandler, principal fw.PrincipalID) {
	// contract section 4b: the service principal acts; the delivered instance is the cause
	cx, err := deliveryContext(PlatformClientID, principal, j.app, f.CorrelationID, f.InstanceID)
	if err != nil {
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s was not run: %v", f.JobCode, f.InstanceID, err))
		return
	}
	reportCtx := context.WithoutCancel(j.ctx)
	f.log = func(level LogLevel, message string) {
		if err := j.platform.LogJobInstance(reportCtx, f.InstanceID, level, message); err != nil {
			j.log.Warn(fmt.Sprintf("flowcatalyst: could not append to the log of scheduled job %s instance %s: %T", f.JobCode, f.InstanceID, err))
		}
	}
	timeout := f.Timeout
	if timeout <= 0 {
		timeout = j.defTimeout
	}
	rep := &report{j: j, f: f, ctx: reportCtx}
	if f.Concurrent {
		rep.complete(j.invoke(j.ctx, h, cx, f, timeout, rep))
		return
	}
	if h.opts.AlwaysConcurrent {
		// the handler declared it never takes the overlap lock, but this firing asks for exclusion,
		// which only the lock could give: refuse rather than run it unprotected
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s was not run: it is registered always-concurrent but the platform asked for an exclusive run (concurrent=false); "+
			"define the job with concurrent=true or register the handler without JobOptions.AlwaysConcurrent", f.JobCode, f.InstanceID))
		rep.complete(outcomeRaw(CompletionFailure, `{"skipped":true,"reason":"not-registered-for-exclusive-run"}`))
		return
	}
	var lock *locks.Lock
	if j.locks != nil {
		lock, _ = j.locks.Default()
	}
	if lock == nil {
		// start-up refuses job handlers without a lock store; never run unprotected
		rep.complete(failureOutcome("no lock instance is configured for the overlap lock"))
		return
	}
	key, err := locks.ServiceKey(lockPrefix + f.JobCode)
	if err != nil {
		rep.complete(failureOutcome("the overlap lock could not be taken; the job did not run"))
		return
	}
	res, err := locks.With(j.ctx, lock, key, j.lease, 0, func(actionCtx context.Context, lease locks.Lease) (outcome, error) {
		f.fence, f.hasFence = lease.Fence(), true
		return j.invoke(actionCtx, h, cx, f, timeout, rep), nil
	})
	if err != nil {
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s did not run: the overlap lock could not be taken (%T)", f.JobCode, f.InstanceID, err))
		rep.complete(failureOutcome("the overlap lock could not be taken; the job did not run"))
		return
	}
	switch res.Outcome {
	case locks.NotAcquired:
		j.log.Info(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s skipped: another run holds its lock", f.JobCode, f.InstanceID))
		rep.complete(outcomeRaw(CompletionFailure, `{"skipped":true,"reason":"lock-held"}`))
	case locks.Completed:
		rep.complete(res.Value)
	case locks.Lost:
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s lost its overlap lock while running; the handler was cancelled and the run is reported as failed", f.JobCode, f.InstanceID))
		rep.complete(failureOutcome("the overlap lock was lost while the job ran"))
	}
}

type handlerResult struct {
	res JobResult
	err error
	pan any
}

// invoke runs the handler under its timeout. parent is cancelled when the lock is lost or the
// application is stopping.
func (j *jobRunner) invoke(parent context.Context, h *jobHandler, cx fw.Context, f *Firing, timeout time.Duration, rep *report) outcome {
	hctx, cancel := context.WithCancel(parent)
	defer cancel()
	done := make(chan handlerResult, 1)
	go func() {
		var out handlerResult
		defer func() {
			if p := recover(); p != nil {
				out = handlerResult{pan: p}
			}
			done <- out
		}()
		out.res, out.err = h.run(hctx, cx, f)
	}()
	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case out := <-done:
		return j.settle(f, out)
	case <-parent.Done():
		// the lock was lost (With turns this into Lost) or the application is stopping
		cancel()
		return failureOutcome("interrupted: the application is stopping")
	case <-timer.C:
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s did not finish within %v (correlation %s); cancelled it", f.JobCode, f.InstanceID, timeout, f.CorrelationID))
		// report now; keep the lock (the caller's With refreshes it) until the handler stops
		rep.complete(failureOutcome("timed out after " + human(timeout)))
		cancel()
		select {
		case <-done:
		case <-parent.Done(): // the lock was lost or the application is stopping: stop waiting
		}
		return outcome{alreadyReported: true}
	}
}

func (j *jobRunner) settle(f *Firing, out handlerResult) outcome {
	switch {
	case out.pan != nil:
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s panicked (correlation %s): %v", f.JobCode, f.InstanceID, f.CorrelationID, out.pan))
		return failureOutcome("the handler panicked")
	case out.err != nil:
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s failed (correlation %s): %v", f.JobCode, f.InstanceID, f.CorrelationID, out.err))
		return failureOutcome(describeError(out.err))
	}
	switch r := out.res.(type) {
	case Success:
		return successOutcome(r.Value)
	case Failure:
		return failureOutcome(r.Message)
	case nil:
		j.log.Error(fmt.Sprintf("flowcatalyst: scheduled job %s instance %s handler returned null (correlation %s)", f.JobCode, f.InstanceID, f.CorrelationID))
		return failureOutcome("the handler returned no result")
	}
	return failureOutcome("the handler returned a result of an unknown type")
}

func describeError(err error) string {
	if m := err.Error(); strings.TrimSpace(m) != "" {
		return m
	}
	return fmt.Sprintf("%T", err)
}

func human(d time.Duration) string {
	if d > 0 && d%time.Second == 0 {
		return strconv.FormatInt(int64(d/time.Second), 10) + "s"
	}
	return strconv.FormatInt(d.Milliseconds(), 10) + "ms"
}

func outcomeRaw(status Completion, json string) outcome {
	return outcome{status: status, result: []byte(json)}
}

func quote(s string) string {
	b, _ := jsontext.AppendQuote(nil, strings.ToValidUTF8(s, "�")) // cannot fail on valid UTF-8
	return string(javafmt.UpperHexEscapes(b))
}

func failureOutcome(msg string) outcome {
	return outcome{status: CompletionFailure, result: sanitise([]byte(`{"error":` + quote(msg) + `}`))}
}

func successOutcome(value any) outcome {
	if value == nil {
		return outcome{status: CompletionSuccess}
	}
	b, err := javafmt.MarshalJSON(value)
	if err != nil {
		return outcome{status: CompletionSuccess, result: []byte(`{"unserialisable":true}`)}
	}
	return outcome{status: CompletionSuccess, result: sanitise(b)}
}

// sanitise keeps a result to MaxResultChars characters of JSON: longer is replaced by
// {"truncated":true,"preview":<the first 10 000 characters>}.
func sanitise(b []byte) []byte {
	units := utf16.Encode([]rune(string(b)))
	if len(units) <= MaxResultChars {
		return b
	}
	preview := string(utf16.Decode(units[:MaxResultChars]))
	return []byte(`{"truncated":true,"preview":` + quote(preview) + `}`)
}

// report sends the completion of a firing at most once, with the runner's own retry: the first
// call and three retries after retryDelay, doubling. The SDK's transport retry is off for it.
type report struct {
	j    *jobRunner
	f    *Firing
	ctx  context.Context
	sent atomic.Bool
}

func (r *report) complete(o outcome) {
	if o.alreadyReported || !r.f.TracksCompletion || !r.sent.CompareAndSwap(false, true) {
		return
	}
	attempts := 1 + completionRetries
	delay := max(time.Millisecond, r.j.retryDelay)
	var last error
	for attempt := 1; attempt <= attempts; attempt++ {
		err := r.j.platform.CompleteJobInstance(r.ctx, r.f.InstanceID, o.status, o.result)
		if err == nil {
			return
		}
		last = err
		if attempt == attempts {
			break
		}
		r.j.log.Warn(fmt.Sprintf("flowcatalyst: completion of scheduled job %s instance %s failed (attempt %d of %d); retrying in %d ms",
			r.f.JobCode, r.f.InstanceID, attempt, attempts, delay.Milliseconds()))
		t := time.NewTimer(delay)
		<-t.C
		delay *= 2
	}
	r.j.log.Error(fmt.Sprintf("flowcatalyst: could not report %s for scheduled job %s instance %s: %v", o.status, r.f.JobCode, r.f.InstanceID, last))
}
