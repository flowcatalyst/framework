package flowcatalyst_test

import (
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"net/http"
	"net/http/httptest"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/locks"
)

type wantFiring struct {
	JobID               string         `json:"jobId"`
	JobCode             string         `json:"jobCode"`
	InstanceID          string         `json:"instanceId"`
	ScheduledFor        *string        `json:"scheduledFor"`
	FiredAt             string         `json:"firedAt"`
	TriggerKind         string         `json:"triggerKind"`
	Payload             jsontext.Value `json:"payload"`
	TracksCompletion    bool           `json:"tracksCompletion"`
	TimeoutSeconds      *int           `json:"timeoutSeconds"`
	Concurrent          bool           `json:"concurrent"`
	FencingTokenPresent bool           `json:"fencingTokenPresent"`
	ContextClient       string         `json:"contextClient"`
	ExecutionID         string         `json:"executionId"`
	CausationID         string         `json:"causationId"`
	CorrelationID       string         `json:"correlationId"`
}

type firingSeen struct {
	f      flowcatalyst.Firing
	token  bool
	client string
	exec   string
	cause  string
}

// TestScheduledJobVectors runs conformance/receivers/scheduled-jobs.json (Java:
// ScheduledJobsVectorConformanceTest): POST /fc/scheduled-jobs, the firing the handler gets, the
// overlap lock states and the completion calls the platform receives.
func TestScheduledJobVectors(t *testing.T) {
	var v struct {
		Cases []struct {
			Name               string            `json:"name"`
			Handler            string            `json:"handler"`
			Arg                string            `json:"arg"`
			Options            string            `json:"options"`
			Lock               string            `json:"lock"`
			Env                map[string]string `json:"env"`
			CompletionFailures int               `json:"completionFailures"`
			CompletionCalls    int               `json:"completionCalls"`
			Sign               bool              `json:"sign"`
			Envelope           jsontext.Value    `json:"envelope"`
			RawBody            *string           `json:"rawBody"`
			Expect             struct {
				Status      int            `json:"status"`
				Body        jsontext.Value `json:"body"`
				HandlerRuns int            `json:"handlerRuns"`
				Firing      *wantFiring    `json:"firing"`
				Completions []struct {
					Path string         `json:"path"`
					Body jsontext.Value `json:"body"`
				} `json:"completions"`
			} `json:"expect"`
		} `json:"cases"`
	}
	conformance.ReadJSON(t, "receivers/scheduled-jobs.json", &v)
	if len(v.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range v.Cases {
		t.Run(c.Name, func(t *testing.T) {
			seen := &recorderOf[firingSeen]{}
			started := make(chan struct{}, 4)
			handler := func(ctx context.Context, cx fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
				_, hasToken := f.FencingToken()
				cause, _ := cx.Causation()
				exec, _ := cx.Execution()
				seen.add(firingSeen{*f, hasToken, cx.Client().String(), exec.String(), cause.String()})
				started <- struct{}{}
				switch c.Handler {
				case "success":
					return flowcatalyst.Success{}, nil
				case "result":
					var val any
					if err := json.Unmarshal([]byte(c.Arg), &val); err != nil {
						t.Error(err)
					}
					return flowcatalyst.Success{Value: val}, nil
				case "failure":
					return flowcatalyst.Failure{Message: c.Arg}, nil
				case "throw":
					return nil, errors.New(c.Arg)
				case "throwBlank":
					return nil, errors.New("")
				case "null":
					return nil, nil
				case "hang":
					<-ctx.Done()
					return nil, ctx.Err()
				case "longString":
					n, _ := strconv.Atoi(c.Arg)
					return flowcatalyst.Success{Value: strings.Repeat("x", n)}, nil
				case "unserialisable":
					return flowcatalyst.Success{Value: make(chan int)}, nil
				}
				t.Errorf("unknown handler kind %q", c.Handler)
				return nil, nil
			}
			opts := flowcatalyst.JobOptions{AlwaysConcurrent: c.Options == "alwaysConcurrent"}
			ro := rigOptions{env: c.Env}
			clk := fwtest.NewClock(testNow)
			var store *locks.MemoryStore
			switch c.Lock {
			case "free":
			case "held", "lost":
				var factory locks.StoreFactory
				store, factory = sharedStore(clk)
				ro.clock, ro.lockFactory = clk, factory
				if c.Lock == "held" {
					probe := locks.New(store, locks.WithClock(clk))
					if _, err := probe.TryAcquire(t.Context(), locks.MustServiceKey("scheduled-job:"+jobCode), 24*time.Hour); err != nil {
						t.Fatal(err)
					}
				}
			case "down":
				ds := &downStore{Store: locks.NewMemoryStore(clk)}
				ds.down.Store(true)
				ro.lockFactory = func(context.Context, *fw.Boot) (locks.Store, error) { return ds, nil }
			case "none":
				ro.noLocks = true
			default:
				t.Fatalf("unknown lock state %q", c.Lock)
			}
			ro.platform = newFakePlatform(t)
			if c.CompletionFailures > 0 {
				ro.platform.failInstanceCalls("complete", http.StatusInternalServerError, c.CompletionFailures)
			}
			r := jobRig(t, handler, opts, ro)
			body := string(c.Envelope)
			if c.RawBody != nil {
				body = *c.RawBody
			}
			req := httptest.NewRequest(http.MethodPost, "/fc/scheduled-jobs", strings.NewReader(body))
			hd := signedHeaders(body, nil)
			if !c.Sign {
				hd = map[string]string{}
			}
			for k, val := range hd {
				req.Header.Set(k, val)
			}
			got := r.postRequest(req)
			e := c.Expect
			if got.status != e.Status {
				t.Fatalf("status %d, want %d: %s", got.status, e.Status, got.body)
			}
			compareBody(t, e.Status, e.Body, got.body)
			if c.Lock == "lost" {
				<-started
				store.ExpireNow("svc:scheduled-job:" + jobCode)
				deadline := time.Now().Add(10 * time.Second)
				for len(r.platform.calls("complete")) < c.CompletionCalls && time.Now().Before(deadline) {
					clk.Advance(time.Second)
					time.Sleep(20 * time.Millisecond)
				}
			}
			if c.CompletionCalls > 0 {
				waitFor(t, "completion calls", func() bool { return len(r.platform.calls("complete")) >= c.CompletionCalls })
			} else {
				time.Sleep(300 * time.Millisecond)
			}
			calls := r.platform.calls("complete")
			if len(calls) != len(e.Completions) {
				t.Fatalf("%d completion calls, want %d: %v", len(calls), len(e.Completions), calls)
			}
			for i, w := range e.Completions {
				if calls[i].path != w.Path {
					t.Errorf("completion %d path %s, want %s", i, calls[i].path, w.Path)
				}
				if c.Handler == "throwBlank" {
					// recorded difference: Java names the exception class (IllegalStateException), Go the
					// error's type (*errors.errorString); both are the type name of a message-less error
					var m struct {
						Status string `json:"status"`
						Result struct {
							Error string `json:"error"`
						} `json:"result"`
					}
					if err := json.Unmarshal([]byte(calls[i].body), &m); err != nil || m.Status != "FAILURE" || m.Result.Error == "" {
						t.Errorf("completion %d: %s, want a FAILURE naming the error type", i, calls[i].body)
					}
					continue
				}
				conformance.EqualJSON(t, "completion "+strconv.Itoa(i), w.Body, []byte(calls[i].body))
			}
			runs := seen.get()
			if len(runs) != e.HandlerRuns {
				t.Fatalf("handler ran %d times, want %d", len(runs), e.HandlerRuns)
			}
			if e.Firing != nil && len(runs) > 0 {
				e.Firing.check(t, runs[0], body)
			}
		})
	}
}

func (w *wantFiring) check(t *testing.T, s firingSeen, body string) {
	t.Helper()
	f := s.f
	if f.JobID != w.JobID || f.JobCode != w.JobCode || f.InstanceID != w.InstanceID || f.Trigger != w.TriggerKind ||
		f.TracksCompletion != w.TracksCompletion || f.Concurrent != w.Concurrent {
		t.Errorf("firing %+v, want %+v", f, w)
	}
	sameTime := func(name, want string, got time.Time) {
		wt, err := time.Parse(time.RFC3339, want)
		if err != nil || !wt.Equal(got) {
			t.Errorf("%s %v, want %s", name, got, want)
		}
	}
	sameTime("firedAt", w.FiredAt, f.FiredAt)
	switch {
	case w.ScheduledFor == nil && !f.ScheduledFor.IsZero():
		t.Errorf("scheduledFor %v, want none", f.ScheduledFor)
	case w.ScheduledFor != nil:
		sameTime("scheduledFor", *w.ScheduledFor, f.ScheduledFor)
	}
	switch {
	case w.TimeoutSeconds == nil && f.Timeout != 0:
		t.Errorf("timeout %v, want none", f.Timeout)
	case w.TimeoutSeconds != nil && f.Timeout != time.Duration(*w.TimeoutSeconds)*time.Second:
		t.Errorf("timeout %v, want %ds", f.Timeout, *w.TimeoutSeconds)
	}
	switch {
	case len(w.Payload) == 0 || string(w.Payload) == "null":
		if len(f.Payload) != 0 {
			t.Errorf("payload %s, want none", f.Payload)
		}
	default:
		conformance.EqualJSON(t, "payload", w.Payload, f.Payload)
	}
	if s.token != w.FencingTokenPresent {
		t.Errorf("fencing token present %v, want %v", s.token, w.FencingTokenPresent)
	}
	if s.client != w.ContextClient || s.exec != w.ExecutionID || s.cause != w.CausationID {
		t.Errorf("context client/execution/causation %s/%s/%s, want %s/%s/%s", s.client, s.exec, s.cause, w.ContextClient, w.ExecutionID, w.CausationID)
	}
	if w.CorrelationID == "<generated>" {
		if strings.TrimSpace(f.CorrelationID.String()) == "" || strings.Contains(body, `"correlationId":"`+f.CorrelationID.String()+`"`) {
			t.Errorf("correlation %q should be generated", f.CorrelationID)
		}
	} else if f.CorrelationID.String() != w.CorrelationID {
		t.Errorf("correlation %q, want %q", f.CorrelationID, w.CorrelationID)
	}
}
