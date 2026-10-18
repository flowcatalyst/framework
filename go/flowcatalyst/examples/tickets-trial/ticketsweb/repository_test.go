package ticketsweb

import (
	"log/slog"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/tickets"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
	"github.com/flowcatalyst/framework/go/usecase/usecasetest"
)

// The ticket repository keeps the write-path contract (section 2): version 1 on insert, find filters by
// client, a stale update or delete touches no row. The framework's contract tests run against it; the
// Java service has no such test (friction G76-12: the Java suite relies on the use case tests).
func TestTheTicketRepositoryKeepsTheWritePathContract(t *testing.T) {
	t.Parallel()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	err = database.ApplyMigrations(t.Context(), db, []fw.MigrationSet{
		{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()},
		{Module: tickets.ModuleID, Files: tickets.Migrations()},
	}, slog.New(slog.DiscardHandler))
	if err != nil {
		t.Fatal(err)
	}
	ids := fw.NewIds(fwtest.NewClock(time.UnixMilli(1_760_000_000_000)))
	opened := time.Date(2026, 10, 17, 9, 0, 0, 0, time.UTC)
	usecasetest.Repository(t, usecasetest.Harness[domain.Ticket, domain.TicketID]{
		Repo: tickets.Repository{},
		DB:   db,
		New: func() (domain.Ticket, domain.TicketID) {
			tk := domain.NewTicket(id.New[domain.TicketKind](ids), "Printer", "smoke", domain.High, "prn_ada", opened, opened.Add(time.Hour))
			return tk, tk.ID
		},
		Change: func(tk domain.Ticket) domain.Ticket { return tk.AssignedTo("agent_1") },
		Equal: func(a, b domain.Ticket) bool {
			return a.ID == b.ID && a.Subject == b.Subject && a.Priority == b.Priority && a.Status == b.Status &&
				a.AssigneeID == b.AssigneeID && a.OpenedAt.Equal(b.OpenedAt) && a.DueAt.Equal(b.DueAt)
		},
	})
}
