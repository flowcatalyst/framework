package tickets

import (
	"context"
	"fmt"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpclient/module"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/secrets"
	"github.com/flowcatalyst/framework/go/usecase"
)

// The gauges the gauge loop publishes.
const (
	// ActiveGauge counts the tickets somebody still has to resolve, across every client.
	ActiveGauge = "tickets_active_count"
	// OverdueGauge counts the active tickets the sweep has flagged, across every client.
	OverdueGauge = "tickets_overdue_count"
)

// GaugeInterval is how often the gauges are refreshed.
const GaugeInterval = 30 * time.Second

// NotifierClient is the named HTTP client; its base URL is http-clients.instances.notifier.base-url.
const NotifierClient = "notifier"

// notifyKey is the reference of a client's key for the notification service (the env store).
var notifyKey = mustRef("notify-key")

// mustRef is secrets.MustParseRef, which fwvet's literal check does not know yet (friction G76-7).
func mustRef(name string) secrets.Ref {
	r, err := secrets.NewRef("", name)
	if err != nil {
		panic(err)
	}
	return r
}

// assignedData is the data of the assignment event, as the outbox wrote it.
type assignedData struct {
	TicketID   string `json:"ticketId"`
	Subject    string `json:"subject"`
	AssigneeID string `json:"assigneeId"`
	AssignedBy string `json:"assignedBy"`
}

// notification is the request body the notification service takes.
type notification struct {
	To         string `json:"to"`
	TicketID   string `json:"ticketId"`
	Subject    string `json:"subject"`
	AssignedBy string `json:"assignedBy"`
}

// Notifier is the dispatch receiver for tickets-trial:support:ticket:assigned (through the
// notify-assignee subscription). It tells the new assignee through the notification service, as the
// delivery's client: the credential is that client's own key, resolved by the secrets resolver, never
// one shared by every client.
//
// A delivery can arrive more than once, so the request carries the job id as its idempotency key and
// the notification service drops a repeat. flowcatalyst.Once would not help: it makes a database write
// and its record atomic, and an HTTP call is not part of that transaction.
//
// What each outcome answers to the platform: sent, or the assignee assigned the ticket to themselves:
// ack; the client has no notification key, or the service refused the request (a 4xx other than 429):
// reject, which is terminal and shows on the dispatch job (the reason names no secret); the service is
// unavailable (429, 5xx, no answer): the handler returns an error, which is a transient failure the
// platform retries with its backoff and within the job's retry budget.
type Notifier struct {
	Clients *module.Clients
	Secrets *secrets.Resolver
	Log     *slog.Logger
}

// Handle is the dispatch handler.
func (n *Notifier) Handle(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[assignedData]) (flowcatalyst.DispatchResult, error) {
	data := d.Data
	if !d.HasData || data.AssigneeID == "" || data.TicketID == "" {
		return flowcatalyst.Reject{Reason: "the delivery has no assignee or ticket"}, nil
	}
	if data.AssigneeID == data.AssignedBy {
		return flowcatalyst.Ack{}, nil // assigning a ticket to yourself tells nobody anything
	}
	found, err := n.Secrets.Resolve(ctx, cx.Client(), notifyKey)
	if err != nil {
		return nil, err // the store cannot be read: transient
	}
	key, ok := found.(secrets.Found)
	if !ok {
		n.Log.WarnContext(ctx, "client has no notification key; job rejected", "client", cx.Client().String(), "job", d.Meta.JobID)
		return flowcatalyst.Reject{Reason: "this client has no notification key"}, nil
	}
	notifier, err := n.Clients.For(NotifierClient, cx)
	if err != nil {
		return nil, err
	}
	response, err := notifier.Post("/notifications").
		Header("Authorization", "Bearer "+key.Secret.Reveal()).
		IdempotencyKey(d.Meta.JobID).
		JSON(notification{To: data.AssigneeID, TicketID: data.TicketID, Subject: data.Subject, AssignedBy: data.AssignedBy}).
		Send(ctx)
	if err != nil {
		return nil, err // no answer: transient
	}
	switch status := response.Status(); {
	case response.IsSuccess():
		return flowcatalyst.Ack{}, nil
	case status >= 400 && status < 500 && status != 429:
		return flowcatalyst.Reject{Reason: fmt.Sprintf("the notification service refused the request (%d)", status)}, nil
	default:
		return nil, fmt.Errorf("the notification service answered %d", status)
	}
}

// SweepBatch is how many tickets the sweep looks at per query.
const SweepBatch = 200

// sweepResult is what a firing found, for the completion report, in the Java order.
type sweepResult struct {
	Flagged int `json:"flagged"`
	Skipped int `json:"skipped"`
}

// Sweeper is the scheduled-job receiver for sla-sweep: it finds the clients that have tickets that
// ran out of time, then, one client at a time with that client's own context (fw.EachClient),
// flags each ticket with the flag-sla-breach use case as the application's service principal. The
// framework holds the overlap lock (scheduled-job:sla-sweep, from the default lock store) around the
// whole run, so one replica sweeps at a time; the use case is idempotent anyway.
//
// A firing sweeps in batches until nothing new is found. A ticket the use case fails on would be found
// again, so each ticket is tried once per firing and the firing reports a failure at the end; the next
// firing tries again.
type Sweeper struct {
	DB      *database.DB
	Service *Service
	Clock   fw.Clock
	Log     *slog.Logger
}

type counts struct{ flagged, skipped, failed int }

// Run is the job handler.
func (s *Sweeper) Run(ctx context.Context, cx fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
	// The firing's context is the platform's (_platform): the sweep acts for one client at a time,
	// each with that client's own context, so the queries are scoped and the use case authorizes the
	// service principal as it does for any caller.
	clients, err := OverdueClients(ctx, s.DB, s.Clock.Now())
	if err != nil {
		return nil, err
	}
	var total counts
	for _, r := range fw.EachClient(ctx, s.Log, cx, clients, s.sweepClient) {
		if r.Failed() {
			total.failed++ // fw.EachClient logged why
			continue
		}
		total.flagged += r.Value.flagged
		total.skipped += r.Value.skipped
		total.failed += r.Value.failed
	}
	f.Log(flowcatalyst.LogInfo, fmt.Sprintf("flagged %d, skipped %d, failed %d", total.flagged, total.skipped, total.failed))
	if total.failed > 0 {
		return flowcatalyst.Failure{Message: fmt.Sprintf("%d tickets could not be flagged", total.failed)}, nil
	}
	return flowcatalyst.Success{Value: sweepResult{Flagged: total.flagged, Skipped: total.skipped}}, nil
}

// sweepClient is one client's sweep, in batches until nothing new is found.
func (s *Sweeper) sweepClient(ctx context.Context, cx fw.Context) (counts, error) {
	tried := map[string]struct{}{}
	var c counts
	for ctx.Err() == nil {
		batch, err := OverdueFor(ctx, s.DB, cx.Client(), s.Clock.Now(), SweepBatch)
		if err != nil {
			return c, err
		}
		progress := false
		for _, o := range batch {
			if _, seen := tried[o.ID.String()]; seen {
				continue
			}
			tried[o.ID.String()] = struct{}{}
			progress = true
			_, err := s.Service.FlagSLABreach.Run(ctx, cx, FlagSLABreachCmd{TicketID: o.ID.String()})
			switch ue, isUseCase := usecase.AsError(err); {
			case err == nil:
				c.flagged++
			case isUseCase && (ue.Code() == NotOverdue.String() || ue.Code() == TicketNotFound.String()):
				c.skipped++ // resolved or flagged since the query: nothing to do
			default:
				c.failed++
				s.Log.ErrorContext(ctx, "flagging ticket failed", "ticket", o.ID.String(), "error", err)
			}
		}
		if !progress {
			break
		}
	}
	return c, nil
}

// Gauges publishes how many tickets are active and overdue across every client, from a loop that one
// replica at a time runs (a lock gate); each replica exports what the last run found.
func Gauges(b *fw.Boot, db *database.DB, all *locks.Locks, interval time.Duration) error {
	var active, overdue atomic.Int64
	if err := b.Metrics().GaugeFunc(ActiveGauge, "Tickets somebody still has to resolve, across every client.", func() float64 { return float64(active.Load()) }); err != nil {
		return err
	}
	if err := b.Metrics().GaugeFunc(OverdueGauge, "Active tickets the SLA sweep flagged, across every client.", func() float64 { return float64(overdue.Load()) }); err != nil {
		return err
	}
	gate, err := locks.ServiceGate(all, "ticket-gauges", time.Minute)
	if err != nil {
		return err
	}
	b.Loop("ticket-gauges", background.Options{Interval: interval, Gate: gate}, func(ctx context.Context, _ background.Tick) error {
		open, late, err := Totals(ctx, db)
		if err != nil {
			return err
		}
		active.Store(open)
		overdue.Store(late)
		return nil
	})
	return nil
}

// RegisterReceivers registers the two handlers: in the build function, before Platform.Routes seals the
// registry.
func RegisterReceivers(fc *flowcatalyst.Platform, n *Notifier, s *Sweeper) error {
	if err := flowcatalyst.OnDispatch(fc.Receivers(), Assigned.String(), n.Handle); err != nil {
		return err
	}
	return fc.Receivers().OnScheduledJob(SLASweepJob, flowcatalyst.JobOptions{}, s.Run)
}
