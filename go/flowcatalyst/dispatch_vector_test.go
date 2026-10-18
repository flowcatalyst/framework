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
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// TestDispatchVectors runs conformance/receivers/dispatch.json (Java: DispatchVectorConformanceTest):
// POST /fc/dispatch for a registered handler outcome, compared on status, outcome header, body,
// the handler that ran, what it was handed and the client in its Context.
func TestDispatchVectors(t *testing.T) {
	var v struct {
		DispatchTimeoutMs int `json:"dispatchTimeoutMs"`
		Cases             []struct {
			Name     string `json:"name"`
			Handlers []struct {
				Pattern string `json:"pattern"`
				Kind    string `json:"kind"`
				Arg     string `json:"arg"`
			} `json:"handlers"`
			Ready   bool        `json:"ready"`
			Headers [][2]string `json:"headers"`
			Body    string      `json:"body"`
			Expect  struct {
				Status   int            `json:"status"`
				Outcome  *string        `json:"outcome"`
				Body     jsontext.Value `json:"body"`
				Handler  *string        `json:"handler"`
				Delivery *wantDelivery  `json:"delivery"`
				Context  *string        `json:"contextClient"`
			} `json:"expect"`
		} `json:"cases"`
	}
	conformance.ReadJSON(t, "receivers/dispatch.json", &v)
	if len(v.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range v.Cases {
		t.Run(c.Name, func(t *testing.T) {
			type ran struct {
				pattern string
				d       flowcatalyst.Delivery[jsontext.Value]
				client  string
			}
			seen := &recorderOf[ran]{}
			do := func(r *rig) reply {
				req := httptest.NewRequest(http.MethodPost, "/fc/dispatch", strings.NewReader(c.Body))
				for _, h := range c.Headers {
					req.Header.Add(h[0], h[1])
				}
				return r.postRequest(req)
			}
			var notReady *reply
			var rg *rig
			register := func(fc *flowcatalyst.Platform) error {
				for _, h := range c.Handlers {
					pattern, kind, arg := h.Pattern, h.Kind, h.Arg
					err := fc.Receivers().OnDispatchRaw(pattern, func(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[jsontext.Value]) (flowcatalyst.DispatchResult, error) {
						seen.add(ran{pattern, d, cx.Client().String()})
						switch kind {
						case "ack":
							return flowcatalyst.Ack{}, nil
						case "defer":
							ms, _ := strconv.Atoi(arg)
							return flowcatalyst.Defer{Delay: time.Duration(ms) * time.Millisecond}, nil
						case "deferDefault":
							// Java's Defer.later() with no delay is Go's Defer{Delay: DefaultDefer} (a zero Delay is a real zero)
							return flowcatalyst.Defer{Delay: flowcatalyst.DefaultDefer}, nil
						case "reject":
							return flowcatalyst.Reject{Reason: arg}, nil
						case "throw", "throwChecked":
							return nil, errors.New(arg)
						case "hang":
							<-ctx.Done()
							return nil, ctx.Err()
						case "null":
							return nil, nil
						}
						t.Errorf("unknown handler kind %q", kind)
						return nil, nil
					})
					if err != nil {
						return err
					}
				}
				return nil
			}
			opts := rigOptions{
				env:      map[string]string{"FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT": strconv.Itoa(v.DispatchTimeoutMs) + "ms"},
				register: register,
			}
			if len(c.Handlers) == 0 {
				opts.noRoutes = true
			}
			if !c.Ready {
				// the handler built in the build function answers before start-up finished
				inner := register
				opts.register = func(fc *flowcatalyst.Platform) error {
					if err := inner(fc); err != nil {
						return err
					}
					routes, err := fc.Routes()
					if err != nil {
						return err
					}
					h := compileForTest(t, routes)
					req := httptest.NewRequest(http.MethodPost, "/fc/dispatch", strings.NewReader(c.Body))
					for _, hd := range c.Headers {
						req.Header.Add(hd[0], hd[1])
					}
					req.RemoteAddr = "192.0.2.1:1234"
					rec := httptest.NewRecorder()
					h.ServeHTTP(rec, req)
					notReady = &reply{rec.Code, rec.Result().Header, rec.Body.String()}
					return nil
				}
			}
			rg = startRig(t, opts)
			var got reply
			switch {
			case notReady != nil:
				got = *notReady
			case len(c.Handlers) == 0:
				// no handlers: nothing is mounted, so the dispatcher answers 404 itself
				routes := httpfwEmpty(t)
				h := compileForTest(t, routes)
				req := httptest.NewRequest(http.MethodPost, "/fc/dispatch", strings.NewReader(c.Body))
				rec := httptest.NewRecorder()
				h.ServeHTTP(rec, req)
				got = reply{rec.Code, rec.Result().Header, rec.Body.String()}
			default:
				got = do(rg)
			}
			e := c.Expect
			if got.status != e.Status {
				t.Fatalf("status %d, want %d: %s", got.status, e.Status, got.body)
			}
			outcome := got.header.Get("FlowCatalyst-Outcome")
			switch {
			case e.Outcome == nil && outcome != "":
				t.Errorf("outcome header %q, want none", outcome)
			case e.Outcome != nil && outcome != *e.Outcome:
				t.Errorf("outcome header %q, want %q", outcome, *e.Outcome)
			}
			compareBody(t, e.Status, e.Body, got.body)
			runs := seen.get()
			if e.Handler == nil {
				if len(runs) != 0 {
					t.Fatalf("handler %s ran, want none", runs[0].pattern)
				}
				return
			}
			if len(runs) != 1 || runs[0].pattern != *e.Handler {
				t.Fatalf("handlers that ran %v, want %s", runs, *e.Handler)
			}
			if e.Delivery != nil {
				e.Delivery.check(t, runs[0].d)
			}
			if e.Context != nil && runs[0].client != *e.Context {
				t.Errorf("context client %q, want %q", runs[0].client, *e.Context)
			}
		})
	}
}

// compareBody compares a response with the vector's body: as parsed JSON, except that the message
// of a 400 is Java's wording (only the error code is compared).
func compareBody(t *testing.T, status int, want jsontext.Value, got string) {
	t.Helper()
	if status == 400 {
		var w, g map[string]any
		if err := json.Unmarshal(want, &w); err != nil {
			t.Fatal(err)
		}
		if err := json.Unmarshal([]byte(got), &g); err != nil {
			t.Fatalf("body is not JSON: %s", got)
		}
		if w["error"] != g["error"] {
			t.Errorf("error %v, want %v (%s)", g["error"], w["error"], got)
		}
		return
	}
	conformance.EqualJSON(t, "body", want, []byte(got))
}

type wantDelivery struct {
	JobID         string         `json:"jobId"`
	EventType     string         `json:"eventType"`
	AttemptNumber *int           `json:"attemptNumber"`
	DataOnly      bool           `json:"dataOnly"`
	Source        *string        `json:"source"`
	Subject       *string        `json:"subject"`
	MessageGroup  *string        `json:"messageGroup"`
	Data          jsontext.Value `json:"data"`
	Client        *struct {
		ID   string `json:"id"`
		Code string `json:"code"`
	} `json:"client"`
	RawBody string `json:"rawBody"`
}

func (w *wantDelivery) check(t *testing.T, d flowcatalyst.Delivery[jsontext.Value]) {
	t.Helper()
	m := d.Meta
	if m.JobID != w.JobID || m.EventType != w.EventType || m.DataOnly != w.DataOnly || string(m.Raw) != w.RawBody {
		t.Errorf("delivery %+v, want %+v", m, w)
	}
	if (w.AttemptNumber == nil && m.Attempt != 0) || (w.AttemptNumber != nil && m.Attempt != *w.AttemptNumber) {
		t.Errorf("attempt %d, want %v", m.Attempt, w.AttemptNumber)
	}
	for _, p := range []struct {
		name string
		want *string
		got  string
	}{{"source", w.Source, m.Source}, {"subject", w.Subject, m.Subject}, {"messageGroup", w.MessageGroup, m.MessageGroup}} {
		if (p.want == nil && p.got != "") || (p.want != nil && p.got != *p.want) {
			t.Errorf("%s %q, want %v", p.name, p.got, p.want)
		}
	}
	switch {
	case w.Client == nil && !m.Client.IsZero():
		t.Errorf("client %+v, want none", m.Client)
	case w.Client != nil && (m.Client.ID != w.Client.ID || m.Client.Code.String() != w.Client.Code):
		t.Errorf("client %+v, want %+v", m.Client, w.Client)
	}
	wantData := string(w.Data)
	if wantData == "" {
		wantData = "null"
	}
	gotData := string(d.Data)
	if !d.HasData || gotData == "" {
		gotData = "null"
	}
	conformance.EqualJSON(t, "delivery data", []byte(wantData), []byte(gotData))
}

func httpfwEmpty(t *testing.T) *httpfw.Routes {
	t.Helper()
	return httpfw.NewRoutes("empty")
}
