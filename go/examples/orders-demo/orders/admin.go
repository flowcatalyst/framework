package orders

import (
	"context"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/dbq"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// AdminView is who may read the orders overview (the Java OrdersAdminModule.VIEW).
var AdminView = fw.MustPermission("orders-demo:admin:orders:view")

// Overview (the document's OrdersOverview) is the orders overview: the client's orders by status.
type Overview struct {
	Open           int32 `json:"open"`
	Cancelled      int32 `json:"cancelled"`
	OpenTotalCents int64 `json:"openTotalCents"`
}

// OverviewOp is GET /api/admin/orders/overview: the demo's one admin section of its own, the plug-in
// shape the data sections follow (an overview of the client's orders, from the application's own
// database, no platform data).
var OverviewOp = httpfw.Get[Overview]("/api/admin/orders/overview").
	ID("getAdminOrdersOverview").Summary("Count the client's orders by status").Tag("admin").
	Permission(AdminView).Build()

// AdminSection is the section's navigation entry.
func AdminSection(web *httpfw.Declared) admin.Section {
	httpfw.NameType[Overview](web, "OrdersOverview")
	return admin.NewSection("orders", "Orders overview", "/admin/orders", AdminView.String()).
		WithIcon("i-lucide-chart-no-axes-column").
		WithDescription("How many orders are open and cancelled.").
		Serving(OverviewOp)
}

// BindAdmin binds the overview.
func (a *API) BindAdmin(rt *httpfw.Routes) {
	rt.Bind(httpfw.Handle(OverviewOp, func(ctx context.Context, cx fw.Context, _ httpfw.In[httpfw.NoBody]) (Overview, error) {
		rows, err := dbq.New(a.DB.Pool()).Summaries(ctx, cx.Client().String())
		if err != nil {
			return Overview{}, err
		}
		var out Overview
		for _, r := range rows {
			if r.Status == string(StatusOpen) {
				out.Open++
				out.OpenTotalCents += r.TotalCents
			} else {
				out.Cancelled++
			}
		}
		return out, nil
	}))
}
