package main

import (
	"context"
	"fmt"
	"io"
	"io/fs"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authdb"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/dbq"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/web"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
	"github.com/flowcatalyst/framework/go/usecase"
)

func newApp(opts ...fw.Option) *fw.App { return fw.New("orders-demo", opts...) }

type declared struct {
	db     *database.Declared
	outbox *usecase.Declared
	web    *httpfw.Declared
	auth   *auth.Declared
}

// declare declares what the demo uses, in the Java demo's module order: database, outbox, orders,
// then the HTTP module with the orders API, the session table's migration and the auth module
// (sessions and login, and the session endpoints' document). Everything the
// openapi command needs is declared here, so `orders-demo openapi` needs no database or
// configuration.
func declare(app *fw.App) declared {
	d := declareScenario(app)
	d.web = httpfw.Declare(app)
	orders.DeclareAPI(d.web)
	authdb.Declare(app)
	d.auth = auth.Declare(app, d.web)
	return d
}

// declareScenario declares only what the command-line scenario (`orders-demo` with no arguments)
// uses: the database, the outbox and the orders. It needs no HTTP or auth settings.
func declareScenario(app *fw.App) declared {
	d := declared{db: database.Declare(app), outbox: usecase.Declare(app)}
	orders.Declare(app)
	return d
}

type deps struct {
	out    io.Writer
	client fw.ClientID // the demo's client; "demo" when zero
	built  func(b *fw.Boot, r *usecase.Runner, db *database.DB)

	// The next three are for the tests and the end-to-end launcher (e2e.go); a plain `serve` leaves
	// them zero.
	authOptions []auth.Option                    // after the demo's own, so they win
	spa         fs.FS                            // the single-page application; the embedded build when nil
	served      func(*auth.Module, *database.DB) // called once the server is built
}

// roles are the demo's roles and what each grants (the Java OrdersWeb table): the clerk and the viewer
// use the orders API; the administrator and the auditor are for the admin area (G6), which Go does not
// have yet, so only the orders roles are used by anything here.
var roles = map[string][]string{
	"orders-demo:clerk":   {orders.Read.String(), orders.Manage.String()},
	"orders-demo:viewer":  {orders.Read.String()},
	"orders-demo:admin":   {"orders-demo:admin:*:*"},
	"orders-demo:auditor": {"orders-demo:admin:console:view", "orders-demo:admin:users:view", "orders-demo:admin:activity:view"},
}

// buildWeb is build for `orders-demo serve`: the same services, then the HTTP server with the
// orders API behind sessions and login (the database session store, the platform as identity
// provider), and the embedded single-page application as the router's fallback.
func buildWeb(d declared, x deps) fw.BuildFunc {
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
		failures, err := auth.RedirectFailuresTo("/sign-in-failed")
		if err != nil {
			return nil, err
		}
		opts := append([]auth.Option{
			auth.SessionStore(authdb.Factory(dbs)),
			auth.Application(fw.MustApplicationCode("orders-demo")),
			auth.PostLogoutRedirect("/signed-out"),
			auth.FailureHandler(failures),
			auth.Permissions(auth.ScopesMapper().And(auth.ByRole(roles))),
		}, x.authOptions...)
		mod, err := d.auth.Build(ctx, b, opts...)
		if err != nil {
			return nil, err
		}
		rt := httpfw.NewRoutes("orders-api")
		(&orders.API{Service: svc, DB: db, Store: orders.Repository{}}).Bind(rt)
		dist := x.spa
		if dist == nil {
			dist = web.Dist()
		}
		site, err := spa.New(dist, spa.Options{})
		if err != nil {
			return nil, err
		}
		rt.SPA(site)
		srv, err := httpfw.NewServer(b, d.web, []*httpfw.Routes{mod.Routes(), rt}, httpfw.WithResolver(mod.Resolver()))
		if err != nil {
			return nil, err
		}
		if x.served != nil {
			x.served(mod, db)
		}
		return srv, nil
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
