package fakeplatform

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// What the platform sends an application's receivers, signed as the platform signs it, for the
// application's tests and launchers (flowcatalyst contract section 4): [Deliver] a dispatch job to
// POST {receivers}/dispatch and [Fire] a scheduled job at POST {receivers}/scheduled-jobs. Each
// builds the request; the test sends it. [JobFromOutbox] and [JobFromEvent] turn what the
// application wrote to its outbox into the job the platform would deliver.

// Signing is how a delivery is signed: the receiver's signing secret, the time of the timestamp
// header (read it from the application's clock, so the delivery is inside the receiver's
// tolerance), and the receiver's bearer token when it has a bearer gate ("" for none).
type Signing struct {
	Secret string
	At     time.Time
	Bearer string
}

// Signature is the X-FlowCatalyst-Signature of a body: hex(HMAC-SHA256(secret, timestamp + body)).
func Signature(secret, timestamp, body string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(timestamp))
	m.Write([]byte(body))
	return hex.EncodeToString(m.Sum(nil))
}

// Job is a dispatch job as the platform delivers it.
type Job struct {
	// ID is the dispatch job id (X-Dispatch-Job-Id and the envelope's id).
	ID string
	// Code is the job's code or event type (X-Event-Type and the envelope's type).
	Code string
	// Client is the identifier (code) of the job's client; "" for a platform-scoped job, which is
	// delivered without X-FlowCatalyst-Client, so its handler runs for the platform client.
	Client string
	// ClientID is the client's platform id; "" means "clt_" + Client.
	ClientID string
	// Source, Subject, CorrelationID and MessageGroup are the envelope's; "" leaves one out.
	Source, Subject, CorrelationID, MessageGroup string
	// Attempt is the attempt number; 0 means 1.
	Attempt int
	// Data is the payload: embedded in the envelope as JSON when it is JSON, else as a string. ""
	// sends no data member.
	Data string
	// ContentType is the payload's content type (the envelope's dataContentType); "" leaves it out.
	ContentType string
	// DataOnly sends Data as the whole body, without the envelope.
	DataOnly bool
}

// Body is what the platform posts for the job at a time: the envelope (its members sorted, as the
// platform writes them), or the raw data for a data-only job.
func (j Job) Body(at time.Time) (string, error) {
	if j.DataOnly {
		if j.Data == "" {
			return "{}", nil
		}
		return j.Data, nil
	}
	attempt := j.Attempt
	if attempt == 0 {
		attempt = 1
	}
	env := map[string]any{"id": j.ID, "type": j.Code, "attemptNumber": attempt, "time": at.UTC().Format("2006-01-02T15:04:05.000Z")}
	for k, v := range map[string]string{
		"source": j.Source, "subject": j.Subject, "correlationId": j.CorrelationID, "messageGroup": j.MessageGroup,
		"dataContentType": j.ContentType,
	} {
		if v != "" {
			env[k] = v
		}
	}
	if j.Client != "" {
		env["clientId"], env["clientCode"] = j.clientID(), j.Client
	}
	if j.Data != "" {
		if v := jsontext.Value(j.Data); v.IsValid() {
			env["data"] = v
		} else {
			env["data"] = j.Data
		}
	}
	b, err := json.Marshal(env, json.Deterministic(true))
	if err != nil {
		return "", err
	}
	return string(b), nil
}

func (j Job) clientID() string {
	if j.ClientID != "" {
		return j.ClientID
	}
	return "clt_" + j.Client
}

// Deliver builds the signed POST {receivers}/dispatch of a job, as the platform sends it: receivers
// is the application's address and receiver path (for example "http://127.0.0.1:8123/fc"). A job
// with a client carries X-FlowCatalyst-Client "{clientId}:{client}" (data-only included); one
// without carries neither the header nor the envelope's client.
func Deliver(ctx context.Context, receivers string, s Signing, j Job) (*http.Request, error) {
	if j.ID == "" || j.Code == "" {
		return nil, errors.New("fakeplatform.Deliver: a job needs an ID and a Code")
	}
	body, err := j.Body(s.At)
	if err != nil {
		return nil, err
	}
	req, err := signed(ctx, strings.TrimRight(receivers, "/")+"/dispatch", s, body)
	if err != nil {
		return nil, err
	}
	req.Header.Set("X-Dispatch-Job-Id", j.ID)
	req.Header.Set("X-Event-Type", j.Code)
	if j.Client != "" {
		req.Header.Set("X-FlowCatalyst-Client", j.clientID()+":"+j.Client)
	}
	return req, nil
}

// JobFromOutbox is the job the platform delivers for a DISPATCH_JOB row of the application's outbox
// (its payload, the platform's batch item): the item's id, code, source, subject, correlation id,
// message group, payload, content type and dataOnly, and its client. The client is the item's
// clientCode (the platform resolves it; here its id is "clt_" + the code); an item that names its
// client only by clientId gets that ClientID and no Client: set Client to the code the platform
// would resolve.
func JobFromOutbox(payload string) (Job, error) {
	var item struct {
		ID                 string `json:"id"`
		Code               string `json:"code"`
		Source             string `json:"source"`
		Subject            string `json:"subject"`
		Payload            string `json:"payload"`
		PayloadContentType string `json:"payloadContentType"`
		DataOnly           bool   `json:"dataOnly"`
		CorrelationID      string `json:"correlationId"`
		ClientID           string `json:"clientId"`
		ClientCode         string `json:"clientCode"`
		MessageGroup       string `json:"messageGroup"`
	}
	if err := json.Unmarshal([]byte(payload), &item, json.RejectUnknownMembers(false)); err != nil {
		return Job{}, fmt.Errorf("fakeplatform.JobFromOutbox: not a dispatch-job item: %w", err)
	}
	if item.ID == "" || item.Code == "" {
		return Job{}, errors.New("fakeplatform.JobFromOutbox: the item has no id or code")
	}
	return Job{
		ID: item.ID, Code: item.Code, Client: item.ClientCode, ClientID: item.ClientID,
		Source: item.Source, Subject: item.Subject, CorrelationID: item.CorrelationID, MessageGroup: item.MessageGroup,
		Data: item.Payload, ContentType: item.PayloadContentType, DataOnly: item.DataOnly,
	}, nil
}

// JobFromEvent is the job the platform delivers to a subscription for an EVENT row of the
// application's outbox (its payload): the job id is the platform's (pick one), the code the event
// type, the client the event's clientCode (none for the platform client, _platform), and the data
// the event's data.
func JobFromEvent(jobID, payload string) (Job, error) {
	var ev struct {
		Type          string `json:"type"`
		Source        string `json:"source"`
		Subject       string `json:"subject"`
		Data          string `json:"data"`
		CorrelationID string `json:"correlationId"`
		MessageGroup  string `json:"messageGroup"`
		ClientCode    string `json:"clientCode"`
	}
	if err := json.Unmarshal([]byte(payload), &ev, json.RejectUnknownMembers(false)); err != nil {
		return Job{}, fmt.Errorf("fakeplatform.JobFromEvent: not an event: %w", err)
	}
	if ev.Type == "" {
		return Job{}, errors.New("fakeplatform.JobFromEvent: the event has no type")
	}
	client := ev.ClientCode
	if client == "_platform" {
		client = ""
	}
	return Job{
		ID: jobID, Code: ev.Type, Client: client, Source: ev.Source, Subject: ev.Subject,
		CorrelationID: ev.CorrelationID, MessageGroup: ev.MessageGroup, Data: ev.Data, ContentType: "application/json",
	}, nil
}

// Firing is a scheduled-job firing as the platform posts it.
type Firing struct {
	// JobCode is the job's code and InstanceID this firing's instance; both required.
	JobCode, InstanceID string
	// JobID is the job definition's id; "" means "sj_" + InstanceID.
	JobID string
	// ScheduledFor and FiredAt default to the signing time.
	ScheduledFor, FiredAt time.Time
	// Trigger is CRON (the default) or MANUAL.
	Trigger string
	// CorrelationID defaults to "corr-" + InstanceID.
	CorrelationID string
	// TracksCompletion says the platform waits for the completion report; Concurrent that the job may
	// overlap itself.
	TracksCompletion, Concurrent bool
	// TimeoutSeconds is the firing's own timeout; 0 leaves it out.
	TimeoutSeconds int
	// Payload is the job's payload as JSON; "" leaves it out.
	Payload string
}

// Body is the firing's body at a time.
func (f Firing) Body(at time.Time) (string, error) {
	orNow := func(t time.Time) string {
		if t.IsZero() {
			t = at
		}
		return t.UTC().Format(time.RFC3339Nano)
	}
	or := func(v, d string) string {
		if v == "" {
			return d
		}
		return v
	}
	body := map[string]any{
		"jobId": or(f.JobID, "sj_"+f.InstanceID), "jobCode": f.JobCode, "instanceId": f.InstanceID,
		"scheduledFor": orNow(f.ScheduledFor), "firedAt": orNow(f.FiredAt), "triggerKind": or(f.Trigger, "CRON"),
		"correlationId": or(f.CorrelationID, "corr-"+f.InstanceID), "tracksCompletion": f.TracksCompletion, "concurrent": f.Concurrent,
	}
	if f.TimeoutSeconds != 0 {
		body["timeoutSeconds"] = f.TimeoutSeconds
	}
	if f.Payload != "" {
		v := jsontext.Value(f.Payload)
		if !v.IsValid() {
			return "", errors.New("fakeplatform.Firing: the payload is not JSON")
		}
		body["payload"] = v
	}
	b, err := json.Marshal(body, json.Deterministic(true))
	if err != nil {
		return "", err
	}
	return string(b), nil
}

// Fire builds the signed POST {receivers}/scheduled-jobs of a firing, as the platform sends it.
func Fire(ctx context.Context, receivers string, s Signing, f Firing) (*http.Request, error) {
	if f.JobCode == "" || f.InstanceID == "" {
		return nil, errors.New("fakeplatform.Fire: a firing needs a JobCode and an InstanceID")
	}
	body, err := f.Body(s.At)
	if err != nil {
		return nil, err
	}
	return signed(ctx, strings.TrimRight(receivers, "/")+"/scheduled-jobs", s, body)
}

func signed(ctx context.Context, target string, s Signing, body string) (*http.Request, error) {
	if s.Secret == "" || s.At.IsZero() {
		return nil, errors.New("fakeplatform: a delivery needs the signing secret and the time")
	}
	ts := strconv.FormatInt(s.At.Unix(), 10)
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, target, strings.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-FlowCatalyst-Signature", Signature(s.Secret, ts, body))
	req.Header.Set("X-FlowCatalyst-Timestamp", ts)
	if s.Bearer != "" {
		req.Header.Set("Authorization", "Bearer "+s.Bearer)
	}
	return req, nil
}
