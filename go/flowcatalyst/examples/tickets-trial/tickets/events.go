package tickets

import (
	"time"

	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
)

// The event types (the Java TicketEvents).
var (
	// Opened is tickets-trial:support:ticket:opened.
	Opened = event.MustType("tickets-trial:support:ticket:opened")
	// Assigned is tickets-trial:support:ticket:assigned: the platform delivers it to the notification handler.
	Assigned = event.MustType("tickets-trial:support:ticket:assigned")
	// StatusChanged is tickets-trial:support:ticket:status-changed.
	StatusChanged = event.MustType("tickets-trial:support:ticket:status-changed")
	// Commented is tickets-trial:support:ticket:commented.
	Commented = event.MustType("tickets-trial:support:ticket:commented")
	// SLABreached is tickets-trial:support:ticket:sla-breached.
	SLABreached = event.MustType("tickets-trial:support:ticket:sla-breached")
	// PolicyChanged is tickets-trial:support:sla-policy:changed.
	PolicyChanged = event.MustType("tickets-trial:support:sla-policy:changed")
)

// The events' JSON keys are the Java records' components, so the outbox row's data is the same;
// an absent value is left out (the Java OutboxWriter leaves nulls out).

// TicketOpened is the result of open-ticket.
type TicketOpened struct {
	Meta        event.Metadata  `json:"-"`
	TicketID    string          `json:"ticketId"`
	Subject     string          `json:"subject"`
	Priority    domain.Priority `json:"priority"`
	RequesterID string          `json:"requesterId"`
	DueAt       time.Time       `json:"dueAt"`
}

// Metadata returns the event's metadata.
func (e TicketOpened) Metadata() event.Metadata { return e.Meta }

// TicketAssigned is the result of assign-ticket.
type TicketAssigned struct {
	Meta               event.Metadata `json:"-"`
	TicketID           string         `json:"ticketId"`
	Subject            string         `json:"subject"`
	AssigneeID         string         `json:"assigneeId"`
	PreviousAssigneeID *string        `json:"previousAssigneeId,omitzero"`
	AssignedBy         string         `json:"assignedBy"`
}

// Metadata returns the event's metadata.
func (e TicketAssigned) Metadata() event.Metadata { return e.Meta }

// TicketStatusChanged is the result of change-status.
type TicketStatusChanged struct {
	Meta     event.Metadata `json:"-"`
	TicketID string         `json:"ticketId"`
	Action   domain.Action  `json:"action"`
	From     domain.Status  `json:"from"`
	To       domain.Status  `json:"to"`
	Note     *string        `json:"note,omitzero"`
}

// Metadata returns the event's metadata.
func (e TicketStatusChanged) Metadata() event.Metadata { return e.Meta }

// TicketCommented is the result of add-comment.
type TicketCommented struct {
	Meta      event.Metadata `json:"-"`
	TicketID  string         `json:"ticketId"`
	CommentID string         `json:"commentId"`
	AuthorID  string         `json:"authorId"`
}

// Metadata returns the event's metadata.
func (e TicketCommented) Metadata() event.Metadata { return e.Meta }

// TicketBreached is the result of flag-sla-breach.
type TicketBreached struct {
	Meta       event.Metadata  `json:"-"`
	TicketID   string          `json:"ticketId"`
	Subject    string          `json:"subject"`
	Priority   domain.Priority `json:"priority"`
	DueAt      time.Time       `json:"dueAt"`
	AssigneeID *string         `json:"assigneeId,omitzero"`
}

// Metadata returns the event's metadata.
func (e TicketBreached) Metadata() event.Metadata { return e.Meta }

// SLAPolicyChanged is the result of set-sla-policy.
type SLAPolicyChanged struct {
	Meta          event.Metadata `json:"-"`
	PolicyID      string         `json:"policyId"`
	LowMinutes    int32          `json:"lowMinutes"`
	NormalMinutes int32          `json:"normalMinutes"`
	HighMinutes   int32          `json:"highMinutes"`
	UrgentMinutes int32          `json:"urgentMinutes"`
}

// Metadata returns the event's metadata.
func (e SLAPolicyChanged) Metadata() event.Metadata { return e.Meta }
