package flowcatalyst_test

import (
	"context"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// vector is conformance/receivers/context.json, written by the Java test
// ReceiverContextConformanceTest: for a delivery and the token the platform issues to the
// application, the Context the receiver hands its handler, or the answer when it runs none.
type vector struct {
	Application   string `json:"application"`
	SigningSecret string `json:"signingSecret"`
	Now           string `json:"now"`
	Cases         []struct {
		Name     string `json:"name"`
		Receiver string `json:"receiver"`
		Token    struct {
			Claims              map[string]string `json:"claims"`
			Opaque              bool              `json:"opaque"`
			TokenEndpointStatus int               `json:"tokenEndpointStatus"`
		} `json:"token"`
		Request struct {
			Method  string            `json:"method"`
			Path    string            `json:"path"`
			Headers map[string]string `json:"headers"`
			Body    string            `json:"body"`
		} `json:"request"`
		Expect struct {
			Status  int    `json:"status"`
			Error   string `json:"error"`
			Message string `json:"message"`
			Context *struct {
				Client        string   `json:"client"`
				Principal     *string  `json:"principal"`
				PrincipalKind *string  `json:"principalKind"`
				Application   *string  `json:"application"`
				CorrelationID string   `json:"correlationId"`
				CausationID   *string  `json:"causationId"`
				ExecutionID   *string  `json:"executionId"`
				Permissions   []string `json:"permissions"`
			} `json:"context"`
		} `json:"expect"`
	} `json:"cases"`
}

const (
	vectorDispatchType = "orders:billing:invoice:created"
	vectorJobCode      = "nightly-export"
)

func TestReceiverContextVector(t *testing.T) {
	var v vector
	conformance.ReadJSON(t, "receivers/context.json", &v)
	if len(v.Cases) == 0 {
		t.Fatal("the vector has no cases")
	}
	now, err := time.Parse(time.RFC3339, v.Now)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range v.Cases {
		t.Run(c.Name, func(t *testing.T) {
			p := newFakePlatform(t)
			switch {
			case c.Token.TokenEndpointStatus != 0:
				p.failTokens(c.Token.TokenEndpointStatus)
			case c.Token.Opaque:
				p.setOpaque()
			default:
				p.setToken(c.Token.Claims["sub"], c.Token.Claims["type"])
			}
			seen := &recorderOf[fw.Context]{}
			r := startRig(t, rigOptions{
				platform: p,
				env:      map[string]string{"FLOWCATALYST_RECEIVERS_SIGNING_SECRET": v.SigningSecret, "FLOWCATALYST_APPLICATION": v.Application},
				register: func(fc *flowcatalyst.Platform) error {
					if err := flowcatalyst.OnDispatch(fc.Receivers(), vectorDispatchType,
						func(_ context.Context, cx fw.Context, _ flowcatalyst.Delivery[map[string]any]) (flowcatalyst.DispatchResult, error) {
							seen.add(cx)
							return flowcatalyst.Ack{}, nil
						}); err != nil {
						return err
					}
					return fc.Receivers().OnScheduledJob(vectorJobCode, flowcatalyst.JobOptions{AlwaysConcurrent: true},
						func(_ context.Context, cx fw.Context, _ *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
							seen.add(cx)
							return flowcatalyst.Success{}, nil
						})
				},
			})
			r.clock.Set(now)
			// the vector's signatures are Java's: this package computes the same ones
			ts := c.Request.Headers["X-FlowCatalyst-Timestamp"]
			if got, want := signature(v.SigningSecret, ts, c.Request.Body), c.Request.Headers["X-FlowCatalyst-Signature"]; got != want {
				t.Fatalf("signature %s, the vector has %s", got, want)
			}
			resp := r.post(c.Request.Path, c.Request.Body, c.Request.Headers)
			if resp.status != c.Expect.Status {
				t.Fatalf("status %d, want %d (%s)", resp.status, c.Expect.Status, resp.body)
			}
			if c.Expect.Status >= 400 {
				env := parseEnvelope(t, resp.body)
				if env["error"] != c.Expect.Error || env["message"] != c.Expect.Message {
					t.Fatalf("answer %v, want %s: %s", env, c.Expect.Error, c.Expect.Message)
				}
			}
			if c.Expect.Context == nil {
				time.Sleep(50 * time.Millisecond) // a handler that wrongly ran would have by now
				if got := seen.get(); len(got) != 0 {
					t.Fatalf("a handler ran: %v", got[0].LogAttrs())
				}
				return
			}
			waitFor(t, "the handler", func() bool { return len(seen.get()) > 0 })
			cx := seen.get()[0]
			want := c.Expect.Context
			if cx.Client().String() != want.Client {
				t.Errorf("client %s, want %s", cx.Client(), want.Client)
			}
			if p, ok := cx.Principal(); !ok || want.Principal == nil || p.String() != *want.Principal {
				t.Errorf("principal %v, want %v", p, want.Principal)
			}
			if k, ok := cx.PrincipalKind(); !ok || want.PrincipalKind == nil || k.String() != *want.PrincipalKind {
				t.Errorf("principal kind %v, want %v", k, want.PrincipalKind)
			}
			if a, ok := cx.Application(); !ok || want.Application == nil || a.String() != *want.Application {
				t.Errorf("application %v, want %v", a, want.Application)
			}
			if c, ok := cx.Causation(); !ok || want.CausationID == nil || c.String() != *want.CausationID {
				t.Errorf("causation id %v, want %v", c, want.CausationID)
			}
			if e, ok := cx.Execution(); !ok || want.ExecutionID == nil || e.String() != *want.ExecutionID {
				t.Errorf("execution id %v, want %v", e, want.ExecutionID)
			}
			if got := cx.Correlation().String(); want.CorrelationID == "<generated>" {
				if strings.TrimSpace(got) == "" || strings.Contains(c.Request.Body, `"correlationId":"`+got+`"`) {
					t.Errorf("correlation id %q should be generated, not the envelope's", got)
				}
			} else if got != want.CorrelationID {
				t.Errorf("correlation id %q, want %q", got, want.CorrelationID)
			}
			if perms := cx.Permissions(); len(perms) != len(want.Permissions) {
				t.Errorf("permissions %v, want %v", perms, want.Permissions)
			}
		})
	}
}
