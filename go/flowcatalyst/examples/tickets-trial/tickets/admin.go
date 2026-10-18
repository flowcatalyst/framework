package tickets

import (
	"context"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// StatusCountView is the tickets of one status; Flagged is how many of those the sweep has flagged as
// past their deadline.
type StatusCountView struct {
	Status  domain.Status `json:"status"`
	Count   int64         `json:"count"`
	Flagged int64         `json:"flagged"`
}

// Overview (the document's TicketsOverview) is the queue overview: the tickets by status, those somebody still has to resolve
// and those of them the sweep has flagged.
type Overview struct {
	Statuses []StatusCountView `json:"statuses"`
	Active   int64             `json:"active"`
	Flagged  int64             `json:"flagged"`
}

// OverviewOp is GET /api/admin/tickets/overview: the service's own admin section (the plug-in shape
// of the admin skeleton): the client's tickets counted by status, from the application's own
// database, no platform data.
var OverviewOp = httpfw.Get[Overview]("/api/admin/tickets/overview").
	ID("getAdminTicketsOverview").Summary("Count the client's tickets by status").Tag("admin").
	Permission(AdminOverview).Build()

// AdminSection is the section's navigation entry.
func AdminSection() admin.Section {
	return admin.NewSection("tickets", "Queue overview", "/admin/tickets", AdminOverview.String()).
		WithIcon("i-lucide-chart-no-axes-column").
		WithDescription("How many tickets are in each status, and how many ran out of time.").
		Serving(OverviewOp)
}

// BindAdmin binds the overview.
func (a *API) BindAdmin(rt *httpfw.Routes) {
	rt.Bind(httpfw.Handle(OverviewOp, func(ctx context.Context, cx fw.Context, _ httpfw.In[httpfw.NoBody]) (Overview, error) {
		counts, err := Counts(ctx, a.DB, cx.Client())
		if err != nil {
			return Overview{}, err
		}
		out := Overview{Statuses: make([]StatusCountView, 0, len(counts))}
		for _, c := range counts {
			out.Statuses = append(out.Statuses, StatusCountView(c))
			if c.Status.IsActive() {
				out.Active += c.Count
				out.Flagged += c.Flagged
			}
		}
		return out, nil
	}))
}
