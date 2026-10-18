package tickets

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/internal/dbq"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
)

// Store is what the use cases load and write tickets with: [Repository], or a test's wrapper.
type Store = usecase.Repository[domain.Ticket, domain.TicketID]

// Repository stores tickets with sqlc's generated queries (sql/tickets.sql). The root row carries
// the version. Comments are append-only, so an update writes only the positions that are not stored
// yet (the version guard means one writer at a time reaches that count).
type Repository struct{}

var _ Store = Repository{}

// Find reads a ticket of the client; false when there is none.
func (Repository) Find(ctx context.Context, db *database.DB, client fw.ClientID, ticketID domain.TicketID) (usecase.Loaded[domain.Ticket], bool, error) {
	q := dbq.New(db.Pool())
	h, err := q.FindHeader(ctx, dbq.FindHeaderParams{ID: ticketID, ClientID: client.String()})
	if errors.Is(err, pgx.ErrNoRows) {
		return usecase.Loaded[domain.Ticket]{}, false, nil
	}
	if err != nil {
		return usecase.Loaded[domain.Ticket]{}, false, fmt.Errorf("ticket %s: %w", ticketID, err)
	}
	rows, err := q.CommentsOf(ctx, ticketID)
	if err != nil {
		return usecase.Loaded[domain.Ticket]{}, false, fmt.Errorf("ticket %s comments: %w", ticketID, err)
	}
	comments := make([]domain.Comment, 0, len(rows))
	for _, r := range rows {
		cid, err := id.Parse[domain.CommentKind](r.CommentID)
		if err != nil {
			return usecase.Loaded[domain.Ticket]{}, false, fmt.Errorf("ticket %s: stored comment id: %w", ticketID, err)
		}
		comments = append(comments, domain.Comment{ID: cid, AuthorID: r.AuthorID, Body: r.Body, PostedAt: r.PostedAt})
	}
	t := domain.Ticket{
		ID: ticketID, Subject: h.Subject, Description: h.Description, Priority: domain.Priority(h.Priority),
		Status: domain.Status(h.Status), RequesterID: h.RequesterID, AssigneeID: deref(h.AssigneeID),
		OpenedAt: h.OpenedAt, DueAt: h.DueAt, BreachedAt: h.BreachedAt, ResolvedAt: h.ResolvedAt,
		Resolution: deref(h.Resolution), Comments: comments,
	}
	if !t.Priority.Valid() || !t.Status.Valid() {
		return usecase.Loaded[domain.Ticket]{}, false, fmt.Errorf("ticket %s: unknown stored priority %q or status %q", ticketID, h.Priority, h.Status)
	}
	return usecase.LoadedFrom(client, h.Version, t), true, nil
}

// Insert stores a new ticket at version 1, with its comments.
func (Repository) Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, t domain.Ticket) error {
	q := dbq.New(tx.Conn())
	if err := q.InsertTicket(ctx, dbq.InsertTicketParams{
		ID: t.ID, ClientID: client.String(), Subject: t.Subject, Description: t.Description,
		Priority: string(t.Priority), Status: string(t.Status), RequesterID: t.RequesterID,
		AssigneeID: nullable(t.AssigneeID), OpenedAt: t.OpenedAt, DueAt: t.DueAt, BreachedAt: t.BreachedAt,
		ResolvedAt: t.ResolvedAt, Resolution: nullable(t.Resolution),
	}); err != nil {
		return err
	}
	return writeComments(ctx, q, t, 0)
}

// Update writes the ticket at v.Next where it still is at v.Expected, then the comments not stored yet.
func (Repository) Update(ctx context.Context, tx *database.Tx, client fw.ClientID, t domain.Ticket, v usecase.VersionCheck) (int64, error) {
	q := dbq.New(tx.Conn())
	n, err := q.UpdateTicket(ctx, dbq.UpdateTicketParams{
		NewVersion: v.Next, Subject: t.Subject, Description: t.Description, Priority: string(t.Priority),
		Status: string(t.Status), AssigneeID: nullable(t.AssigneeID), DueAt: t.DueAt, BreachedAt: t.BreachedAt,
		ResolvedAt: t.ResolvedAt, Resolution: nullable(t.Resolution),
		ID: t.ID, ClientID: client.String(), Version: v.Expected,
	})
	if err != nil || n != 1 {
		return n, err // 0: stale, the runner applies the policy; above 1: the runner fails it
	}
	stored, err := q.CommentCount(ctx, t.ID)
	if err != nil {
		return n, err
	}
	return n, writeComments(ctx, q, t, int(stored))
}

// Delete deletes the ticket at v.Expected; the comments go by ON DELETE CASCADE.
func (Repository) Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, t domain.Ticket, v usecase.VersionCheck) (int64, error) {
	return dbq.New(tx.Conn()).DeleteTicket(ctx, dbq.DeleteTicketParams{ID: t.ID, ClientID: client.String(), Version: v.Expected})
}

func writeComments(ctx context.Context, q *dbq.Queries, t domain.Ticket, stored int) error {
	for position := stored; position < len(t.Comments); position++ {
		c := t.Comments[position]
		if err := q.InsertComment(ctx, dbq.InsertCommentParams{
			TicketID: t.ID, Position: int32(position), //nolint:gosec // a handful of comments
			CommentID: c.ID.String(), AuthorID: c.AuthorID, Body: c.Body, PostedAt: c.PostedAt,
		}); err != nil {
			return err
		}
	}
	return nil
}

func nullable(s string) *string {
	if s == "" {
		return nil
	}
	return &s
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}
