package ticketsweb

import (
	"bytes"
	"encoding/json/jsontext"
	"encoding/json/v2"
	"io"
	"net/http"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/tickets"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/metrics"
)

// Everything the platform calls into or is told: the definition sync, the dispatch receiver that
// notifies an assignee (through a fake notification service, with a client's own key), the
// scheduled-job receiver that runs the SLA sweep, and the gauges (the Java TicketsPlatformTest, test by
// test). The platform is the fakeplatform, the notification service a local server; nothing leaves the
// machine.

const (
	signingSecret = "whsec-tickets-test-only"
	acmeKey       = "key-acme-test-only"
)

type platformSite struct {
	*site
	platform *Platform
	notifier *NotifierFake
	clock    *fwtest.Clock
}

func startPlatformSite(t *testing.T) *platformSite {
	t.Helper()
	p := StartPlatform()
	t.Cleanup(p.Close)
	n := StartNotifier()
	t.Cleanup(n.Close)
	clock := fwtest.NewClock(time.Now())
	s := startWeb(t, siteOptions{
		clock:  clock,
		tuning: &Tuning{CommentLimit: CommentLimit, GaugeInterval: 50 * time.Millisecond},
		env: map[string]string{
			"FLOWCATALYST_ENABLED":                          "true",
			"FLOWCATALYST_BASE_URL":                         p.BaseURL(),
			"FLOWCATALYST_CLIENT_ID":                        fakeplatform.ClientID,
			"FLOWCATALYST_CLIENT_SECRET":                    fakeplatform.ClientSecret,
			"FLOWCATALYST_SUBSCRIPTION_TARGET_BASE_URL":     "http://tickets.test",
			"FLOWCATALYST_RECEIVERS_SIGNING_SECRET":         signingSecret,
			"HTTP_CLIENTS_INSTANCES_NOTIFIER_BASE_URL":      n.BaseURL(),
			"TICKETS_NOTIFY_ACME__NOTIFY_KEY":               acmeKey, // globex has none
			"FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY": "0s",
		},
	})
	return &platformSite{site: s, platform: p, notifier: n, clock: clock}
}

func (s *platformSite) person(client, who, role string) string {
	return s.site.person(client, who, "tickets-trial:"+role)
}

func (s *platformSite) assign(cookie, ticketID, assignee string) {
	s.t.Helper()
	if a := s.post("/api/tickets/"+ticketID+"/assign", cookie, `{"assigneeId":"`+assignee+`"}`); a.status != 200 {
		s.t.Fatalf("assign: %d %s", a.status, a.body)
	}
}

// eventRow is the outbox row of the latest event of a type, as the platform's processor would read it.
func (s *platformSite) eventRow(eventType string) map[string]any {
	s.t.Helper()
	var payload string
	err := s.built.DB.Pool().QueryRow(s.t.Context(),
		`SELECT payload FROM outbox_messages WHERE type = 'EVENT' AND payload::jsonb ->> 'type' = $1 ORDER BY created_at DESC, id DESC LIMIT 1`,
		eventType).Scan(&payload)
	if err != nil {
		s.t.Fatal(err)
	}
	var row map[string]any
	if err := json.Unmarshal([]byte(payload), &row); err != nil {
		s.t.Fatal(err)
	}
	return row
}

func (s *platformSite) query(sql string) string {
	s.t.Helper()
	var out string
	if err := s.built.DB.Pool().QueryRow(s.t.Context(), sql).Scan(&out); err != nil {
		s.t.Fatalf("%s: %v", sql, err)
	}
	return out
}

// envelopeOf is what the platform delivers to a subscription for an event: the envelope around the
// row's data.
func envelopeOf(t *testing.T, event map[string]any, jobID, clientCode string) string {
	t.Helper()
	data, _ := event["data"].(string)
	envelope := []struct {
		Key   string
		Value any
	}{
		{"id", jobID},
		{"type", event["type"]},
		{"attemptNumber", 1},
		{"source", event["source"]},
		{"subject", event["subject"]},
		{"correlationId", event["correlationId"]},
		{"messageGroup", event["messageGroup"]},
		{"clientId", "clt_" + clientCode},
		{"clientCode", clientCode},
		{"data", jsontext.Value(data)},
	}
	var b bytes.Buffer
	b.WriteByte('{')
	for i, e := range envelope {
		if i > 0 {
			b.WriteByte(',')
		}
		k, _ := json.Marshal(e.Key)
		v, err := json.Marshal(e.Value)
		if err != nil {
			t.Fatal(err)
		}
		b.Write(k)
		b.WriteByte(':')
		b.Write(v)
	}
	b.WriteByte('}')
	return b.String()
}

func (s *platformSite) deliver(d Delivery) answer {
	s.t.Helper()
	req, err := d.Request(s.t.Context(), s.base, signingSecret, s.clock.Now())
	if err != nil {
		s.t.Fatal(err)
	}
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		s.t.Fatal(err)
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(res.Body)
	return answer{res.StatusCode, res.Header, string(b)}
}

func (s *platformSite) deliverAssignment(event map[string]any, jobID, clientCode string) answer {
	s.t.Helper()
	return s.deliver(Delivery{
		Path: "/fc/dispatch", Body: envelopeOf(s.t, event, jobID, clientCode),
		Headers: map[string]string{"X-Dispatch-Job-Id": jobID, "X-Event-Type": event["type"].(string), "X-FlowCatalyst-Client": "clt_" + clientCode + ":" + clientCode}, //nolint:forcetypeassert // an outbox row has a string type
	})
}

func (s *platformSite) fire(instanceID string) answer {
	s.t.Helper()
	return s.deliver(Delivery{Path: "/fc/scheduled-jobs", Body: FiringBody(s.clock.Now(), tickets.SLASweepJob, instanceID)})
}

// ---- the definition sync -----------------------------------------------------------------------

func TestTheSyncSendsTheEventTypesRolesConnectionSubscriptionAndJobInTheOrderTheyDependOn(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	if _, err := s.built.Platform.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
		t.Fatal(err)
	}

	synced := strings.Join(s.platform.SyncedResources(), ",")
	for _, want := range []string{"roles", "event-types", "connections", "subscriptions", "scheduled-jobs"} {
		if !strings.Contains(synced, want) {
			t.Errorf("%s was not synced: %s", want, synced)
		}
	}
	of := func(resource string) string {
		r, ok := s.platform.SyncOf(resource)
		if !ok {
			t.Fatalf("no sync of %s", resource)
		}
		return r.Body
	}
	for _, code := range []string{"opened", "assigned", "status-changed", "commented", "sla-breached"} {
		if !strings.Contains(of("event-types"), "tickets-trial:support:ticket:"+code) {
			t.Errorf("event type %s missing: %s", code, of("event-types"))
		}
	}
	if !strings.Contains(of("event-types"), "tickets-trial:support:sla-policy:changed") {
		t.Errorf("sla-policy:changed missing")
	}
	roles := of("roles")
	for _, want := range []string{
		`"name":"agent"`, `"name":"lead"`, `"name":"requester"`, "tickets-trial:support:sla:manage",
		// the framework added the administrator's role itself, from the admin sections the service registered
		`"name":"admin"`, "tickets-trial:admin:tickets:view",
	} {
		if !strings.Contains(roles, want) {
			t.Errorf("roles lack %s: %s", want, roles)
		}
	}
	if subs := of("subscriptions"); !strings.Contains(subs, "http://tickets.test/fc/dispatch") || !strings.Contains(subs, "notify-assignee") {
		t.Errorf("subscriptions: %s", subs)
	}
	if jobs := of("scheduled-jobs"); !strings.Contains(jobs, "sla-sweep") || !strings.Contains(jobs, "0 * * * * *") {
		t.Errorf("jobs: %s", jobs)
	}
}

func TestADryRunNamesWhatWouldBeSyncedAndSendsNothing(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	s.platform.Clear()
	report, err := s.built.Platform.Sync(t.Context(), flowcatalyst.SyncDryRun)
	if err != nil {
		t.Fatal(err)
	}
	text := strings.Join(report.Describe(), "\n")
	if !strings.Contains(text, "sla-sweep") || !strings.Contains(text, "notify-assignee") {
		t.Errorf("report: %s", text)
	}
	if got := s.platform.SyncRequests(); len(got) != 0 {
		t.Errorf("a dry run sent %d requests", len(got))
	}
}

// ---- the dispatch receiver: notify the assignee ------------------------------------------------

func TestAnAssignmentTheUseCaseWroteNotifiesTheAssigneeWithTheClientsOwnKey(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	agent := s.person("acme", "agent_1", "agent")
	id := s.open(s.person("acme", "prn_ada", "requester"), "Printer on fire", "HIGH")
	s.assign(agent, id, "agent_7")

	r := s.deliverAssignment(s.eventRow("tickets-trial:support:ticket:assigned"), "dj_0HZXEQ5Y8JY5Z", "acme")

	if r.status != 200 || r.body != `{"ack":true}` {
		t.Fatalf("delivery: %d %s", r.status, r.body)
	}
	got := s.notifier.Received()
	if len(got) != 1 {
		t.Fatalf("notifications: %d", len(got))
	}
	call := got[0]
	if call.Path != "/notifications" || call.Header.Get("Authorization") != "Bearer "+acmeKey || call.Header.Get("Idempotency-Key") != "dj_0HZXEQ5Y8JY5Z" {
		t.Errorf("call %s %v", call.Path, call.Header)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(call.Body), &body); err != nil {
		t.Fatal(err)
	}
	if body["to"] != "agent_7" || body["ticketId"] != id || body["subject"] != "Printer on fire" || body["assignedBy"] != "agent_1" {
		t.Errorf("body %s", call.Body)
	}
}

func TestAssigningATicketToYourselfNotifiesNobody(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	id := s.open(s.person("acme", "prn_ada", "requester"), "Mine", "LOW")
	s.assign(s.person("acme", "agent_1", "agent"), id, "agent_1")

	r := s.deliverAssignment(s.eventRow("tickets-trial:support:ticket:assigned"), "dj_1", "acme")

	if r.status != 200 || r.body != `{"ack":true}` || len(s.notifier.Received()) != 0 {
		t.Errorf("delivery: %d %s, %d notifications", r.status, r.body, len(s.notifier.Received()))
	}
}

func TestAClientWithoutAKeyIsRejectedTerminallyAndTheSecretIsNeverSent(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	id := s.open(s.person("globex", "prn_gus", "requester"), "Globex printer", "LOW")
	s.assign(s.person("globex", "agent_g", "agent"), id, "agent_g2")

	r := s.deliverAssignment(s.eventRow("tickets-trial:support:ticket:assigned"), "dj_2", "globex")

	if r.status != 422 || r.header.Get("FlowCatalyst-Outcome") != "reject" || strings.Contains(r.body, acmeKey) || len(s.notifier.Received()) != 0 {
		t.Errorf("delivery: %d %v %s, %d notifications", r.status, r.header, r.body, len(s.notifier.Received()))
	}
}

func TestATroubledNotificationServiceIsTransientAndARefusalIsTerminal(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	id := s.open(s.person("acme", "prn_ada", "requester"), "Printer", "LOW")
	s.assign(s.person("acme", "agent_1", "agent"), id, "agent_7")
	event := s.eventRow("tickets-trial:support:ticket:assigned")

	s.notifier.Answer(500)
	if got := s.deliverAssignment(event, "dj_3", "acme").status; got != 503 { // the platform retries it
		t.Errorf("a 500: %d", got)
	}
	s.notifier.Answer(400)
	refused := s.deliverAssignment(event, "dj_4", "acme")
	if refused.status != 422 || !strings.Contains(refused.body, "400") || strings.Contains(refused.body, acmeKey) {
		t.Errorf("a 400: %d %s", refused.status, refused.body)
	}
	s.notifier.Answer(202)
	if got := s.deliverAssignment(event, "dj_5", "acme").status; got != 200 {
		t.Errorf("recovered: %d", got)
	}
}

func TestADeliveryWithoutTheSignatureIsRefusedBeforeAnyHandlerRuns(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	r := s.call("POST", "/fc/dispatch", "{}", "Content-Type", "application/json", "X-Dispatch-Job-Id", "dj_9", "X-Event-Type", "tickets-trial:support:ticket:assigned")

	if r.status != 401 || len(s.notifier.Received()) != 0 {
		t.Errorf("delivery: %d %s, %d notifications", r.status, r.body, len(s.notifier.Received()))
	}
}

// ---- the scheduled-job receiver: the SLA sweep -------------------------------------------------

func TestTheSweepFlagsEveryClientsOverdueTicketsAsTheServiceAndReportsWhatItDid(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	acmeUrgent := s.open(s.person("acme", "prn_ada", "requester"), "Acme urgent", "URGENT") // due in 60 minutes
	globexUrgent := s.open(s.person("globex", "prn_gus", "requester"), "Globex urgent", "URGENT")
	acmeLow := s.open(s.person("acme", "prn_ada", "requester"), "Acme low", "LOW") // due in two days

	s.clock.Advance(61 * time.Minute)
	if got := s.fire("si_1").status; got != 202 {
		t.Fatalf("fired: %d", got)
	}

	calls, err := s.platform.AwaitInstanceCalls("complete", 1, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	completion := calls[0].JSON()
	if completion["status"] != "SUCCESS" || !strings.Contains(calls[0].Body, `"flagged":2`) {
		t.Errorf("completion %s", calls[0].Body)
	}
	if got := s.query("SELECT string_agg(id, ',' ORDER BY id) FROM tickets WHERE breached_at IS NOT NULL"); got != acmeUrgent+","+globexUrgent {
		t.Errorf("flagged %s", got)
	}
	if got := s.query("SELECT (breached_at IS NULL)::text FROM tickets WHERE id = '" + acmeLow + "'"); got != "true" {
		t.Errorf("the low ticket was flagged")
	}

	// Each flag is an event in the ticket's own client, written by the service principal, caused by the firing.
	clients := s.query(`SELECT string_agg(payload::jsonb ->> 'clientCode', ',' ORDER BY payload::jsonb ->> 'clientCode') FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:sla-breached'`)
	if clients != "acme,globex" {
		t.Errorf("breach events for %s", clients)
	}
	breach := s.eventRow("tickets-trial:support:ticket:sla-breached")
	raw, _ := json.Marshal(breach)
	if breach["causationId"] != "si_1" || !strings.Contains(string(raw), fakeplatform.ServicePrincipal) {
		t.Errorf("breach row %s", raw)
	}

	// A second firing finds nothing left to do.
	if got := s.fire("si_2").status; got != 202 {
		t.Fatalf("second fired: %d", got)
	}
	calls, err = s.platform.AwaitInstanceCalls("complete", 2, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(calls[1].Body, `"flagged":0`) {
		t.Errorf("second completion %s", calls[1].Body)
	}
	if got := s.query(`SELECT count(*)::text FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:sla-breached'`); got != "2" {
		t.Errorf("breach events %s", got)
	}
}

func TestTheSweepHoldsTheOverlapLockOfItsJobCode(t *testing.T) {
	t.Parallel()
	// The framework takes scheduled-job:<code> from the default lock store around the run: a second
	// replica (here, the same store) cannot run the sweep while the first holds it.
	s := startPlatformSite(t)
	lock, ok := s.built.Locks.Default()
	if !ok {
		t.Fatal("no default lock")
	}
	held, err := lock.TryAcquire(t.Context(), locks.MustServiceKey("scheduled-job:sla-sweep"), time.Minute)
	if err != nil {
		t.Fatal(err)
	}
	if _, acquired := held.(locks.Acquired); !acquired {
		t.Fatalf("the test could not take the lock: %v", held)
	}

	if got := s.fire("si_locked").status; got != 202 {
		t.Fatalf("fired: %d", got)
	}

	calls, err := s.platform.AwaitInstanceCalls("complete", 1, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(calls[0].Body, "lock-held") {
		t.Errorf("completion %s", calls[0].Body)
	}
}

// ---- the gauges --------------------------------------------------------------------------------

func TestTheGaugesFollowTheTicketsAcrossClients(t *testing.T) {
	t.Parallel()
	s := startPlatformSite(t)
	s.open(s.person("acme", "prn_ada", "requester"), "A", "URGENT")
	s.open(s.person("globex", "prn_gus", "requester"), "B", "LOW")
	s.clock.Advance(61 * time.Minute)
	s.fire("si_g")
	if _, err := s.platform.AwaitInstanceCalls("complete", 1, 10*time.Second); err != nil {
		t.Fatal(err)
	}

	deadline := time.Now().Add(10 * time.Second)
	var text string
	for time.Now().Before(deadline) {
		var b bytes.Buffer
		if err := metrics.WritePrometheus(&b, s.run.Boot().Metrics().Snapshot()); err != nil {
			t.Fatal(err)
		}
		text = b.String()
		if strings.Contains(text, "tickets_active_count 2\n") && strings.Contains(text, "tickets_overdue_count 1\n") {
			return
		}
		time.Sleep(25 * time.Millisecond)
	}
	t.Fatalf("gauges not as expected:\n%s", text)
}
