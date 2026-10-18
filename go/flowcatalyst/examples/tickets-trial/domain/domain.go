// Package domain is the tickets service's domain: typed ids, the closed sets (priority, status,
// action), the status machine and the ticket aggregate with its comments. It knows nothing of
// storage; the client and version of a stored ticket live in usecase.Loaded.
package domain

import (
	"slices"
	"time"

	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
)

// TicketKind gives ticket ids their prefix (tkt_ and a TSID).
type TicketKind struct{}

// Prefix returns "tkt".
func (TicketKind) Prefix() string { return "tkt" }

// CommentKind gives comment ids their prefix (cmt_ and a TSID).
type CommentKind struct{}

// Prefix returns "cmt".
func (CommentKind) Prefix() string { return "cmt" }

// TicketID is a ticket's id. sqlc.yaml names this alias as the type of the tickets.id and
// ticket_comments.ticket_id columns.
type TicketID = id.ID[TicketKind]

// CommentID is a comment's id.
type CommentID = id.ID[CommentKind]

// Priority is how urgent a ticket is; the SLA gives each priority its own time to resolve.
type Priority string

// The priorities.
const (
	Low    Priority = "LOW"
	Normal Priority = "NORMAL"
	High   Priority = "HIGH"
	Urgent Priority = "URGENT"
)

// EnumValues implements openapi.EnumValuer.
func (Priority) EnumValues() []string { return []string{"LOW", "NORMAL", "HIGH", "URGENT"} }

// Valid reports whether p is one of the priorities.
func (p Priority) Valid() bool { return slices.Contains(p.EnumValues(), string(p)) }

// Status is where a ticket is in its life. The machine is one table, [Status.After]:
//
//	OPEN --START--> IN_PROGRESS --RESOLVE--> RESOLVED --CLOSE--> CLOSED
//	 ^                                          |
//	 +-----------------REOPEN-------------------+
type Status string

// The statuses.
const (
	Open       Status = "OPEN"
	InProgress Status = "IN_PROGRESS"
	Resolved   Status = "RESOLVED"
	Closed     Status = "CLOSED"
)

// EnumValues implements openapi.EnumValuer.
func (Status) EnumValues() []string { return []string{"OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"} }

// Valid reports whether s is one of the statuses.
func (s Status) Valid() bool { return slices.Contains(s.EnumValues(), string(s)) }

// Action is what an agent can do to move a ticket along its status machine.
type Action string

// The actions.
const (
	// Start: an assigned open ticket becomes in progress.
	Start Action = "START"
	// Resolve: an in-progress ticket becomes resolved, with a resolution.
	Resolve Action = "RESOLVE"
	// Reopen: a resolved ticket is open again.
	Reopen Action = "REOPEN"
	// Close: a resolved ticket is closed for good.
	Close Action = "CLOSE"
)

// EnumValues implements openapi.EnumValuer.
func (Action) EnumValues() []string { return []string{"START", "RESOLVE", "REOPEN", "CLOSE"} }

// Valid reports whether a is one of the actions.
func (a Action) Valid() bool { return slices.Contains(a.EnumValues(), string(a)) }

// After is the status an action leads to from s; false when the action is not allowed from here.
func (s Status) After(a Action) (Status, bool) {
	switch a {
	case Start:
		return InProgress, s == Open
	case Resolve:
		return Resolved, s == InProgress
	case Reopen:
		return Open, s == Resolved
	case Close:
		return Closed, s == Resolved
	}
	return "", false
}

// IsActive reports whether the SLA clock is running: a ticket that someone still has to resolve.
func (s Status) IsActive() bool { return s == Open || s == InProgress }

// Comment is one comment on a ticket. Comments are never edited or removed.
type Comment struct {
	ID       CommentID
	AuthorID string
	Body     string
	PostedAt time.Time
}

// Ticket is the aggregate. Its comments belong to it: they are saved with it and covered by its
// version. Methods that change it return a new value and write nothing.
type Ticket struct {
	ID          TicketID
	Subject     string
	Description string
	Priority    Priority
	Status      Status
	RequesterID string
	// AssigneeID is the principal working on it; "" when nobody.
	AssigneeID string
	OpenedAt   time.Time
	// DueAt is the SLA deadline, fixed when the ticket was opened.
	DueAt time.Time
	// BreachedAt is when the SLA sweep noticed the deadline had passed; nil when it did not.
	BreachedAt *time.Time
	// ResolvedAt and Resolution are set while the ticket is resolved.
	ResolvedAt *time.Time
	Resolution string
	// Comments are in the order they were posted.
	Comments []Comment
}

// NewTicket returns a new open ticket without an assignee.
func NewTicket(tid TicketID, subject, description string, priority Priority, requester string, now, dueAt time.Time) Ticket {
	return Ticket{
		ID: tid, Subject: subject, Description: description, Priority: priority, Status: Open,
		RequesterID: requester, OpenedAt: now, DueAt: dueAt,
	}
}

// AssignedTo returns the ticket with another assignee.
func (t Ticket) AssignedTo(assignee string) Ticket {
	t.AssigneeID = assignee
	return t
}

// Moved returns the ticket after an action; the caller has checked that the status allows it. note
// is the resolution for [Resolve] and ignored by the others.
func (t Ticket) Moved(next Status, a Action, note string, now time.Time) Ticket {
	switch a {
	case Resolve:
		at := now
		t.ResolvedAt, t.Resolution = &at, note
	case Reopen:
		t.ResolvedAt, t.Resolution = nil, ""
	case Start, Close:
	}
	t.Status = next
	return t
}

// WithComment returns the ticket with one more comment.
func (t Ticket) WithComment(c Comment) Ticket {
	t.Comments = append(slices.Clip(t.Comments), c)
	return t
}

// Breached returns the ticket marked as past its SLA deadline.
func (t Ticket) Breached(now time.Time) Ticket {
	t.BreachedAt = &now
	return t
}

// AuditEntity names the ticket for the audit row.
func (t Ticket) AuditEntity() usecase.Entity {
	return usecase.Entity{Type: "Ticket", ID: t.ID.String()}
}
