package tickets

import "github.com/flowcatalyst/framework/go/flowcatalyst"

// The codes of what the platform is told about.
const (
	// Connection is the code of the connection the subscription delivers through.
	Connection = "main"
	// SLASweepJob is the scheduled job that flags tickets past their deadline.
	SLASweepJob = "sla-sweep"
	// NotifySubscription is the subscription that notifies the assignee.
	NotifySubscription = "notify-assignee"
)

// DefineFor declares what the platform needs to know about the application, in code, pushed by
// `tickets-trial sync` at deploy time: the event types the use cases write, the roles (the same
// [Roles] the sign-in maps to permissions), the connection that delivers to this service, the
// subscription that sends an assignment to the notify handler, and the SLA sweep's schedule.
func DefineFor(d *flowcatalyst.Declared) {
	defs := d.Definitions(ModuleID).
		EventTypeOf(Opened, "Ticket opened", "A requester opened a ticket.").
		EventTypeOf(Assigned, "Ticket assigned", "A ticket was given to an agent.").
		EventTypeOf(StatusChanged, "Ticket status changed", "A ticket moved along its status machine.").
		EventTypeOf(Commented, "Ticket commented", "A comment was posted on a ticket.").
		EventTypeOf(SLABreached, "Ticket SLA breached", "A ticket ran out of time.").
		EventTypeOf(PolicyChanged, "SLA policy changed", "A client changed its SLA policy.").
		Connection(Connection, "Tickets").
		Subscription(flowcatalyst.NewSubscription(NotifySubscription, "Notify the assignee", "/fc/dispatch", Assigned.String()).
			ViaConnection(Connection).
			WithDescription("Tells the agent a ticket was given to them.")).
		// Every minute, seconds first (six fields). The platform reports each firing's completion.
		ScheduledJob(flowcatalyst.NewScheduledJob(SLASweepJob, "SLA sweep", "0 * * * * *").Tracking(true))
	for _, r := range Roles {
		defs.Role(roleDefinition(r))
	}
}
