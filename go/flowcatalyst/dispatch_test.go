package flowcatalyst_test

import (
	"context"
	"encoding/json/jsontext"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
)

const (
	evt      = "orders:billing:invoice:created"
	envelope = `{"id":"dj_0HZXEQ5Y8JY5Z","type":"orders:billing:invoice:created","attemptNumber":3,"source":"orders:billing","subject":"billing.invoice.9","messageGroup":"billing:invoice:9","correlationId":"corr-1","clientId":"clt_1","clientCode":"acme","data":{"n":1}}`
)

type invoice struct {
	N int `json:"n"`
}

type fn = func(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error)

// dispatchRig starts a rig whose only dispatch handler, for evt, is h.
func dispatchRig(t *testing.T, h fn, o rigOptions) *rig {
	t.Helper()
	o.register = func(fc *flowcatalyst.Platform) error { return flowcatalyst.OnDispatch(fc.Receivers(), evt, h) }
	return startRig(t, o)
}

func ack(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
	return flowcatalyst.Ack{}, nil
}

func (r *rig) dispatch(body string, extra map[string]string) reply {
	r.t.Helper()
	return r.post("/fc/dispatch", body, dispatchHeaders(body, evt, extra))
}

func result(res flowcatalyst.DispatchResult) fn {
	return func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		return res, nil
	}
}

func TestAckIs200WithAckTrue(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	got := r.dispatch(envelope, nil)
	if got.status != 200 || got.body != `{"ack":true}` {
		t.Fatalf("%d %s", got.status, got.body)
	}
}

func TestDeferIs200WithAckFalseAndTheDelayRoundedUp(t *testing.T) {
	for _, c := range []struct {
		delay time.Duration
		want  string
	}{
		{flowcatalyst.DefaultDefer, `{"ack":false,"delaySeconds":30}`},
		{0, `{"ack":false,"delaySeconds":0}`},
		{time.Millisecond, `{"ack":false,"delaySeconds":1}`},
		{1500 * time.Millisecond, `{"ack":false,"delaySeconds":2}`},
		{2 * time.Second, `{"ack":false,"delaySeconds":2}`},
		{4294967295 * time.Second, `{"ack":false,"delaySeconds":4294967295}`},
	} {
		r := dispatchRig(t, result(flowcatalyst.Defer{Delay: c.delay}), rigOptions{})
		if got := r.dispatch(envelope, nil); got.status != 200 || got.body != c.want {
			t.Errorf("delay %v: %d %s, want %s", c.delay, got.status, got.body, c.want)
		}
	}
	for _, bad := range []time.Duration{-time.Second, 4294967296 * time.Second} {
		r := dispatchRig(t, result(flowcatalyst.Defer{Delay: bad}), rigOptions{})
		if got := r.dispatch(envelope, nil); got.status != 503 {
			t.Errorf("delay %v: %d, want 503", bad, got.status)
		}
	}
}

func TestRejectIs422WithTheOutcomeHeaderAndTheReason(t *testing.T) {
	r := dispatchRig(t, result(flowcatalyst.Reject{Reason: "unknown invoice"}), rigOptions{})
	got := r.dispatch(envelope, nil)
	if got.status != 422 || got.header.Get("FlowCatalyst-Outcome") != "reject" || got.body != `{"error":"REJECTED","message":"unknown invoice"}` {
		t.Fatalf("%d %v %s", got.status, got.header, got.body)
	}
}

func TestARejectReasonCannotSplitALineOrGrowWithoutBound(t *testing.T) {
	r := dispatchRig(t, result(flowcatalyst.Reject{Reason: "bad\r\nX-Evil: 1\u202e\u2028end"}), rigOptions{})
	got := parseEnvelope(t, r.dispatch(envelope, nil).body)
	if msg := got["message"].(string); strings.ContainsAny(msg, "\r\n\u202e\u2028") || msg != "bad  X-Evil: 1  end" {
		t.Fatalf("message %q", msg)
	}
	long := strings.Repeat("x", 600)
	r2 := dispatchRig(t, result(flowcatalyst.Reject{Reason: long}), rigOptions{})
	if msg := parseEnvelope(t, r2.dispatch(envelope, nil).body)["message"].(string); len(msg) != 500 {
		t.Fatalf("reason of %d characters, want it cut to 500", len(msg))
	}
	// the cut does not split a surrogate pair
	pairs := strings.Repeat("a", 499) + "\U0001F600"
	r3 := dispatchRig(t, result(flowcatalyst.Reject{Reason: pairs}), rigOptions{})
	msg := parseEnvelope(t, r3.dispatch(envelope, nil).body)["message"].(string)
	if msg != strings.Repeat("a", 499) {
		t.Fatalf("message %q: a surrogate pair was split or kept", msg)
	}
	// a reason of white space only is no reason: a handler error
	r4 := dispatchRig(t, result(flowcatalyst.Reject{Reason: " \t "}), rigOptions{})
	if got := r4.dispatch(envelope, nil); got.status != 503 {
		t.Fatalf("a blank reason: %d", got.status)
	}
}

func TestAFailingHandlerIs503AndTheAnswerCarriesNoneOfItsInternals(t *testing.T) {
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		return nil, errors.New("db password is hunter2")
	}, rigOptions{})
	got := r.dispatch(envelope, nil)
	if got.status != 503 || strings.Contains(got.body, "hunter2") || got.body != `{"error":"UNAVAILABLE","message":"the handler failed"}` {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if !r.logs.has("hunter2") || !r.logs.has("dj_0HZXEQ5Y8JY5Z") || !r.logs.has("corr-1") {
		t.Fatalf("the failure is logged with the job and correlation ids:\n%s", r.logs.all())
	}
}

func TestAPanickingHandlerIs503(t *testing.T) {
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		panic("boom hunter2")
	}, rigOptions{})
	got := r.dispatch(envelope, nil)
	if got.status != 503 || strings.Contains(got.body, "hunter2") {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if r.logs.has("hunter2") == false {
		t.Fatal("the panic is not logged")
	}
}

func TestANilResultIs503(t *testing.T) {
	r := dispatchRig(t, result(nil), rigOptions{})
	if got := r.dispatch(envelope, nil); got.status != 503 {
		t.Fatalf("%d", got.status)
	}
}

func TestAHandlerPastTheTimeoutIsCancelledAnd503(t *testing.T) {
	var cancelled atomic.Bool
	started := make(chan struct{})
	r := dispatchRig(t, func(ctx context.Context, _ fw.Context, _ flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		close(started)
		<-ctx.Done()
		cancelled.Store(true)
		return nil, ctx.Err()
	}, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT": "150ms"}})
	start := time.Now()
	got := r.dispatch(envelope, nil)
	if got.status != 503 || !strings.Contains(got.body, "did not finish in time") {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if time.Since(start) > 3*time.Second {
		t.Fatal("the answer waited for the handler")
	}
	<-started
	waitFor(t, "the handler's context to be cancelled", cancelled.Load)
}

func TestNoHandlerForTheTypeIsATerminalReject(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	body := strings.ReplaceAll(envelope, "invoice:created", "invoice:voided")
	got := r.post("/fc/dispatch", body, dispatchHeaders(body, "orders:billing:invoice:voided", nil))
	if got.status != 422 || got.header.Get("FlowCatalyst-Outcome") != "reject" || !strings.Contains(got.body, "no handler for orders:billing:invoice:voided") {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if !r.logs.has("no dispatch handler for orders:billing:invoice:voided") {
		t.Fatal("not logged")
	}
}

func TestABadSignatureIs401AndTheHandlerNeverRuns(t *testing.T) {
	var ran atomic.Bool
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		ran.Store(true)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	h := dispatchHeaders(envelope, evt, map[string]string{"X-FlowCatalyst-Signature": strings.Repeat("0", 64)})
	got := r.post("/fc/dispatch", envelope, h)
	want := `{"error":"UNAUTHENTICATED","message":"the delivery could not be authenticated","details":{"reason":"INVALID_SIGNATURE"}}`
	if got.status != 401 || got.body != want {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if ran.Load() {
		t.Fatal("the handler ran")
	}
	// the log has the code and the correlation id, never a header value
	if !r.logs.has("INVALID_SIGNATURE") || r.logs.has(strings.Repeat("0", 64)) || r.logs.has(testSecret) {
		t.Fatalf("log:\n%s", r.logs.all())
	}
}

func TestASignatureOverAReserialisedBodyIs401(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	reserialised := strings.ReplaceAll(envelope, `"data":{"n":1}`, `"data": {"n": 1}`)
	got := r.post("/fc/dispatch", reserialised, dispatchHeaders(envelope, evt, nil)) // signed over the compact form
	if got.status != 401 || !strings.Contains(got.body, "INVALID_SIGNATURE") {
		t.Fatalf("%d %s", got.status, got.body)
	}
}

func TestTheHandlerSeesExactlyTheBytesThatWereVerified(t *testing.T) {
	seen := &recorderOf[string]{}
	r := dispatchRig(t, func(_ context.Context, _ fw.Context, d flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		seen.add(string(d.Meta.Raw))
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	spaced := strings.ReplaceAll(envelope, `"data":{"n":1}`, "\"data\":\n {\"n\":  1}")
	if got := r.dispatch(spaced, nil); got.status != 200 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if got := seen.get(); len(got) != 1 || got[0] != spaced {
		t.Fatalf("raw body %q", got)
	}
}

func TestSignatureRefusals(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	for _, c := range []struct {
		name    string
		headers map[string]string
		reason  string
	}{
		{"unsigned", map[string]string{"X-FlowCatalyst-Signature": "", "X-FlowCatalyst-Timestamp": ""}, "MISSING_SIGNATURE"},
		{"no timestamp", map[string]string{"X-FlowCatalyst-Timestamp": ""}, "MISSING_TIMESTAMP"},
		{"an unreadable timestamp", map[string]string{"X-FlowCatalyst-Timestamp": "yesterday"}, "INVALID_TIMESTAMP"},
		{"expired", map[string]string{"X-FlowCatalyst-Timestamp": "2026-10-09T11:54:59.000Z"}, "TIMESTAMP_EXPIRED"},
		{"in the future", map[string]string{"X-FlowCatalyst-Timestamp": "2026-10-09T12:01:01.000Z"}, "TIMESTAMP_IN_FUTURE"},
		{"a short signature", map[string]string{"X-FlowCatalyst-Signature": "abcd"}, "INVALID_SIGNATURE"},
	} {
		h := dispatchHeaders(envelope, evt, nil)
		for k, v := range c.headers {
			if v == "" {
				delete(h, k)
			} else {
				h[k] = v
			}
		}
		got := r.post("/fc/dispatch", envelope, h)
		if got.status != 401 || !strings.Contains(got.body, `"reason":"`+c.reason+`"`) {
			t.Errorf("%s: %d %s", c.name, got.status, got.body)
		}
	}
}

func TestTheTimestampWindowEdges(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	ok := func(ts string) int {
		body := envelope
		h := dispatchHeaders(body, evt, map[string]string{"X-FlowCatalyst-Timestamp": ts, "X-FlowCatalyst-Signature": signature(testSecret, ts, body)})
		return r.post("/fc/dispatch", body, h).status
	}
	for ts, want := range map[string]int{
		"2026-10-09T11:55:00.000Z": 200, // exactly 300 s old
		"2026-10-09T11:55:00.999Z": 200,
		"2026-10-09T11:54:59.999Z": 401, // whole seconds, as every SDK compares: 301 old
		"2026-10-09T11:54:59Z":     401,
		"2026-10-09T12:01:00.000Z": 200, // exactly 60 s ahead
		"2026-10-09T12:01:01.000Z": 401,
		"1791547200":               200, // the same instant as Unix seconds
		"1791546900":               200, // 300 s old
		"1791546899":               401,
		"1791547260":               200,
		"1791547261":               401,
	} {
		if got := ok(ts); got != want {
			t.Errorf("timestamp %s: %d, want %d", ts, got, want)
		}
	}
	// a Unix timestamp is signed as written
	body := envelope
	h := dispatchHeaders(body, evt, map[string]string{"X-FlowCatalyst-Timestamp": "1791547200", "X-FlowCatalyst-Signature": signature(testSecret, testTS, body)})
	if got := r.post("/fc/dispatch", body, h); got.status != 401 {
		t.Errorf("a signature over another spelling of the instant: %d", got.status)
	}
	long := strings.Repeat("1", 65)
	if got := ok(long); got != 401 {
		t.Errorf("a 65-character timestamp: %d", got)
	}
}

func TestARepeatedSecurityHeaderIs401(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	req := func(name, value string) int {
		h := dispatchHeaders(envelope, evt, nil)
		hr, _ := http.NewRequest(http.MethodPost, "/fc/dispatch", strings.NewReader(envelope))
		for k, v := range h {
			hr.Header.Set(k, v)
		}
		hr.Header.Add(name, value)
		if name == "Authorization" {
			hr.Header.Add(name, value) // a header sent once is no repeat; the others are set above
		}
		return r.postRequest(hr).status
	}
	for _, name := range []string{"X-FlowCatalyst-Signature", "X-FlowCatalyst-Timestamp", "Authorization"} {
		if got := req(name, "x"); got != 401 {
			t.Errorf("%s twice: %d", name, got)
		}
	}
}

func TestUnsignedIsAcceptedWhenNotRequiredAndStartUpWarns(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE": "false", "FLOWCATALYST_RECEIVERS_SIGNING_SECRET": ""}})
	h := map[string]string{"X-Dispatch-Job-Id": "dj_1", "X-Event-Type": evt}
	if got := r.post("/fc/dispatch", envelope, h); got.status != 200 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if !r.logs.has("WARN flowcatalyst: flowcatalyst.receivers.require-signature is false") {
		t.Fatalf("no start-up warning:\n%s", r.logs.all())
	}
}

func TestWithoutASecretAndSignaturesNotRequiredEveryDeliveryIsAccepted(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE": "false", "FLOWCATALYST_RECEIVERS_SIGNING_SECRET": ""}})
	// signed with a secret nobody here knows, expired, and garbage: all accepted, nothing to verify against
	for _, h := range []map[string]string{
		dispatchHeaders(envelope, evt, nil),
		dispatchHeaders(envelope, evt, map[string]string{"X-FlowCatalyst-Timestamp": "2020-01-01T00:00:00.000Z"}),
		dispatchHeaders(envelope, evt, map[string]string{"X-FlowCatalyst-Signature": "garbage"}),
	} {
		if got := r.post("/fc/dispatch", envelope, h); got.status != 200 {
			t.Errorf("%v: %d %s", h, got.status, got.body)
		}
	}
}

func TestASignedDeliveryIsStillVerifiedWhenSignaturesAreNotRequired(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE": "false"}})
	bad := dispatchHeaders(envelope, evt, map[string]string{"X-FlowCatalyst-Signature": strings.Repeat("0", 64)})
	if got := r.post("/fc/dispatch", envelope, bad); got.status != 401 {
		t.Fatalf("a wrong signature: %d", got.status)
	}
	if got := r.dispatch(envelope, nil); got.status != 200 {
		t.Fatalf("a right signature: %d", got.status)
	}
	// an unsigned delivery with a secret configured is accepted (nothing claims a signature)
	if got := r.post("/fc/dispatch", envelope, map[string]string{"X-Dispatch-Job-Id": "dj_1", "X-Event-Type": evt}); got.status != 200 {
		t.Fatalf("unsigned: %d", got.status)
	}
}

func TestTheBearerGateIsEnforcedWhenConfigured(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_BEARER_TOKEN": "gate-token"}})
	for _, c := range []struct {
		auth   string
		status int
		reason string
	}{{"", 401, "MISSING_BEARER"}, {"Basic x", 401, "MISSING_BEARER"}, {"Bearer wrong", 401, "INVALID_BEARER"}, {"Bearer gate-token", 200, ""}} {
		extra := map[string]string{}
		if c.auth != "" {
			extra["Authorization"] = c.auth
		}
		got := r.dispatch(envelope, extra)
		if got.status != c.status || (c.reason != "" && !strings.Contains(got.body, c.reason)) {
			t.Errorf("Authorization %q: %d %s", c.auth, got.status, got.body)
		}
	}
	// the bearer gate applies to an unsigned delivery too
	r2 := dispatchRig(t, ack, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE": "false", "FLOWCATALYST_RECEIVERS_BEARER_TOKEN": "gate-token"}})
	if got := r2.post("/fc/dispatch", envelope, map[string]string{"X-Dispatch-Job-Id": "dj_1", "X-Event-Type": evt}); got.status != 401 {
		t.Fatalf("unsigned without the bearer: %d", got.status)
	}
}

func TestABodyOverTheLimitIsRefusedBeforeAnyHandler(t *testing.T) {
	var ran atomic.Bool
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		ran.Store(true)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	big := `{"id":"dj_0HZXEQ5Y8JY5Z","type":"` + evt + `","attemptNumber":1,"data":"` + strings.Repeat("x", 1<<20) + `"}`
	got := r.dispatch(big, nil)
	if got.status != 413 || ran.Load() {
		t.Fatalf("%d (handler ran: %v)", got.status, ran.Load())
	}
}

func TestTheDeliveryAndTheContextCarryTheEnvelopeAndTheClient(t *testing.T) {
	type seenT struct {
		cx fw.Context
		d  flowcatalyst.Delivery[invoice]
	}
	seen := &recorderOf[seenT]{}
	r := dispatchRig(t, func(_ context.Context, cx fw.Context, d flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		seen.add(seenT{cx, d})
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	if got := r.dispatch(envelope, map[string]string{"X-FlowCatalyst-Client": "clt_1:acme"}); got.status != 200 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	s := seen.get()[0]
	m := s.d.Meta
	if m.JobID != "dj_0HZXEQ5Y8JY5Z" || m.EventType != evt || m.Attempt != 3 || m.Source != "orders:billing" || m.Subject != "billing.invoice.9" ||
		m.MessageGroup != "billing:invoice:9" || m.CorrelationID.String() != "corr-1" || m.DataOnly || m.Client.ID != "clt_1" || m.Client.Code.String() != "acme" {
		t.Fatalf("meta %+v", m)
	}
	if s.d.Data.N != 1 || !s.d.HasData {
		t.Fatalf("data %+v", s.d)
	}
	cx := s.cx
	if cx.Client().String() != "acme" || cx.Correlation().String() != "corr-1" {
		t.Fatalf("context %v", cx.LogAttrs())
	}
	if k, _ := cx.PrincipalKind(); k != fw.PrincipalService {
		t.Fatalf("principal kind %v", k)
	}
	if p, _ := cx.Principal(); p.String() != servicePrincipal {
		t.Fatalf("principal %v", p)
	}
	if c, _ := cx.Causation(); c.String() != "dj_0HZXEQ5Y8JY5Z" {
		t.Fatalf("causation %v", c)
	}
}

func TestMalformedJobHeadersAre400AndCannotForgeLogLines(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	for _, c := range []struct {
		name string
		h    map[string]string
	}{
		{"no job id", map[string]string{"X-Dispatch-Job-Id": ""}},
		{"a job id with a space", map[string]string{"X-Dispatch-Job-Id": "dj 1"}},
		{"a job id of 129 characters", map[string]string{"X-Dispatch-Job-Id": strings.Repeat("a", 129)}},
		{"no event type", map[string]string{"X-Event-Type": ""}},
		{"an event type with a newline", map[string]string{"X-Event-Type": "a\nb"}},
		{"a bad client header", map[string]string{"X-FlowCatalyst-Client": "no-colon"}},
		{"a client header with two colons", map[string]string{"X-FlowCatalyst-Client": "a:b:c"}},
		{"a client header with a space", map[string]string{"X-FlowCatalyst-Client": "a b:c"}},
	} {
		h := dispatchHeaders(envelope, evt, nil)
		for k, v := range c.h {
			if v == "" {
				delete(h, k)
			} else {
				h[k] = v
			}
		}
		got := r.post("/fc/dispatch", envelope, h)
		if got.status != 400 || !strings.Contains(got.body, `"VALIDATION"`) {
			t.Errorf("%s: %d %s", c.name, got.status, got.body)
		}
	}
	if strings.Contains(r.logs.all(), "\nX-Evil") {
		t.Fatal("a header forged a log line")
	}
}

func TestARepeatedJobIDOrEventTypeHeaderIs400(t *testing.T) {
	r := dispatchRig(t, ack, rigOptions{})
	for _, name := range []string{"X-Dispatch-Job-Id", "X-Event-Type", "X-FlowCatalyst-Client"} {
		req, err := http.NewRequest(http.MethodPost, "/fc/dispatch", strings.NewReader(envelope))
		if err != nil || req == nil {
			t.Fatal(err)
		}
		for k, v := range dispatchHeaders(envelope, evt, map[string]string{"X-FlowCatalyst-Client": "clt_1:acme"}) {
			req.Header.Set(k, v)
		}
		req.Header.Add(name, req.Header.Get(name))
		if got := r.postRequest(req); got.status != 400 {
			t.Errorf("%s twice: %d %s", name, got.status, got.body)
		}
	}
}

func TestMalformedEnvelopesNeverReachAHandler(t *testing.T) {
	var ran atomic.Bool
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		ran.Store(true)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	head := `{"id":"dj_0HZXEQ5Y8JY5Z","type":"` + evt + `",`
	for name, body := range map[string]string{
		"no attemptNumber":         head + `"data":{}}`,
		"attemptNumber zero":       head + `"attemptNumber":0,"data":{}}`,
		"attemptNumber negative":   head + `"attemptNumber":-1,"data":{}}`,
		"attemptNumber a fraction": head + `"attemptNumber":1.5,"data":{}}`,
		"attemptNumber 1.0":        head + `"attemptNumber":1.0,"data":{}}`,
		"attemptNumber a string":   head + `"attemptNumber":"1","data":{}}`,
		"attemptNumber too large":  head + `"attemptNumber":2147483648,"data":{}}`,
		"source a number":          head + `"attemptNumber":1,"source":5,"data":{}}`,
		"subject an object":        head + `"attemptNumber":1,"subject":{},"data":{}}`,
		"messageGroup a list":      head + `"attemptNumber":1,"messageGroup":[],"data":{}}`,
		"correlationId a number":   head + `"attemptNumber":1,"correlationId":7,"data":{}}`,
		"clientId a boolean":       head + `"attemptNumber":1,"clientId":true,"data":{}}`,
		"clientCode a number":      head + `"attemptNumber":1,"clientCode":1,"data":{}}`,
		"duplicate keys":           head + `"attemptNumber":1,"attemptNumber":2,"data":{}}`,
	} {
		got := r.dispatch(body, nil)
		if got.status != 400 || !strings.Contains(got.body, "the dispatch envelope is malformed") {
			t.Errorf("%s: %d %s", name, got.status, got.body)
		}
	}
	if ran.Load() {
		t.Fatal("a handler ran")
	}
	// nulls for the optional members are fine
	ok := head + `"attemptNumber":1,"source":null,"subject":null,"messageGroup":null,"correlationId":null,"clientId":null,"clientCode":null,"data":null}`
	if got := r.dispatch(ok, nil); got.status != 200 {
		t.Fatalf("optional nulls: %d %s", got.status, got.body)
	}
}

func TestADataOnlyBodyIsTheRawPayload(t *testing.T) {
	seen := &recorderOf[flowcatalyst.Delivery[invoice]]{}
	r := dispatchRig(t, func(_ context.Context, _ fw.Context, d flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		seen.add(d)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	for _, body := range []string{`{"n":7}`, `{"id":"other","type":"` + evt + `","n":7}`, `{"id":"dj_0HZXEQ5Y8JY5Z","n":7}`} {
		if got := r.dispatch(body, nil); got.status != 200 {
			t.Fatalf("%s: %d %s", body, got.status, got.body)
		}
	}
	for i, d := range seen.get() {
		if !d.Meta.DataOnly || d.Meta.Attempt != 0 || d.Data.N != 7 || !d.HasData || d.Meta.CorrelationID.IsZero() {
			t.Errorf("delivery %d: %+v", i, d)
		}
	}
}

func TestADataOnlyBodyThatIsNotJSONHasNoData(t *testing.T) {
	seen := &recorderOf[flowcatalyst.Delivery[invoice]]{}
	r := dispatchRig(t, func(_ context.Context, _ fw.Context, d flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		seen.add(d)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	for _, body := range []string{"plain text", `{"n":1,"n":2}`, ""} {
		if got := r.dispatch(body, nil); got.status != 200 {
			t.Fatalf("%q: %d %s", body, got.status, got.body)
		}
	}
	for i, d := range seen.get() {
		if d.HasData || !d.Meta.DataOnly {
			t.Errorf("body %d: %+v", i, d)
		}
	}
}

func TestATypedHandlerWhoseDataDoesNotDecodeIsRejected(t *testing.T) {
	var ran atomic.Bool
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		ran.Store(true)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	body := strings.ReplaceAll(envelope, `"data":{"n":1}`, `"data":{"n":"not a number"}`)
	got := r.dispatch(body, nil)
	if got.status != 422 || got.header.Get("FlowCatalyst-Outcome") != "reject" || !strings.Contains(got.body, "does not decode") || strings.Contains(got.body, "not a number") {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if ran.Load() {
		t.Fatal("the handler ran")
	}
	// unknown members of the data are ignored: the publisher may add fields
	extra := strings.ReplaceAll(envelope, `"data":{"n":1}`, `"data":{"n":1,"later":true}`)
	if got := r.dispatch(extra, nil); got.status != 200 {
		t.Fatalf("unknown member: %d %s", got.status, got.body)
	}
}

func TestRawHandlersGetTheJSONAsReceived(t *testing.T) {
	seen := &recorderOf[string]{}
	r := startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		return fc.Receivers().OnDispatchRaw("orders:*", func(_ context.Context, _ fw.Context, d flowcatalyst.Delivery[jsontext.Value]) (flowcatalyst.DispatchResult, error) {
			seen.add(string(d.Data))
			return flowcatalyst.Ack{}, nil
		})
	}})
	if got := r.dispatch(envelope, nil); got.status != 200 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if got := seen.get(); len(got) != 1 || got[0] != `{"n":1}` {
		t.Fatalf("data %q", got)
	}
}

func TestTheMostSpecificPatternWins(t *testing.T) {
	var hit atomic.Value
	mark := func(name string) fn {
		return func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
			hit.Store(name)
			return flowcatalyst.Ack{}, nil
		}
	}
	r := startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		rcv := fc.Receivers()
		for pattern, name := range map[string]string{
			"*": "all", "orders:*": "orders", "orders:billing:*": "billing", "orders:billing:invoice:*": "invoice", evt: "exact",
		} {
			if err := flowcatalyst.OnDispatch(rcv, pattern, mark(name)); err != nil {
				return err
			}
		}
		return nil
	}})
	for typ, want := range map[string]string{
		evt: "exact", "orders:billing:invoice:voided": "invoice", "orders:billing:order:placed": "billing",
		"orders:sales:order:placed": "orders", "billing:x:y:z": "all", "orders": "all",
	} {
		body := `{"n":1}`
		if got := r.post("/fc/dispatch", body, dispatchHeaders(body, typ, nil)); got.status != 200 {
			t.Fatalf("%s: %d %s", typ, got.status, got.body)
		}
		if got := hit.Load(); got != want {
			t.Errorf("%s ran %v, want %s", typ, got, want)
		}
	}
}

func TestADuplicateOrMalformedRegistrationIsAnError(t *testing.T) {
	errs := make([]error, 0, 16)
	startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		rcv := fc.Receivers()
		must := func(pattern string) error { return flowcatalyst.OnDispatch(rcv, pattern, ack) }
		if err := must(evt); err != nil {
			return err
		}
		errs = append(errs, must(evt), must("orders:*"), must("orders:*"), must("*"), must("*"),
			must("bad pattern"), must("orders:"), must(":x"), must("orders:*:x"), must(""), must("a\nb"))
		return nil
	}})
	// positions: 0 dup exact (err), 1 first family (ok), 2 dup family (err), 3 first star (ok), 4 dup star (err), 5.. malformed (err)
	for i, shouldFail := range []bool{true, false, true, false, true, true, true, true, true, true, true} {
		failed := errs[i] != nil
		if failed != shouldFail {
			t.Errorf("registration %d: failed=%v, want failure=%v", i, failed, shouldFail)
		}
	}
	if err := errs[0]; err == nil || !strings.Contains(err.Error(), "already registered") {
		t.Errorf("duplicate: %v", err)
	}
	if err := errs[5]; err == nil || !strings.Contains(err.Error(), "malformed") {
		t.Errorf("malformed: %v", err)
	}
}

func TestRegisteringAfterRoutesIsRefused(t *testing.T) {
	r := startRig(t, rigOptions{})
	if err := flowcatalyst.OnDispatch(r.fc.Receivers(), evt, ack); !errors.Is(err, flowcatalyst.ErrSealed) {
		t.Fatalf("OnDispatch after Routes: %v", err)
	}
	if err := r.fc.Receivers().OnScheduledJob("x", flowcatalyst.JobOptions{}, nil); !errors.Is(err, flowcatalyst.ErrSealed) {
		t.Fatalf("OnScheduledJob after Routes: %v", err)
	}
}

func TestDeliveriesBeforeStartUpCompletesAre503(t *testing.T) {
	var during int
	startRig(t, rigOptions{noRoutes: true, register: func(fc *flowcatalyst.Platform) error {
		if err := flowcatalyst.OnDispatch(fc.Receivers(), evt, ack); err != nil {
			return err
		}
		r := &rig{t: t, platform: nil}
		routes, err := fc.Routes()
		if err != nil {
			return err
		}
		h := compileForTest(t, routes)
		r.handler = h
		during = r.post("/fc/dispatch", envelope, dispatchHeaders(envelope, evt, nil)).status
		return nil
	}})
	if during != 503 {
		t.Fatalf("before start-up completed: %d, want 503", during)
	}
}

func TestATokenForAUserRunsNoHandlerAndAnswers503(t *testing.T) {
	p := newFakePlatform(t)
	p.setToken("prn_0HUMAN", "USER")
	var ran atomic.Bool
	r := dispatchRig(t, func(context.Context, fw.Context, flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		ran.Store(true)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{platform: p})
	got := r.dispatch(envelope, nil)
	if got.status != 503 || got.body != `{"error":"UNAVAILABLE","message":"the application's service principal is not available"}` || ran.Load() {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if !r.logs.has("dj_0HZXEQ5Y8JY5Z") {
		t.Fatal("not logged with the job id")
	}
}

func TestRefusalsBeforeTheHandlerDoNotNeedThePrincipal(t *testing.T) {
	p := newFakePlatform(t)
	r := dispatchRig(t, ack, rigOptions{platform: p})
	p.failTokens(503)
	p.clear()
	bad := dispatchHeaders(envelope, evt, map[string]string{"X-FlowCatalyst-Signature": strings.Repeat("0", 64)})
	if got := r.post("/fc/dispatch", envelope, bad); got.status != 401 {
		t.Fatalf("401 expected: %d", got.status)
	}
	noHandler := r.post("/fc/dispatch", envelope, dispatchHeaders(envelope, "orders:other:x:y", nil))
	if noHandler.status != 422 {
		t.Fatalf("422 expected: %d", noHandler.status)
	}
	malformed := r.dispatch(`{"id":"dj_0HZXEQ5Y8JY5Z","type":"`+evt+`"}`, nil)
	if malformed.status != 400 {
		t.Fatalf("400 expected: %d", malformed.status)
	}
	if n := p.tokenRequests(); n != 0 {
		t.Fatalf("%d token requests: a refusal contacted the platform", n)
	}
}

func TestANewTokenBringsItsPrincipal(t *testing.T) {
	p := newFakePlatform(t)
	seen := &recorderOf[string]{}
	r := dispatchRig(t, func(_ context.Context, cx fw.Context, _ flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		pr, _ := cx.Principal()
		seen.add(pr.String())
		return flowcatalyst.Ack{}, nil
	}, rigOptions{platform: p})
	r.dispatch(envelope, nil)
	p.setToken("prn_0ROTATED", "SERVICE")
	r.fc.Client().ClearToken() // rotated credentials: the token is fetched again
	r.dispatch(envelope, nil)
	got := seen.get()
	if len(got) != 2 || got[0] != servicePrincipal || got[1] != "prn_0ROTATED" {
		t.Fatalf("principals %v", got)
	}
}

func TestAPlatformScopedJobHasThePlatformClientAndAGeneratedCorrelationID(t *testing.T) {
	seen := &recorderOf[fw.Context]{}
	r := dispatchRig(t, func(_ context.Context, cx fw.Context, _ flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		seen.add(cx)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	body := `{"n":1}`
	r.dispatch(body, nil)
	cx := seen.get()[0]
	if cx.Client() != flowcatalyst.PlatformClientID || cx.Client().String() != "_platform" || cx.Correlation().IsZero() {
		t.Fatalf("context %v", cx.LogAttrs())
	}
	if _, ok := fw.ParseClientID("_platform"); ok != nil {
		t.Fatal("_platform is not a ClientID")
	}
}

func TestACorrelationIDOfAnotherShapeIsReplaced(t *testing.T) {
	seen := &recorderOf[string]{}
	r := dispatchRig(t, func(_ context.Context, cx fw.Context, _ flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		seen.add(cx.Correlation().String())
		return flowcatalyst.Ack{}, nil
	}, rigOptions{})
	for _, c := range []struct {
		id        string
		kept      bool
		bodyValue string
	}{
		{"abc-123", true, `"abc-123"`},
		{strings.Repeat("a", 200), true, ""},
		{strings.Repeat("a", 201), false, ""},
		{"has space", false, `"has space"`},
		{"tab\\t", false, `"tab\t"`},
		{"\\u00e9", false, `"é"`},
	} {
		body := strings.ReplaceAll(envelope, `"correlationId":"corr-1"`, `"correlationId":"`+c.id+`"`)
		r.dispatch(body, nil)
		got := seen.get()
		last := got[len(got)-1]
		if c.kept && last != c.id {
			t.Errorf("%q: got %q, want it kept", c.id, last)
		}
		if !c.kept && (last == c.id || strings.TrimSpace(last) == "") {
			t.Errorf("%q: got %q, want a generated id", c.id, last)
		}
	}
}

func TestHandlersAreWaitedForAtShutdown(t *testing.T) {
	release := make(chan struct{})
	started := make(chan struct{})
	var finished atomic.Bool
	r := dispatchRig(t, func(ctx context.Context, _ fw.Context, _ flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
		close(started)
		<-release
		finished.Store(true)
		return flowcatalyst.Ack{}, nil
	}, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT": "100ms"}})
	if got := r.dispatch(envelope, nil); got.status != 503 { // timed out; the goroutine goes on
		t.Fatalf("%d", got.status)
	}
	<-started
	done := make(chan struct{})
	go func() { _ = r.run.Shutdown(); close(done) }()
	select {
	case <-done:
		t.Fatal("shutdown did not wait for the running handler")
	case <-time.After(300 * time.Millisecond):
	}
	close(release)
	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("shutdown never finished")
	}
	if !finished.Load() {
		t.Fatal("the handler was abandoned")
	}
}

var _ = fmt.Sprint
