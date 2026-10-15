package flowcatalyst

import (
	"bytes"
	"context"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"regexp"
	"runtime/debug"
	"strconv"
	"sync"
	"sync/atomic"
	"time"
	"unicode"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

const (
	jobIDHeader     = "X-Dispatch-Job-Id"
	eventTypeHeader = "X-Event-Type"
	clientHeader    = "X-FlowCatalyst-Client"
	outcomeHeader   = "FlowCatalyst-Outcome"
	noPrincipal     = "the application's service principal is not available"
)

var (
	jobIDRe       = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)
	eventTypeRe   = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,255}$`)
	clientRe      = regexp.MustCompile(`^([A-Za-z0-9._-]{1,100}):([A-Za-z0-9._-]{1,100})$`)
	correlationRe = regexp.MustCompile(`^[\x21-\x7E]{1,200}$`)
)

func unavailable(msg string) error {
	return httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", msg)
}

// receiverDeps is what both receivers share.
type receiverDeps struct {
	recv      *Receivers
	gate      gate
	app       fw.ApplicationCode
	principal func(ctx context.Context) (fw.PrincipalID, error)
	ready     *atomic.Bool
	log       *slog.Logger
	route     string
}

// refuse checks the signature and bearer gate; a refusal is the 401 envelope, logged with the
// failure code and the framework's correlation id, never a header value or a secret.
func (d *receiverDeps) refuse(r *http.Request, body func() ([]byte, error)) error {
	f, err := d.gate.verify(r.Header, body)
	if err != nil {
		return err
	}
	if f != "" {
		d.log.Warn(fmt.Sprintf("flowcatalyst: refused a delivery to %s: %s (correlation %s)", d.route, f, httpfw.CorrelationID(r)))
		return httpfw.NewError(http.StatusUnauthorized, "UNAUTHENTICATED", "the delivery could not be authenticated", httpfw.Detail{Key: "reason", Value: string(f)})
	}
	return nil
}

// bodyOnce reads the request body once, however often it is asked for.
func bodyOnce(r *http.Request) func() ([]byte, error) {
	var (
		once sync.Once
		b    []byte
		err  error
	)
	return func() ([]byte, error) {
		once.Do(func() { b, err = httpfw.ReadBody(r) })
		return b, err
	}
}

// dispatchReceiver is POST {prefix}/dispatch.
type dispatchReceiver struct {
	receiverDeps
	timeout  time.Duration
	inFlight *sync.WaitGroup
}

func (d *dispatchReceiver) handle(w http.ResponseWriter, r *http.Request) error {
	if !d.ready.Load() {
		return unavailable("the receivers are not ready")
	}
	body := bodyOnce(r)
	if err := d.refuse(r, body); err != nil {
		return err
	}
	jobID, okJob := single(r, jobIDHeader, jobIDRe)
	eventType, okType := single(r, eventTypeHeader, eventTypeRe)
	if !okJob || !okType {
		return httpfw.Validation("the " + jobIDHeader + " and " + eventTypeHeader + " headers are required and must be well formed")
	}
	var client DeliveryClient
	if vs := r.Header.Values(clientHeader); len(vs) > 0 {
		var m []string
		if len(vs) == 1 {
			m = clientRe.FindStringSubmatch(vs[0])
		}
		if m == nil {
			return httpfw.Validation("the " + clientHeader + " header must be {id}:{code}")
		}
		code, err := fw.ParseClientID(m[2])
		if err != nil {
			return httpfw.Validation("the " + clientHeader + " header must be {id}:{code}")
		}
		client = DeliveryClient{ID: m[1], Code: code}
	}
	raw, err := body() // the same bytes the gate verified, or (unsigned) read now: never re-read
	if err != nil {
		return err
	}
	meta, data, hasData, merr := parseDelivery(jobID, eventType, client, raw)
	if merr != "" {
		return httpfw.Validation("the dispatch envelope is malformed: " + merr)
	}
	h := d.recv.dispatchHandler(eventType)
	if h == nil {
		d.log.Warn(fmt.Sprintf("flowcatalyst: no dispatch handler for %s (job %s); rejected", eventType, jobID))
		return reject(w, Reject{Reason: "no handler for " + eventType})
	}
	// never run a handler without the application's service principal (owner ruling 2026-10-12)
	principal, err := d.principal(r.Context())
	if err != nil {
		d.log.Error(fmt.Sprintf("flowcatalyst: dispatch job %s (%s) was not run: %s (%v); answered 503, the platform retries", jobID, eventType, noPrincipal, err))
		return unavailable(noPrincipal)
	}
	clientID := PlatformClientID
	if !client.IsZero() {
		clientID = client.Code
	}
	cx, err := deliveryContext(clientID, principal, d.app, meta.CorrelationID, jobID)
	if err != nil {
		d.log.Error("flowcatalyst: " + err.Error())
		return unavailable("the handler failed")
	}
	return d.run(w, r, h, cx, meta, data, hasData)
}

func single(r *http.Request, name string, re *regexp.Regexp) (string, bool) {
	vs := r.Header.Values(name)
	if len(vs) != 1 || !re.MatchString(vs[0]) {
		return "", false
	}
	return vs[0], true
}

// deliveryContext is the context of a receiver run (flowcatalyst contract section 4b): the
// application's service principal acts; the delivered message (the dispatch job, or the
// scheduled-job instance) is the cause and the execution; no permissions.
func deliveryContext(client fw.ClientID, principal fw.PrincipalID, app fw.ApplicationCode, correlation fw.CorrelationID, causation string) (fw.Context, error) {
	cx, err := fw.NewContext(client, correlation)
	if err != nil {
		return fw.Context{}, err
	}
	cause, err := fw.ParseCausationID(causation)
	if err != nil {
		return fw.Context{}, err
	}
	exec, err := fw.ParseExecutionID(causation)
	if err != nil {
		return fw.Context{}, err
	}
	return cx.WithPrincipalOfKind(principal, fw.PrincipalService).WithApplication(app).WithCausation(cause).WithExecution(exec), nil
}

type handlerOutcome struct {
	result DispatchResult
	err    error
}

func (d *dispatchReceiver) run(w http.ResponseWriter, r *http.Request, h *dispatchHandler, cx fw.Context, meta DeliveryMeta, data jsontext.Value, hasData bool) error {
	// the handler is not cancelled when the platform hangs up: only its timeout ends it
	ctx, cancel := context.WithCancel(context.WithoutCancel(r.Context()))
	done := make(chan handlerOutcome, 1)
	d.inFlight.Go(func() {
		defer cancel()
		var out handlerOutcome
		func() {
			defer func() {
				if p := recover(); p != nil {
					d.log.Error(fmt.Sprintf("flowcatalyst: dispatch handler for %s panicked (job %s, correlation %s): %v\n%s", meta.EventType, meta.JobID, meta.CorrelationID, p, debug.Stack()))
					out = handlerOutcome{err: errors.New("the handler panicked")}
				}
			}()
			out.result, out.err = h.run(ctx, cx, meta, data, hasData)
		}()
		done <- out
	})
	// the deadline is a timer, not the context's: a handler that returns because its context was
	// cancelled must not be mistaken for one that finished in time
	timer := time.NewTimer(d.timeout)
	defer timer.Stop()
	select {
	case out := <-done:
		return d.respond(w, meta, out)
	case <-timer.C:
		cancel()
		d.log.Error(fmt.Sprintf("flowcatalyst: dispatch handler for %s did not finish within %v (job %s, correlation %s); cancelled it and answered 503, the platform retries",
			meta.EventType, d.timeout, meta.JobID, meta.CorrelationID))
		return unavailable("the handler did not finish in time")
	}
}

func (d *dispatchReceiver) respond(w http.ResponseWriter, meta DeliveryMeta, out handlerOutcome) error {
	fail := func(why string) error {
		d.log.Error(fmt.Sprintf("flowcatalyst: dispatch handler for %s %s (job %s, correlation %s); answered 503, the platform retries", meta.EventType, why, meta.JobID, meta.CorrelationID))
		return unavailable("the handler failed")
	}
	if out.err != nil {
		d.log.Error(fmt.Sprintf("flowcatalyst: dispatch handler for %s failed (job %s, correlation %s): %v; answered 503, the platform retries", meta.EventType, meta.JobID, meta.CorrelationID, out.err))
		return unavailable("the handler failed")
	}
	switch res := out.result.(type) {
	case Ack:
		httpfw.Bytes(w, http.StatusOK, "application/json", []byte(`{"ack":true}`))
		return nil
	case Defer:
		secs, ok := deferSeconds(res.Delay)
		if !ok {
			return fail("returned an invalid deferral (a negative delay or over 2^32-1 seconds)")
		}
		httpfw.Bytes(w, http.StatusOK, "application/json", []byte(`{"ack":false,"delaySeconds":`+strconv.FormatInt(secs, 10)+`}`))
		return nil
	case Reject:
		if isBlank(res.Reason) {
			return fail("rejected without a reason")
		}
		return reject(w, res)
	case nil:
		return fail("returned no result")
	}
	return fail("returned a result of an unknown type")
}

// isBlank is Java's String.isBlank: empty or only white space as Character.isWhitespace has it
// (not the no-break spaces U+00A0, U+2007 and U+202F).
func isBlank(s string) bool {
	for _, r := range s {
		switch {
		case r == '\u00A0' || r == '\u2007' || r == '\u202F':
			return false
		case r >= 0x1C && r <= 0x1F, r == ' ', r >= '\t' && r <= '\r':
		case unicode.In(r, unicode.Zs, unicode.Zl, unicode.Zp):
		default:
			return false
		}
	}
	return true
}

// deferSeconds is the delay rounded up to whole seconds, 0 to 2^32-1.
func deferSeconds(d time.Duration) (int64, bool) {
	if d < 0 {
		return 0, false
	}
	secs := int64(d / time.Second)
	if d%time.Second > 0 {
		secs++
	}
	return secs, secs <= maxDeferSeconds
}

// reject is the 422 REJECTED answer: the reason cleaned and cut to 500 characters, and the outcome
// header the platform reads.
func reject(w http.ResponseWriter, r Reject) error {
	w.Header().Set(outcomeHeader, "reject")
	return httpfw.NewError(http.StatusUnprocessableEntity, "REJECTED", clean(r.Reason, maxReasonLength))
}

// parseDelivery reads the body: the platform's envelope when it is a JSON object whose id and type
// equal the headers, else the raw payload (data-only). A body shaped like an envelope but with
// duplicate keys or a bad field is malformed (the message), never a data-only payload.
func parseDelivery(jobID, eventType string, client DeliveryClient, body []byte) (DeliveryMeta, jsontext.Value, bool, string) {
	meta := DeliveryMeta{JobID: jobID, EventType: eventType, Client: client, Raw: bytes.Clone(body)}
	var shape map[string]jsontext.Value
	lenient := json.Unmarshal(body, &shape, jsontext.AllowDuplicateNames(true)) == nil
	if !lenient || textOf(shape["id"]) != jobID || textOf(shape["type"]) != eventType {
		meta.DataOnly = true
		meta.CorrelationID = fw.NewCorrelationID()
		var data jsontext.Value
		if err := json.Unmarshal(body, &data); err != nil {
			return meta, nil, false, ""
		}
		return meta, data, true, ""
	}
	var root map[string]jsontext.Value
	if err := json.Unmarshal(body, &root); err != nil {
		return meta, nil, false, "it has duplicate keys"
	}
	attempt, ok := positiveInt(root["attemptNumber"])
	if !ok {
		return meta, nil, false, "attemptNumber must be a positive integer"
	}
	meta.Attempt = attempt
	var err string
	if meta.Source, err = optionalString(root, "source"); err != "" {
		return meta, nil, false, err
	}
	if meta.Subject, err = optionalString(root, "subject"); err != "" {
		return meta, nil, false, err
	}
	if meta.MessageGroup, err = optionalString(root, "messageGroup"); err != "" {
		return meta, nil, false, err
	}
	correlation, err := optionalString(root, "correlationId")
	if err != "" {
		return meta, nil, false, err
	}
	if _, err = optionalString(root, "clientId"); err != "" {
		return meta, nil, false, err
	}
	if _, err = optionalString(root, "clientCode"); err != "" {
		return meta, nil, false, err
	}
	meta.CorrelationID = correlationOf(correlation)
	data, hasData := root["data"]
	return meta, data, hasData, ""
}

func correlationOf(c string) fw.CorrelationID {
	if correlationRe.MatchString(c) {
		if id, err := fw.ParseCorrelationID(c); err == nil {
			return id
		}
	}
	return fw.NewCorrelationID()
}

// textOf is the string a JSON value holds, "" when it is not a string.
func textOf(v jsontext.Value) string {
	if v.Kind() != '"' {
		return ""
	}
	var s string
	if json.Unmarshal(v, &s) != nil {
		return ""
	}
	return s
}

// positiveInt reads a JSON integer (no fraction or exponent) from 1 to 2^31-1.
func positiveInt(v jsontext.Value) (int, bool) {
	if v.Kind() != '0' {
		return 0, false
	}
	n, err := strconv.ParseInt(string(v), 10, 32)
	if err != nil || n < 1 {
		return 0, false
	}
	return int(n), true
}

// optionalString reads an optional string member: absent or null is "", anything else not a string
// is the error.
func optionalString(root map[string]jsontext.Value, field string) (string, string) {
	v, present := root[field]
	if !present || v.Kind() == 'n' {
		return "", ""
	}
	if v.Kind() != '"' {
		return "", field + " must be a string"
	}
	return textOf(v), ""
}
