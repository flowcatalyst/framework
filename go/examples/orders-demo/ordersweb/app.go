// Package ordersweb is the orders-demo application: the write path of the Java OrdersDemo scenario
// and the web application of the Java OrdersWeb (sessions and login against the platform, the typed
// orders API, the single-page application and, with an [Extension], the admin area), as a library so
// that the command in this directory and the one in the flowcatalyst module (which adds the
// platform-backed admin sections) run the same code.
package ordersweb

import (
	"context"
	"fmt"
	"io"
	"io/fs"

	"github.com/flowcatalyst/framework/go/admin"
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

// NewApp returns the application, named orders-demo.
func NewApp(opts ...fw.Option) *fw.App { return fw.New("orders-demo", opts...) }

// Declared is what the application declared: the database, the outbox, the HTTP module, the auth
// module and, with an [Extension], the admin registry.
type Declared struct {
	db     *database.Declared
	outbox *usecase.Declared
	web    *httpfw.Declared
	auth   *auth.Declared
	adm    *admin.Declared
	ext    Extension
}

// OpenAPI writes the document of everything declared, or every problem found; it needs no
// configuration and no database.
func (d Declared) OpenAPI() (document []byte, problems []string) { return d.web.OpenAPI() }

// Extension adds to the admin area what needs more than the core module: the sections that read the
// platform (the flowcatalyst module's platformadmin). A nil Extension means no admin area at all
// (the Java OrdersWeb.withoutAdmin, whose document is the shared vector openapi/orders-demo.json);
// [NoPlatform] means the admin area without platform sections.
type Extension interface {
	// Declare registers the extension's sections with the admin registry and declares whatever else
	// it needs, at declaration time. It is called between the framework's own `about` section and the
	// config section, which fixes the navigation order (about, the extension's, config, orders).
	Declare(app *fw.App, web *httpfw.Declared, adm *admin.Declared)
	// Build is called in the build function, after the migrations ran and before the server is built;
	// the routes it returns are served with the application's own.
	Build(ctx context.Context, b *fw.Boot, dbs *database.Databases) (*httpfw.Routes, error)
	// Finish is called at declaration time once every section is registered (the config section and
	// the demo's own included), for what needs the whole registry: the definitions the sync sends.
	Finish(adm *admin.Declared)
}

type noPlatform struct{}

func (noPlatform) Declare(*fw.App, *httpfw.Declared, *admin.Declared) {}

func (noPlatform) Finish(*admin.Declared) {}

func (noPlatform) Build(context.Context, *fw.Boot, *database.Databases) (*httpfw.Routes, error) {
	return nil, nil //nolint:nilnil // no routes
}

// NoPlatform is the extension of an application without the platform's sections: the admin area has
// about, config and the demo's own section.
var NoPlatform Extension = noPlatform{}

// Declare declares what the demo uses, in the Java demo's module order: database, outbox, orders,
// then the HTTP module with the orders API, the session table's migration and the auth module
// (sessions and login, and the session endpoints' document) and, when ext is not nil, the admin
// area. Everything the openapi command needs is declared here, so `orders-demo openapi` needs no
// database or configuration.
func Declare(app *fw.App, ext Extension) Declared {
	d := DeclareScenario(app)
	d.web = httpfw.Declare(app)
	orders.DeclareAPI(d.web)
	authdb.Declare(app)
	d.auth = auth.Declare(app, d.web)
	if ext != nil {
		d.ext = ext
		d.adm = admin.Declare(d.web, fw.MustApplicationCode("orders-demo"))
		ext.Declare(app, d.web, d.adm)
		d.adm.Config()
		d.adm.Section(orders.AdminSection(d.web))
		ext.Finish(d.adm)
	}
	return d
}

// DeclareScenario declares only what the command-line scenario (`orders-demo` with no arguments)
// uses: the database, the outbox and the orders. It needs no HTTP or auth settings.
func DeclareScenario(app *fw.App) Declared {
	d := Declared{db: database.Declare(app), outbox: usecase.Declare(app)}
	orders.Declare(app)
	return d
}

// Deps are the parts of the application that tests and the end-to-end launcher replace; a plain
// `serve` leaves them zero.
type Deps struct {
	// Out is where the scenario prints.
	Out io.Writer
	// Client is the demo's client; "demo" when zero.
	Client fw.ClientID
	// Built is called once the scenario's services are built.
	Built func(b *fw.Boot, r *usecase.Runner, db *database.DB)
	// AuthOptions are applied after the demo's own, so they win.
	AuthOptions []auth.Option
	// SPA is the single-page application; the embedded build when nil.
	SPA fs.FS
	// Served is called once the server is built.
	Served func(*auth.Module, *database.DB)
}

// roles are the demo's roles and what each grants (the Java OrdersWeb table): the clerk and the viewer
// use the orders API; the administrator and the auditor are for the admin area.
var roles = map[string][]string{
	"orders-demo:clerk":   {orders.Read.String(), orders.Manage.String()},
	"orders-demo:viewer":  {orders.Read.String()},
	"orders-demo:admin":   {"orders-demo:admin:*:*"},
	"orders-demo:auditor": {"orders-demo:admin:console:view", "orders-demo:admin:users:view", "orders-demo:admin:activity:view"},
}

// BuildWeb is the build function for `orders-demo serve`: the same services, then the HTTP server with the
// orders API behind sessions and login (the database session store, the platform as identity
// provider), and the embedded single-page application as the router's fallback.
func BuildWeb(d Declared, x Deps) fw.BuildFunc {
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
		}, x.AuthOptions...)
		mod, err := d.auth.Build(ctx, b, opts...)
		if err != nil {
			return nil, err
		}
		rt := httpfw.NewRoutes("orders-api")
		api := &orders.API{Service: svc, DB: db, Store: orders.Repository{}}
		api.Bind(rt)
		tables := []*httpfw.Routes{mod.Routes(), rt}
		if d.adm != nil {
			api.BindAdmin(rt)
			adminRoutes, err := d.adm.Build(b)
			if err != nil {
				return nil, err
			}
			tables = append(tables, adminRoutes)
			extRoutes, err := d.ext.Build(ctx, b, dbs)
			if err != nil {
				return nil, err
			}
			if extRoutes != nil {
				tables = append(tables, extRoutes)
			}
		}
		dist := x.SPA
		if dist == nil {
			dist = web.Dist()
		}
		site, err := spa.New(dist, spa.Options{})
		if err != nil {
			return nil, err
		}
		rt.SPA(site)
		srv, err := httpfw.NewServer(b, d.web, tables, httpfw.WithResolver(mod.Resolver()))
		if err != nil {
			return nil, err
		}
		if x.Served != nil {
			x.Served(mod, db)
		}
		return srv, nil
	}
}

// BuildScenario is the build function of the command-line scenario.
func BuildScenario(d Declared, x Deps) fw.BuildFunc {
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
		if x.Built != nil {
			x.Built(b, runner, db)
		}
		client := x.Client
		if client.IsZero() {
			client = fw.MustClientID("demo")
		}
		return fw.ServeFunc(func(ctx context.Context) error {
			report, err := run(ctx, svc, db, client)
			for _, line := range report {
				_, _ = fmt.Fprintln(x.Out, line)
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
