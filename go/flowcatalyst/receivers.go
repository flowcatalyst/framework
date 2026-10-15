package flowcatalyst

import (
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"sync"
	"time"
	"unicode"
	"unicode/utf16"

	"github.com/flowcatalyst/framework/go/fw"
)

// PlatformClientID is the client of a context that has none from the platform: a dispatch job
// without X-FlowCatalyst-Client, and every scheduled-job firing. It cannot equal a platform
// client identifier.
var PlatformClientID = fw.MustClientID("_platform")

var (
	segmentRe = regexp.MustCompile(`^[A-Za-z0-9._-]+$`)
	jobCodeRe = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,186}$`)
)

// DispatchResult is what a dispatch handler decided: [Ack], [Defer] or [Reject].
//
//sumtype:decl
type DispatchResult interface{ dispatchResult() }

// Ack acknowledges the job: 200 {"ack":true}.
type Ack struct{}

// Defer asks the platform to deliver the job again later, without spending its retry budget:
// 200 {"ack":false,"delaySeconds":n} with n the delay rounded up to whole seconds. The delay is 0
// to 2^32-1 seconds; use [DefaultDefer] for the usual 30 seconds.
type Defer struct{ Delay time.Duration }

// Reject refuses the job for good: 422 REJECTED with the header FlowCatalyst-Outcome: reject. The
// reason is required (not blank), cleaned (control and format characters become spaces) and cut to
// 500 characters; it never contains the handler's internals unless the handler puts them there.
type Reject struct{ Reason string }

func (Ack) dispatchResult()    {}
func (Defer) dispatchResult()  {}
func (Reject) dispatchResult() {}

// DefaultDefer is the delay of a deferral that names none.
const DefaultDefer = 30 * time.Second

const (
	maxDeferSeconds = 4_294_967_295
	maxReasonLength = 500
)

// JobResult is what a scheduled-job handler returned: [Success] or [Failure].
//
//sumtype:decl
type JobResult interface{ jobResult() }

// Success is a job that ran: Value (any JSON-encodable value, nil for none) is reported as the
// instance's result when the job tracks completion. Over 10 000 characters of JSON it is replaced
// by {"truncated":true,"preview":<the first 10 000>}; a value that cannot be encoded by
// {"unserialisable":true}.
type Success struct{ Value any }

// Failure is a job that failed without an error.
type Failure struct{ Message string }

func (Success) jobResult() {}
func (Failure) jobResult() {}

// MaxResultChars is the longest result, in characters of JSON, reported to the platform.
const MaxResultChars = 10_000

// JobOptions are the options of a scheduled-job handler.
type JobOptions struct {
	// AlwaysConcurrent says the handler never takes the overlap lock. A firing that asks for an
	// exclusive run (concurrent=false) is then refused rather than run unprotected.
	AlwaysConcurrent bool
}

// DeliveryClient is the client named by the X-FlowCatalyst-Client header: the platform's
// identifier and the client's code.
type DeliveryClient struct {
	ID   string
	Code fw.ClientID
}

// IsZero reports whether the delivery named no client.
func (c DeliveryClient) IsZero() bool { return c.Code.IsZero() }

// DeliveryMeta is what a dispatch delivery says about itself besides its data.
type DeliveryMeta struct {
	// JobID is the dispatch job's id (X-Dispatch-Job-Id), the idempotency key of [Once].
	JobID string
	// EventType is the delivered event type (X-Event-Type).
	EventType string
	// Attempt is the attempt number, 1 or more; 0 when the body is data-only.
	Attempt int
	// Source, Subject and MessageGroup are the envelope's, "" when absent or data-only.
	Source, Subject, MessageGroup string
	// CorrelationID is the envelope's when well formed, else generated.
	CorrelationID fw.CorrelationID
	// Client is the X-FlowCatalyst-Client header; the zero value when absent.
	Client DeliveryClient
	// DataOnly says the body is the raw payload, not the platform's envelope.
	DataOnly bool
	// Raw is the body as received (the bytes that were verified).
	Raw []byte
}

// Delivery is a dispatch job delivered to a handler: its metadata and its data as a T.
type Delivery[T any] struct {
	Meta DeliveryMeta
	// Data is the envelope's data (or, for data-only, the body) decoded into a T.
	Data T
	// HasData says the delivery carried data; false for an envelope without a data member.
	HasData bool
}

// DispatchFunc is an untyped dispatch handler: the data is the raw JSON. It returns the result to
// answer with, or an error, which is a transient failure (503: the platform retries).
type DispatchFunc func(ctx context.Context, cx fw.Context, d Delivery[jsontext.Value]) (DispatchResult, error)

// Firing is a scheduled-job firing delivered to a handler.
type Firing struct {
	// JobID names the job's definition, JobCode its code, InstanceID this firing.
	JobID, JobCode, InstanceID string
	// ScheduledFor is when it was due; the zero time when absent.
	ScheduledFor time.Time
	// FiredAt is when the platform fired it.
	FiredAt time.Time
	// Trigger is CRON or MANUAL.
	Trigger string
	// CorrelationID is the envelope's when well formed, else generated.
	CorrelationID fw.CorrelationID
	// Payload is the job's payload, nil when absent.
	Payload jsontext.Value
	// TracksCompletion says the platform waits for the completion report.
	TracksCompletion bool
	// Timeout is the firing's own timeout; 0 when it names none.
	Timeout time.Duration
	// Concurrent says the job may overlap itself.
	Concurrent bool

	fence    int64
	hasFence bool
	log      func(level LogLevel, message string)
}

// FencingToken returns the overlap lock's fencing token, and false for a concurrent job.
func (f *Firing) FencingToken() (int64, bool) { return f.fence, f.hasFence }

// maxLogLength is the longest instance log line.
const maxLogLength = 10_000

// Log appends a line to this instance's log on the platform, best effort: a failure is logged
// locally and never returned. Lines are cut to 10 000 characters.
func (f *Firing) Log(level LogLevel, message string) {
	if f.log == nil {
		return
	}
	if u := utf16.Encode([]rune(message)); len(u) > maxLogLength {
		message = string(utf16.Decode(u[:maxLogLength]))
	}
	f.log(level, message)
}

// Receivers holds the handlers an application registers for the platform's deliveries. Register
// them in the build function, before [Platform.Routes] seals the registry.
type Receivers struct {
	mu            sync.Mutex
	sealed        bool
	dispatchExact map[string]*dispatchHandler
	dispatchFam   map[string]*dispatchHandler
	jobs          map[string]*jobHandler
}

func newReceivers() *Receivers {
	return &Receivers{dispatchExact: map[string]*dispatchHandler{}, dispatchFam: map[string]*dispatchHandler{}, jobs: map[string]*jobHandler{}}
}

// ErrSealed is returned when a handler is registered after the receivers were opened.
var ErrSealed = errors.New("receivers cannot be registered after start-up; register in the build function, before Routes")

func (r *Receivers) seal() {
	r.mu.Lock()
	r.sealed = true
	r.mu.Unlock()
}

// OnDispatch registers a typed dispatch handler for an event type pattern: an exact type, a family
// ending in ":*" on a segment boundary ("orders:*" does not match "orders"), or "*". The most
// specific wins; a duplicate is an error. The delivery's data is decoded into a T before the
// handler runs; data that does not decode answers Reject (the payload can never succeed). Use
// jsontext.Value as T for the raw JSON.
func OnDispatch[T any](r *Receivers, pattern string, h func(ctx context.Context, cx fw.Context, d Delivery[T]) (DispatchResult, error)) error {
	return r.registerDispatch(pattern, &dispatchHandler{run: func(ctx context.Context, cx fw.Context, meta DeliveryMeta, data jsontext.Value, hasData bool) (DispatchResult, error) {
		d := Delivery[T]{Meta: meta, HasData: hasData}
		if hasData {
			if err := json.Unmarshal(data, &d.Data); err != nil {
				return Reject{Reason: "the data of " + meta.EventType + " does not decode into " + typeName[T]()}, nil
			}
		}
		return h(ctx, cx, d)
	}})
}

// OnDispatchRaw registers an untyped dispatch handler: see [OnDispatch].
func (r *Receivers) OnDispatchRaw(pattern string, h DispatchFunc) error {
	return OnDispatch(r, pattern, h)
}

type dispatchHandler struct {
	run func(ctx context.Context, cx fw.Context, meta DeliveryMeta, data jsontext.Value, hasData bool) (DispatchResult, error)
}

func typeName[T any]() string {
	var zero T
	return fmt.Sprintf("%T", zero)
}

func (r *Receivers) registerDispatch(pattern string, h *dispatchHandler) error {
	family := pattern == "*" || strings.HasSuffix(pattern, ":*")
	key := pattern
	if family {
		key = pattern[:max(0, len(pattern)-2)]
	}
	if pattern != "*" {
		for seg := range strings.SplitSeq(key, ":") {
			if !segmentRe.MatchString(seg) {
				return fmt.Errorf("dispatch handler pattern '%s' is malformed: use a type, a family ending in ':*', or '*'", clean(pattern, 200))
			}
		}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.sealed {
		return ErrSealed
	}
	target := r.dispatchExact
	if family {
		target = r.dispatchFam
	}
	if _, dup := target[key]; dup {
		return fmt.Errorf("a dispatch handler for '%s' is already registered", pattern)
	}
	target[key] = h
	return nil
}

// OnScheduledJob registers the handler of a scheduled job by its code ([A-Za-z0-9._:-], at most
// 186 characters, so that scheduled-job:{code} is a valid lock name). A duplicate is an error. A
// handler that is not [JobOptions.AlwaysConcurrent] takes the overlap lock scheduled-job:{code}
// (service scope, lease flowcatalyst.receivers.job-lock-lease, refreshed while the job runs) from
// the default lock store, so [Build] needs one.
func (r *Receivers) OnScheduledJob(code string, opts JobOptions, h func(ctx context.Context, cx fw.Context, f *Firing) (JobResult, error)) error {
	if !jobCodeRe.MatchString(code) {
		return fmt.Errorf("scheduled job code '%s' is malformed: use [A-Za-z0-9._:-], at most 186 characters", clean(code, 200))
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.sealed {
		return ErrSealed
	}
	if _, dup := r.jobs[code]; dup {
		return fmt.Errorf("a handler for scheduled job '%s' is already registered", code)
	}
	r.jobs[code] = &jobHandler{opts: opts, run: h}
	return nil
}

type jobHandler struct {
	opts JobOptions
	run  func(ctx context.Context, cx fw.Context, f *Firing) (JobResult, error)
}

func (r *Receivers) dispatchHandler(eventType string) *dispatchHandler {
	r.mu.Lock()
	defer r.mu.Unlock()
	if h, ok := r.dispatchExact[eventType]; ok {
		return h
	}
	prefix := eventType
	for {
		i := strings.LastIndexByte(prefix, ':')
		if i <= 0 {
			break
		}
		prefix = prefix[:i]
		if h, ok := r.dispatchFam[prefix]; ok {
			return h
		}
	}
	return r.dispatchFam[""]
}

func (r *Receivers) jobHandler(code string) *jobHandler {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.jobs[code]
}

func (r *Receivers) hasDispatch() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.dispatchExact)+len(r.dispatchFam) > 0
}

func (r *Receivers) hasJobs() bool {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.jobs) > 0
}

func (r *Receivers) exclusiveJobCodes() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for code, h := range r.jobs {
		if !h.opts.AlwaysConcurrent {
			out = append(out, code)
		}
	}
	return sorted(out)
}

func (r *Receivers) jobCodes() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]string, 0, len(r.jobs))
	for code := range r.jobs {
		out = append(out, code)
	}
	return sorted(out)
}

func (r *Receivers) dispatchPatterns() []string {
	r.mu.Lock()
	defer r.mu.Unlock()
	var out []string
	for k := range r.dispatchExact {
		out = append(out, k)
	}
	for k := range r.dispatchFam {
		if k == "" {
			out = append(out, "*")
		} else {
			out = append(out, k+":*")
		}
	}
	return sorted(out)
}

// clean makes text safe for a log line or a response message: control, format, line and paragraph
// separator characters become spaces, and it is cut to max UTF-16 code units (as Java counts
// characters) without splitting a surrogate pair.
func clean(text string, maxChars int) string {
	units := utf16.Encode([]rune(text))
	if len(units) > maxChars {
		end := maxChars
		if end > 0 && utf16.IsSurrogate(rune(units[end-1])) && units[end-1] < 0xDC00 {
			end-- // do not end on a high surrogate
		}
		units = units[:end]
	}
	// Java checks each UTF-16 unit: a surrogate is never "unsafe", so supplementary characters stay
	for i, u := range units {
		if !utf16.IsSurrogate(rune(u)) && unicode.In(rune(u), unicode.Cc, unicode.Cf, unicode.Zl, unicode.Zp) {
			units[i] = ' '
		}
	}
	return string(utf16.Decode(units))
}

func sorted(s []string) []string {
	out := append([]string(nil), s...)
	slices.Sort(out)
	return out
}
