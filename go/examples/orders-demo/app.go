package main

import (
	"context"
	"fmt"
	"io"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/dbq"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/web"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
	"github.com/flowcatalyst/framework/go/usecase"
)

func newApp(opts ...fw.Option) *fw.App { return fw.New("orders-demo", opts...) }

type declared struct {
	db     *database.Declared
	outbox *usecase.Declared
	web    *httpfw.Declared
	token  config.Handle[config.Secret]
}

// declare declares what the demo uses, in the Java demo's module order: database, outbox, orders,
// then the HTTP module with the orders API and the session endpoints' document. Everything the
// openapi command needs is declared here, so `orders-demo openapi` needs no database or
// configuration.
func declare(app *fw.App) declared {
	d := declared{db: database.Declare(app), outbox: usecase.Declare(app)}
	orders.Declare(app)
	d.web = httpfw.Declare(app)
	orders.DeclareAPI(d.web)
	sessionapi.Declare(d.web, true)
	d.token = config.Declare(app.Module("orders-api"), config.SecretValue("orders.demo-token",
		"The bearer token that signs a caller in as the demo's clerk, until sessions and login exist (serve only)."))
	return d
}

type deps struct {
	out    io.Writer
	client fw.ClientID // the demo's client; "demo" when zero
	built  func(b *fw.Boot, r *usecase.Runner, db *database.DB)
}

// buildWeb is build for `orders-demo serve`: the same services, then the HTTP server with the
// orders API (a fixed-token resolver stands in for sessions), the session endpoints answering 501
// until they are built, and the embedded single-page application as the router's fallback.
func buildWeb(d declared, x deps) fw.BuildFunc {
	return func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		token, ok := d.token.Lookup(b)
		if !ok || token.Reveal() == "" {
			return nil, fmt.Errorf("orders.demo-token is required to serve (ORDERS_DEMO_TOKEN, or config/secrets.local.yaml)")
		}
		dbs, err := database.Connect(ctx, b, d.db)
		if err != nil {
			return nil, err
		}
		if err := database.Migrate(ctx, b, dbs); err != nil {
			return nil, err
		}
		db, ok := dbs.Default()
		if !ok {
			return nil, fmt.Errorf("no database instance is configured")
		}
		runner, err := usecase.NewRunner(b, d.outbox, db)
		if err != nil {
			return nil, err
		}
		svc, err := orders.NewService(runner, b.Ids(), event.NewFactory(b.Clock(), b.Ids()))
		if err != nil {
			return nil, err
		}
		client := x.client
		if client.IsZero() {
			client = fw.MustClientID("demo")
		}
		rt := httpfw.NewRoutes("orders-api")
		(&orders.API{Service: svc, DB: db, Store: orders.Repository{}}).Bind(rt)
		sessionapi.Unavailable(rt)
		site, err := spa.New(web.Dist(), spa.Options{})
		if err != nil {
			return nil, err
		}
		rt.SPA(site)
		return httpfw.NewServer(b, d.web, []*httpfw.Routes{rt}, httpfw.WithResolver(tokenResolver{token: token, client: client}))
	}
}

func build(d declared, x deps) fw.BuildFunc {
	return func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		dbs, err := database.Connect(ctx, b, d.db)
		if err != nil {
			return nil, err
		}
		if err := database.Migrate(ctx, b, dbs); err != nil {
			return nil, err
		}
		db, ok := dbs.Default()
		if !ok {
			return nil, fmt.Errorf("no database instance is configured")
		}
		runner, err := usecase.NewRunner(b, d.outbox, db)
		if err != nil {
			return nil, err
		}
		svc, err := orders.NewService(runner, b.Ids(), event.NewFactory(b.Clock(), b.Ids()))
		if err != nil {
			return nil, err
		}
		if x.built != nil {
			x.built(b, runner, db)
		}
		client := x.client
		if client.IsZero() {
			client = fw.MustClientID("demo")
		}
		return fw.ServeFunc(func(ctx context.Context) error {
			report, err := run(ctx, svc, db, client)
			for _, line := range report {
				_, _ = fmt.Fprintln(x.out, line)
			}
			return err
		}), nil
	}
}

// run is the Java demo's scenario.
func run(ctx context.Context, svc *orders.Service, db *database.DB, client fw.ClientID) ([]string, error) {
	cx := fw.ContextFor(client).WithPrincipal(fw.MustPrincipalID("usr_demo"))
	var report []string
	customer, err := domain.ParseCustomerName("Ada Lovelace")
	if err != nil {
		return nil, err
	}
	placed, err := svc.PlaceOrder.Run(ctx, cx, orders.PlaceOrder{Customer: customer})
	report = append(report, "place: "+describe(err, orders.Placed.String()))
	if err != nil {
		return report, nil
	}
	orderID := placed.Event().OrderID
	add := func(sku string, qty, price int64) string {
		cmd, err := orders.ParseAddLine(orderID, sku, qty, price)
		if err != nil {
			return describe(err, "")
		}
		_, err = svc.AddLine.Run(ctx, cx, cmd)
		return describe(err, orders.LineAdded.String())
	}
	report = append(report, "add: "+add("ANALYTICAL-ENGINE", 1, 120_000), "add: "+add("PUNCH-CARDS", 500, 3))
	reason, err := domain.ParseCancelReason("changed my mind")
	if err != nil {
		return nil, err
	}
	_, err = svc.CancelOrder.Run(ctx, cx, orders.CancelOrder{Order: orderID, Reason: reason})
	report = append(report, "cancel: "+describe(err, orders.Cancelled.String()))
	report = append(report, "add after cancel: "+add("PUNCH-CARDS", 1, 3))
	summaries, err := dbq.New(db.Pool()).Summaries(ctx, client.String())
	if err != nil {
		return report, err
	}
	for _, s := range summaries {
		report = append(report, fmt.Sprintf("order %s %s v%d: %d lines, %d cents", s.ID, s.Status, s.Version, s.LineCount, s.TotalCents))
	}
	return report, nil
}

func describe(err error, committed string) string {
	if err == nil {
		return "committed " + committed
	}
	if ue, ok := usecase.AsError(err); ok {
		return ue.Kind().String() + " " + ue.Code()
	}
	return "failed: " + err.Error()
}
