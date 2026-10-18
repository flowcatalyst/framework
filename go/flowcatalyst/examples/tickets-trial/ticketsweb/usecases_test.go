package ticketsweb

import (
	"context"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/tickets"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

// The write path of the tickets service against PostgreSQL, without HTTP or the platform (the Java
// TicketUseCasesTest, test by test).

var (
	start  = time.Date(2026, 10, 17, 9, 0, 0, 0, time.UTC)
	acme   = fw.MustClientID("acme")
	globex = fw.MustClientID("globex")
)

type rig struct {
	t     *testing.T
	clock *fwtest.Clock
	built *Built
	ctx   context.Context
}

func startCore(t *testing.T, deps Deps) *rig {
	t.Helper()
	r := &rig{t: t, clock: fwtest.NewClock(start), ctx: t.Context()}
	env := map[string]string{
		"APP_ENV":                          "ci",
		"DATABASE_INSTANCES_MAIN_URL":      fwtest.NewDatabase(t).URL,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
	}
	app := NewApp(fw.WithEnvironment(env), fw.WithConfigDir(ConfigDir), fw.WithClock(r.clock), quiet())
	deps.Served = func(b *Built) { r.built = b }
	run, err := app.Start(t.Context(), BuildCore(DeclareCore(app), deps))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return r
}

// ---- contexts: who is acting ---------------------------------------------------------------------

func principal(s string) fw.PrincipalID { return fw.MustPrincipalID(s) }

// requester may open tickets and comment on their own.
func requester(client fw.ClientID, who string) fw.Context {
	return fw.ContextFor(client).WithPrincipal(principal(who)).WithPermissions(tickets.OpenTicket, tickets.Comment, tickets.Read)
}

// agent works any ticket of the client.
func agent(client fw.ClientID) fw.Context {
	return fw.ContextFor(client).WithPrincipal(principal("agent_1")).WithPermissions(tickets.Read, tickets.OpenTicket, tickets.Work, tickets.Comment)
}

// lead is an agent who also sets the client's SLA.
func lead(client fw.ClientID) fw.Context {
	return fw.ContextFor(client).WithPrincipal(principal("lead_1")).WithPermissions(tickets.Read, tickets.Work, tickets.Comment, tickets.SLAManage)
}

// service is what a receiver runs as: the application's service principal, no permissions.
func service(client fw.ClientID) fw.Context {
	return fw.ContextFor(client).WithPrincipalOfKind(principal("prn_svc"), fw.PrincipalService)
}

// ---- helpers -------------------------------------------------------------------------------------

func (r *rig) query(sql string) string {
	r.t.Helper()
	var s string
	if err := r.built.DB.Pool().QueryRow(r.ctx, sql).Scan(&s); err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
	return s
}

func (r *rig) exec(sql string) {
	r.t.Helper()
	if _, err := r.built.DB.Pool().Exec(r.ctx, sql); err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
}

func (r *rig) count(sql string) int {
	r.t.Helper()
	var n int
	if err := r.built.DB.Pool().QueryRow(r.ctx, sql).Scan(&n); err != nil {
		r.t.Fatalf("%s: %v", sql, err)
	}
	return n
}

func (r *rig) open(client fw.ClientID, p domain.Priority) string {
	r.t.Helper()
	done, err := r.built.Service.OpenTicket.Run(r.ctx, requester(client, "req_1"), tickets.OpenTicketCmd{Subject: "Printer on fire", Description: "It is smoking.", Priority: p})
	if err != nil {
		r.t.Fatalf("open: %v", err)
	}
	return done.Event().TicketID
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatalf("expected a commit, got %v", err)
	}
}

func failed(t *testing.T, err error) *usecase.Error {
	t.Helper()
	ue, ok := usecase.AsError(err)
	if !ok {
		t.Fatalf("expected a use case error, got %v", err)
	}
	return ue
}

// ticket is version:status:assignee:comments of one ticket.
func (r *rig) ticket(id string) string {
	r.t.Helper()
	return r.query("SELECT version || ':' || status || ':' || coalesce(assignee_id, '-') || ':' || (SELECT count(*) FROM ticket_comments WHERE ticket_id = id) FROM tickets WHERE id = '" + id + "'")
}

func (r *rig) change(cx fw.Context, tid string, a domain.Action, n *string) error {
	_, err := r.built.Service.ChangeStatus.Run(r.ctx, cx, tickets.ChangeStatusCmd{TicketID: tid, Action: a, Note: n})
	return err
}

func (r *rig) assign(cx fw.Context, tid, to string) error {
	_, err := r.built.Service.AssignTicket.Run(r.ctx, cx, tickets.AssignTicketCmd{TicketID: tid, AssigneeID: to})
	return err
}

func (r *rig) comment(cx fw.Context, tid, body string) error {
	_, err := r.built.Service.AddComment.Run(r.ctx, cx, tickets.AddCommentCmd{TicketID: tid, Body: body})
	return err
}

func (r *rig) setPolicy(cx fw.Context, low, normal, high, urgent int32) error {
	_, err := r.built.Service.SetSLAPolicy.Run(r.ctx, cx, tickets.SetSLAPolicyCmd{Minutes: tickets.SlaMinutes{Low: low, Normal: normal, High: high, Urgent: urgent}})
	return err
}

// ---- opening and the SLA -------------------------------------------------------------------------

func TestATicketIsOpenedWithTheDefaultSLAAndWritesOneEventAndOneAuditRow(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)

	if got := r.ticket(tid); got != "1:OPEN:-:0" {
		t.Errorf("ticket %s", got)
	}
	if due := r.query("SELECT (due_at AT TIME ZONE 'UTC')::text FROM tickets WHERE id = '" + tid + "'"); !strings.HasPrefix(strings.ReplaceAll(due, " ", "T"), "2026-10-18T09:00:00") { // NORMAL: 1440 minutes
		t.Errorf("due %s", due)
	}
	if n := r.count("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'"); n != 1 {
		t.Errorf("events %d", n)
	}
	if n := r.count("SELECT count(*) FROM outbox_messages WHERE type = 'AUDIT_LOG'"); n != 1 {
		t.Errorf("audit rows %d", n)
	}
	if got := r.query("SELECT payload::jsonb ->> 'type' FROM outbox_messages WHERE type = 'EVENT'"); got != "tickets-trial:support:ticket:opened" {
		t.Errorf("event type %s", got)
	}
	if got := r.query("SELECT payload::jsonb ->> 'clientCode' FROM outbox_messages WHERE type = 'EVENT'"); got != "acme" {
		t.Errorf("client %s", got)
	}
}

func (r *rig) minutesToDue(tid string) string {
	r.t.Helper()
	return r.query("SELECT (extract(epoch FROM (due_at - opened_at)) / 60)::bigint FROM tickets WHERE id = '" + tid + "'")
}

func TestAClientsOwnPolicyDecidesTheDeadlineOfTicketsOpenedAfterwardsButNotBefore(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	before := r.open(acme, domain.Urgent)
	must(t, r.setPolicy(lead(acme), 600, 300, 120, 15))
	after := r.open(acme, domain.Urgent)
	other := r.open(globex, domain.Urgent)

	if got := r.minutesToDue(before); got != "60" { // the default, fixed when it was opened
		t.Errorf("before: %s", got)
	}
	if got := r.minutesToDue(after); got != "15" {
		t.Errorf("after: %s", got)
	}
	if got := r.minutesToDue(other); got != "60" { // another client's policy is not this client's
		t.Errorf("other client: %s", got)
	}
}

func TestAPolicyIsOnePerClientAndASecondSetUpdatesIt(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	must(t, r.setPolicy(lead(acme), 600, 300, 120, 15))
	must(t, r.setPolicy(lead(acme), 700, 400, 130, 20))

	if n := r.count("SELECT count(*) FROM fw_client_settings WHERE client_id = 'acme'"); n != 1 {
		t.Errorf("rows %d", n)
	}
	if got := r.query("SELECT version || ':' || (settings -> 'tickets.sla' ->> 'urgent') FROM fw_client_settings WHERE client_id = 'acme'"); got != "2:20" {
		t.Errorf("stored %s", got)
	}
	if ue := failed(t, r.setPolicy(lead(acme), 10, 20, 30, 40)); ue.Code() != "POLICY_NOT_ORDERED" {
		t.Errorf("unordered: %s", ue.Code())
	}
	if ue := failed(t, r.setPolicy(lead(acme), 0, 20, 30, 40)); ue.Code() != "MINUTES_INVALID" {
		t.Errorf("range: %s", ue.Code())
	}
}

// ---- the status machine --------------------------------------------------------------------------

func TestATicketGoesFromOpenToClosedAndTheMachineRefusesTheRest(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.High)
	a := agent(acme)

	if ue := failed(t, r.change(a, tid, domain.Start, nil)); ue.Code() != "ASSIGNEE_REQUIRED" {
		t.Errorf("start unassigned: %s", ue.Code())
	}
	must(t, r.assign(a, tid, "agent_1"))
	if ue := failed(t, r.assign(a, tid, "agent_1")); ue.Code() != "ALREADY_ASSIGNED" {
		t.Errorf("assign twice: %s", ue.Code())
	}
	must(t, r.change(a, tid, domain.Start, nil))
	if got := r.ticket(tid); got != "3:IN_PROGRESS:agent_1:0" {
		t.Errorf("ticket %s", got)
	}

	illegal := failed(t, r.change(a, tid, domain.Close, nil))
	if illegal.Code() != "ILLEGAL_TRANSITION" || illegal.Kind() != usecase.KindBusinessRule ||
		illegal.Details()["status"] != "IN_PROGRESS" || illegal.Details()["action"] != "CLOSE" {
		t.Errorf("illegal: %s %v %v", illegal.Code(), illegal.Kind(), illegal.Details())
	}
	if ue := failed(t, r.change(a, tid, domain.Resolve, new(" "))); ue.Code() != "NOTE_REQUIRED" {
		t.Errorf("blank note: %s", ue.Code())
	}

	r.clock.Set(start.Add(600 * time.Second))
	must(t, r.change(a, tid, domain.Resolve, new("Replaced the toner.")))
	if got := r.query("SELECT resolution || '@' || (resolved_at AT TIME ZONE 'UTC') FROM tickets WHERE id = '" + tid + "'"); !strings.HasPrefix(got, "Replaced the toner.@2026-10-17 09:10:00") {
		t.Errorf("resolved: %s", got)
	}
	must(t, r.change(a, tid, domain.Reopen, new("Still smoking.")))
	if got := r.query("SELECT coalesce(resolution, 'none') FROM tickets WHERE id = '" + tid + "'"); got != "none" {
		t.Errorf("reopened resolution: %s", got)
	}
	must(t, r.change(a, tid, domain.Start, nil))
	must(t, r.change(a, tid, domain.Resolve, new("Replaced the printer.")))
	must(t, r.change(a, tid, domain.Close, nil))
	if got := r.ticket(tid); got != "8:CLOSED:agent_1:0" {
		t.Errorf("closed: %s", got)
	}

	if ue := failed(t, r.assign(a, tid, "agent_2")); ue.Code() != "TICKET_NOT_ASSIGNABLE" {
		t.Errorf("assign closed: %s", ue.Code())
	}
	if ue := failed(t, r.change(a, tid, domain.Reopen, new("no"))); ue.Code() != "ILLEGAL_TRANSITION" {
		t.Errorf("reopen closed: %s", ue.Code())
	}
	// every committed step wrote an event and an audit row
	if n := r.count("SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'"); n != 8 {
		t.Errorf("events %d", n)
	}
	if n := r.count("SELECT count(*) FROM outbox_messages WHERE type = 'AUDIT_LOG'"); n != 8 {
		t.Errorf("audit rows %d", n)
	}
}

func TestAnAssignmentEventNamesTheNewAndThePreviousAssignee(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Low)
	must(t, r.assign(agent(acme), tid, "agent_2"))
	must(t, r.assign(agent(acme), tid, "agent_3"))

	got := r.query(`SELECT (payload::jsonb ->> 'data')::jsonb ->> 'assigneeId' || '<-' || ((payload::jsonb ->> 'data')::jsonb ->> 'previousAssigneeId')
		FROM outbox_messages WHERE type = 'EVENT' AND payload::jsonb ->> 'type' LIKE '%:assigned'
		ORDER BY created_at DESC, id DESC LIMIT 1`)
	if got != "agent_3<-agent_2" {
		t.Errorf("event data %s", got)
	}
}

// ---- comments ------------------------------------------------------------------------------------

func TestCommentsAreAppendedInOrderAndARequesterCommentsOnlyOnTheirOwnTicket(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)

	must(t, r.comment(requester(acme, "req_1"), tid, "Any news?"))
	must(t, r.comment(agent(acme), tid, "Looking now."))
	notYours := failed(t, r.comment(requester(acme, "req_2"), tid, "Me too"))

	if notYours.Kind() != usecase.KindAuthorization || notYours.Code() != "NOT_YOUR_TICKET" {
		t.Errorf("not yours: %v %s", notYours.Kind(), notYours.Code())
	}
	if got := r.ticket(tid); got != "3:OPEN:-:2" {
		t.Errorf("ticket %s", got)
	}
	if got := r.query("SELECT string_agg(position || author_id, ',' ORDER BY position) FROM ticket_comments"); got != "0req_1,1agent_1" {
		t.Errorf("comments %s", got)
	}
	if ue := failed(t, r.comment(agent(acme), tid, "  ")); ue.Code() != "BODY_INVALID" {
		t.Errorf("blank comment: %s", ue.Code())
	}

	must(t, r.assign(agent(acme), tid, "agent_1"))
	must(t, r.change(agent(acme), tid, domain.Start, nil))
	must(t, r.change(agent(acme), tid, domain.Resolve, new("Done")))
	must(t, r.change(agent(acme), tid, domain.Close, nil))
	if ue := failed(t, r.comment(agent(acme), tid, "Late")); ue.Code() != "TICKET_CLOSED" {
		t.Errorf("comment on closed: %s", ue.Code())
	}
}

// ---- authorization and clients -------------------------------------------------------------------

func TestAUseCaseChecksTheCallersPermissionAndTheKindOfPrincipalItNeeds(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)
	outbox := r.count("SELECT count(*) FROM outbox_messages")

	_, err := r.built.Service.OpenTicket.Run(r.ctx, fw.ContextFor(acme), tickets.OpenTicketCmd{Subject: "x", Priority: domain.Low})
	if ue := failed(t, err); ue.Code() != "PRINCIPAL_REQUIRED" {
		t.Errorf("no principal: %s", ue.Code())
	}
	if ue := failed(t, r.assign(requester(acme, "req_1"), tid, "req_1")); ue.Code() != "FORBIDDEN" {
		t.Errorf("requester assigns: %s", ue.Code())
	}
	if ue := failed(t, r.setPolicy(requester(acme, "req_1"), 4, 3, 2, 1)); ue.Kind() != usecase.KindAuthorization {
		t.Errorf("requester sets the policy: %v", ue.Kind())
	}
	// the sweep's use case is the service's only: not even an agent may flag a ticket
	_, err = r.built.Service.FlagSLABreach.Run(r.ctx, agent(acme), tickets.FlagSLABreachCmd{TicketID: tid})
	if ue := failed(t, err); ue.Code() != "NOT_A_SERVICE" {
		t.Errorf("agent flags: %s", ue.Code())
	}
	if n := r.count("SELECT count(*) FROM outbox_messages"); n != outbox {
		t.Errorf("outbox %d, was %d", n, outbox)
	}
	if got := r.ticket(tid); got != "1:OPEN:-:0" {
		t.Errorf("ticket %s", got)
	}
}

func TestAnotherClientCannotSeeOrChangeTheTicketAndListsAreTheClientsOwn(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)
	r.open(globex, domain.Normal)

	if ue := failed(t, r.assign(agent(globex), tid, "agent_1")); ue.Code() != "TICKET_NOT_FOUND" {
		t.Errorf("assign: %s", ue.Code())
	}
	if ue := failed(t, r.comment(agent(globex), tid, "mine")); ue.Code() != "TICKET_NOT_FOUND" {
		t.Errorf("comment: %s", ue.Code())
	}
	if got := r.ticket(tid); got != "1:OPEN:-:0" {
		t.Errorf("ticket %s", got)
	}
	mine, err := tickets.List(r.ctx, r.built.DB, acme, tickets.Filter{}, 50)
	if err != nil || len(mine.Items) != 1 || mine.Items[0].ID.String() != tid {
		t.Errorf("acme list %v %v", mine, err)
	}
	theirs, err := tickets.List(r.ctx, r.built.DB, globex, tickets.Filter{}, 50)
	if err != nil || len(theirs.Items) != 1 {
		t.Errorf("globex list %v %v", theirs, err)
	}
}

// ---- reading -------------------------------------------------------------------------------------

func TestTheListFiltersAndPagesNewestFirst(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	a, b, c := r.open(acme, domain.Low), r.open(acme, domain.Normal), r.open(acme, domain.High)
	must(t, r.assign(agent(acme), b, "agent_7"))
	ids := func(p tickets.SummaryPage) string {
		var s []string
		for _, i := range p.Items {
			s = append(s, i.ID.String())
		}
		return strings.Join(s, ",")
	}
	list := func(f tickets.Filter, limit int) tickets.SummaryPage {
		t.Helper()
		p, err := tickets.List(r.ctx, r.built.DB, acme, f, limit)
		if err != nil {
			t.Fatal(err)
		}
		return p
	}

	first := list(tickets.Filter{}, 2)
	if ids(first) != c+","+b || first.NextCursor != b {
		t.Errorf("first page %s next %s", ids(first), first.NextCursor)
	}
	second := list(tickets.Filter{After: first.NextCursor}, 2)
	if ids(second) != a || second.NextCursor != "" {
		t.Errorf("second page %s next %q", ids(second), second.NextCursor)
	}
	if got := ids(list(tickets.Filter{Assignee: "agent_7"}, 50)); got != b {
		t.Errorf("by assignee %s", got)
	}
	if got := ids(list(tickets.Filter{Status: domain.InProgress}, 50)); got != "" {
		t.Errorf("in progress %q", got)
	}
	if got := len(list(tickets.Filter{Status: domain.Open, Requester: "req_1"}, 50).Items); got != 3 {
		t.Errorf("open by req_1: %d", got)
	}
	counts, err := tickets.Counts(r.ctx, r.built.DB, acme)
	if err != nil || len(counts) != 1 || counts[0] != (tickets.StatusCount{Status: domain.Open, Count: 3, Flagged: 0}) {
		t.Errorf("counts %v %v", counts, err)
	}
}

// ---- the SLA sweep's use case --------------------------------------------------------------------

func TestATicketPastItsDeadlineIsFlaggedOnceAndOnlyWhileSomeoneStillOwesAnAnswer(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	acmeUrgent := r.open(acme, domain.Urgent) // due in 60 minutes
	globexUrgent := r.open(globex, domain.Urgent)
	resolved := r.open(acme, domain.Urgent)
	must(t, r.assign(agent(acme), resolved, "agent_1"))
	must(t, r.change(agent(acme), resolved, domain.Start, nil))
	must(t, r.change(agent(acme), resolved, domain.Resolve, new("Done in time")))
	flag := func(cx fw.Context, tid string) error {
		_, err := r.built.Service.FlagSLABreach.Run(r.ctx, cx, tickets.FlagSLABreachCmd{TicketID: tid})
		return err
	}
	clientsOf := func() []fw.ClientID {
		cs, err := tickets.OverdueClients(r.ctx, r.built.DB, r.clock.Now())
		if err != nil {
			t.Fatal(err)
		}
		return cs
	}

	if cs := clientsOf(); len(cs) != 0 {
		t.Errorf("clients before the deadline: %v", cs)
	}
	if ue := failed(t, flag(service(acme), acmeUrgent)); ue.Code() != "NOT_OVERDUE" {
		t.Errorf("before the deadline: %s", ue.Code())
	}

	r.clock.Set(start.Add(61 * time.Minute))
	if cs := clientsOf(); len(cs) != 2 || cs[0] != acme || cs[1] != globex {
		t.Errorf("clients after the deadline: %v", cs)
	}
	// Each client's query sees only that client's tickets, and the sweep acts with that client's own context.
	overdue := func(c fw.ClientID) []string {
		os, err := tickets.OverdueFor(r.ctx, r.built.DB, c, r.clock.Now(), 100)
		if err != nil {
			t.Fatal(err)
		}
		var out []string
		for _, o := range os {
			out = append(out, o.ID.String())
		}
		return out
	}
	if got := overdue(acme); len(got) != 1 || got[0] != acmeUrgent { // not the resolved one
		t.Errorf("acme overdue %v", got)
	}
	if got := overdue(globex); len(got) != 1 || got[0] != globexUrgent {
		t.Errorf("globex overdue %v", got)
	}
	results := fw.EachClient(r.ctx, nil, service(acme), clientsOf(), func(ctx context.Context, cx fw.Context) (fw.ClientID, error) {
		for _, tid := range overdue(cx.Client()) {
			if _, err := r.built.Service.FlagSLABreach.Run(ctx, cx, tickets.FlagSLABreachCmd{TicketID: tid}); err != nil {
				return fw.ClientID{}, err
			}
		}
		return cx.Client(), nil
	})
	for _, res := range results {
		if !res.Done() {
			t.Errorf("client %s: %v", res.Client, res.Err)
		}
	}
	if cs := clientsOf(); len(cs) != 0 {
		t.Errorf("clients after the sweep: %v", cs)
	}
	if ue := failed(t, flag(service(acme), acmeUrgent)); ue.Code() != "NOT_OVERDUE" {
		t.Errorf("twice: %s", ue.Code())
	}
	if ue := failed(t, flag(service(globex), acmeUrgent)); ue.Code() != "TICKET_NOT_FOUND" {
		t.Errorf("another client's ticket: %s", ue.Code())
	}
	if n := r.count("SELECT count(*) FROM outbox_messages WHERE payload::jsonb ->> 'type' = 'tickets-trial:support:ticket:sla-breached'"); n != 2 {
		t.Errorf("breach events %d", n)
	}
	open, late, err := tickets.Totals(r.ctx, r.built.DB)
	if err != nil || open != 2 || late != 2 {
		t.Errorf("totals %d %d %v", open, late, err)
	}
}

// ---- atomicity and optimistic locking ------------------------------------------------------------

func TestADatabaseFailureWhileWritingTheCommentRollsBackTheTicketAndTheOutbox(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)
	outbox := r.count("SELECT count(*) FROM outbox_messages")

	// A rule only the database knows: validate passes, the comment insert fails after the ticket row
	// was updated.
	r.exec("ALTER TABLE ticket_comments ADD CONSTRAINT no_boom CHECK (body <> 'boom')")
	ue := failed(t, r.comment(agent(acme), tid, "boom"))

	if ue.Kind() != usecase.KindInternal || ue.Code() != "PERSIST" {
		t.Errorf("got %v %s", ue.Kind(), ue.Code())
	}
	if strings.Contains(ue.Message(), "boom") || strings.Contains(ue.Message(), "ticket_comments") {
		t.Errorf("the message leaks: %q", ue.Message())
	}
	if got := r.ticket(tid); got != "1:OPEN:-:0" {
		t.Errorf("ticket %s", got)
	}
	if n := r.count("SELECT count(*) FROM outbox_messages"); n != outbox {
		t.Errorf("outbox %d, was %d", n, outbox)
	}
}

// gated holds the first load of a ticket until the test lets the attempt go on.
type gated struct {
	tickets.Repository
	loaded  chan struct{}
	proceed chan struct{}
	once    sync.Once
}

func newGated() *gated { return &gated{loaded: make(chan struct{}), proceed: make(chan struct{})} }

func (g *gated) Find(ctx context.Context, db *database.DB, client fw.ClientID, tid domain.TicketID) (usecase.Loaded[domain.Ticket], bool, error) {
	l, found, err := g.Repository.Find(ctx, db, client, tid)
	g.once.Do(func() {
		close(g.loaded)
		select {
		case <-g.proceed:
		case <-time.After(20 * time.Second):
			err = context.DeadlineExceeded
		case <-ctx.Done():
			err = ctx.Err()
		}
	})
	return l, found, err
}

func (r *rig) slow(store tickets.Store, p tickets.Policies) *tickets.Service {
	r.t.Helper()
	deps := r.built.Deps
	deps.Tickets = store
	svc, err := tickets.NewService(r.built.Runner, deps, p)
	if err != nil {
		r.t.Fatal(err)
	}
	return svc
}

func TestTwoCommentsPostedAtOnceBothLandUnderRetryAtTheNextFreePositions(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)
	g := newGated()
	retry, err := usecase.Retry(3, 10*time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	slow := r.slow(g, tickets.Policies{Assign: retry, Change: retry, Comment: retry, Flag: retry, Policy: retry})

	done := make(chan error, 1)
	go func() {
		_, err := slow.AddComment.Run(r.ctx, agent(acme), tickets.AddCommentCmd{TicketID: tid, Body: "from A"})
		done <- err
	}()
	<-g.loaded
	must(t, r.comment(agent(acme), tid, "from B"))
	close(g.proceed)

	must(t, <-done)
	if got := r.ticket(tid); got != "3:OPEN:-:2" {
		t.Errorf("ticket %s", got)
	}
	if got := r.query("SELECT string_agg(body, ',' ORDER BY position) FROM ticket_comments"); got != "from B,from A" {
		t.Errorf("comments %s", got)
	}
}

func TestAStatusChangeOnAStaleViewIsRefusedAndLeavesTheOtherChangeAlone(t *testing.T) {
	t.Parallel()
	r := startCore(t, Deps{})
	tid := r.open(acme, domain.Normal)
	must(t, r.assign(agent(acme), tid, "agent_1"))
	g := newGated()
	slow := r.slow(g, tickets.Policies{Assign: usecase.Fail(), Change: usecase.Fail(), Comment: usecase.Fail(), Flag: usecase.Fail(), Policy: usecase.Fail()})

	done := make(chan error, 1)
	go func() {
		_, err := slow.ChangeStatus.Run(r.ctx, agent(acme), tickets.ChangeStatusCmd{TicketID: tid, Action: domain.Start})
		done <- err
	}()
	<-g.loaded
	must(t, r.comment(agent(acme), tid, "while you were looking"))
	outbox := r.count("SELECT count(*) FROM outbox_messages")
	close(g.proceed)

	ue := failed(t, <-done)
	if ue.Kind() != usecase.KindConflict || ue.Code() != "STALE_AGGREGATE" {
		t.Errorf("got %v %s", ue.Kind(), ue.Code())
	}
	if got := r.ticket(tid); got != "3:OPEN:agent_1:1" {
		t.Errorf("ticket %s", got)
	}
	if n := r.count("SELECT count(*) FROM outbox_messages"); n != outbox {
		t.Errorf("outbox %d, was %d", n, outbox)
	}
}
