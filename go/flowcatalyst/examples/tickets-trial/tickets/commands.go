package tickets

import (
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/usecase"
)

// OpenTicketCmd opens a ticket.
type OpenTicketCmd struct {
	Subject     string
	Description string
	Priority    domain.Priority
}

// AuditData records the subject and priority.
func (c OpenTicketCmd) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("subject", c.Subject), usecase.Field("priority", string(c.Priority))}
}

// AssignTicketCmd gives a ticket to someone.
type AssignTicketCmd struct {
	TicketID   string
	AssigneeID string
}

// AuditData records the ticket and the assignee.
func (c AssignTicketCmd) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("ticketId", c.TicketID), usecase.Field("assigneeId", c.AssigneeID)}
}

// ChangeStatusCmd moves a ticket along its status machine. Note is the resolution (for resolve) or
// the reason (for reopen); nil for the others.
type ChangeStatusCmd struct {
	TicketID string
	Action   domain.Action
	Note     *string
}

// AuditData records the ticket and the action.
func (c ChangeStatusCmd) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("ticketId", c.TicketID), usecase.Field("action", string(c.Action))}
}

// AddCommentCmd comments on a ticket.
type AddCommentCmd struct {
	TicketID string
	Body     string
}

// AuditData records the ticket.
func (c AddCommentCmd) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("ticketId", c.TicketID)}
}

// FlagSLABreachCmd marks a ticket whose SLA deadline has passed (the sweep's command).
type FlagSLABreachCmd struct {
	TicketID string
}

// AuditData records the ticket.
func (c FlagSLABreachCmd) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("ticketId", c.TicketID)}
}

// SetSLAPolicyCmd sets the client's SLA policy.
type SetSLAPolicyCmd struct {
	Minutes SlaMinutes
}

// AuditData records the four times.
func (c SetSLAPolicyCmd) AuditData() []usecase.AuditField {
	return []usecase.AuditField{
		usecase.Field("low", c.Minutes.Low), usecase.Field("normal", c.Minutes.Normal),
		usecase.Field("high", c.Minutes.High), usecase.Field("urgent", c.Minutes.Urgent),
	}
}
