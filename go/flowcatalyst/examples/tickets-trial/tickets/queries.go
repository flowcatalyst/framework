package tickets

import (
	"context"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/internal/dbq"
	"github.com/flowcatalyst/framework/go/fw"
)

// Reads for screens, lists and the background jobs: plain typed queries, not aggregates
// (write-path contract, section 9). Every one but [OverdueClients] and [Totals] is scoped to a
// client.

// Summary is one row of a ticket list.
type Summary struct {
	ID          domain.TicketID
	Subject     string
	Priority    domain.Priority
	Status      domain.Status
	RequesterID string
	AssigneeID  string
	OpenedAt    time.Time
	DueAt       time.Time
	BreachedAt  *time.Time
	Version     int64
}

// SummaryPage is one page of tickets, newest first; NextCursor is the id to pass as After for the next
// page, "" on the last page.
type SummaryPage struct {
	Items      []Summary
	NextCursor string
}

// Filter is the optional filters of a list; a zero field means any.
type Filter struct {
	Status    domain.Status
	Assignee  string
	Requester string
	After     string
}

// List returns a page of a client's tickets, newest first. limit is the page size, 1 to 200.
func List(ctx context.Context, db *database.DB, client fw.ClientID, f Filter, limit int) (SummaryPage, error) {
	size := max(1, min(limit, 200))
	rows, err := dbq.New(db.Pool()).List(ctx, dbq.ListParams{
		ClientID: client.String(), Status: nullable(string(f.Status)), AssigneeID: nullable(f.Assignee),
		RequesterID: nullable(f.Requester), After: nullable(f.After), PageSize: int32(size + 1), //nolint:gosec // at most 201
	})
	if err != nil {
		return SummaryPage{}, err
	}
	out := make([]Summary, 0, len(rows))
	for _, r := range rows {
		out = append(out, Summary{
			ID: r.ID, Subject: r.Subject, Priority: domain.Priority(r.Priority), Status: domain.Status(r.Status),
			RequesterID: r.RequesterID, AssigneeID: deref(r.AssigneeID), OpenedAt: r.OpenedAt, DueAt: r.DueAt,
			BreachedAt: r.BreachedAt, Version: r.Version,
		})
	}
	if len(out) <= size {
		return SummaryPage{Items: out}, nil
	}
	page := out[:size]
	return SummaryPage{Items: page, NextCursor: page[size-1].ID.String()}, nil
}

// OverdueClients returns the clients that have an active ticket whose deadline has passed and that
// was not flagged yet: the one query that is not scoped to a client, and it returns client codes
// only.
func OverdueClients(ctx context.Context, db *database.DB, now time.Time) ([]fw.ClientID, error) {
	rows, err := dbq.New(db.Pool()).OverdueClients(ctx, now)
	if err != nil {
		return nil, err
	}
	out := make([]fw.ClientID, 0, len(rows))
	for _, code := range rows {
		c, err := fw.ParseClientID(code)
		if err != nil {
			return nil, err
		}
		out = append(out, c)
	}
	return out, nil
}

// Overdue is a ticket past its deadline.
type Overdue struct {
	ID    domain.TicketID
	DueAt time.Time
}

// OverdueFor returns a client's active tickets whose deadline has passed and that were not flagged
// yet, oldest deadline first.
func OverdueFor(ctx context.Context, db *database.DB, client fw.ClientID, now time.Time, limit int) ([]Overdue, error) {
	rows, err := dbq.New(db.Pool()).OverdueFor(ctx, dbq.OverdueForParams{ClientID: client.String(), Now: now, PageSize: int32(limit)}) //nolint:gosec // a small batch
	if err != nil {
		return nil, err
	}
	out := make([]Overdue, len(rows))
	for i, r := range rows {
		out[i] = Overdue{ID: r.ID, DueAt: r.DueAt}
	}
	return out, nil
}

// StatusCount is the tickets of one status; Flagged is how many of them the sweep has flagged as past
// their deadline.
type StatusCount struct {
	Status  domain.Status
	Count   int64
	Flagged int64
}

// Counts returns a client's tickets counted by status, one entry per status that has tickets.
func Counts(ctx context.Context, db *database.DB, client fw.ClientID) ([]StatusCount, error) {
	rows, err := dbq.New(db.Pool()).Counts(ctx, client.String())
	if err != nil {
		return nil, err
	}
	out := make([]StatusCount, len(rows))
	for i, r := range rows {
		out[i] = StatusCount{Status: domain.Status(r.Status), Count: r.N, Flagged: r.Overdue}
	}
	return out, nil
}

// Totals returns the open and overdue tickets across every client.
func Totals(ctx context.Context, db *database.DB) (open, overdue int64, err error) {
	r, err := dbq.New(db.Pool()).Totals(ctx)
	if err != nil {
		return 0, 0, err
	}
	return r.Open, r.Overdue, nil
}
