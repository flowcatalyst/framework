// Package ticketsweb is the tickets service as a web application: the write path of the tickets
// package, the HTTP server, sessions and login against the platform, the typed API, the admin area, the
// definitions the platform needs, the receivers the platform calls (a dispatch receiver that notifies an
// assignee, a scheduled-job receiver that runs the SLA sweep), the gauge loop and the single-page
// application built from ui/apps/tickets. It is a library so that the command (main.go), the tests and
// the browser end-to-end launcher run the same code. The Java counterpart is TicketsWeb.
package ticketsweb

import (
	"context"
	"fmt"
	"io/fs"
	"time"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/auth/authdb"
	"github.com/flowcatalyst/framework/go/clientsettings"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/tickets"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/web"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpclient/module"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/spa"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/locks/dblock"
	"github.com/flowcatalyst/framework/go/ratelimit"
	"github.com/flowcatalyst/framework/go/secrets"
	"github.com/flowcatalyst/framework/go/usecase"
)

// NewApp returns the application, named tickets-trial.
func NewApp(opts ...fw.Option) *fw.App { return fw.New(tickets.Application, opts...) }

// CommentLimit is the usual comment limit: thirty a minute per person, a burst of ten.
var CommentLimit = ratelimit.MustPolicy(30, time.Minute, 10)

// Tuning is the numbers a test changes: how often one person may comment, and how often the gauges
// of active and overdue tickets are refreshed.
type Tuning struct {
	CommentLimit  ratelimit.Policy
	GaugeInterval time.Duration
}

// DefaultTuning is the numbers of a deployment: thirty comments a minute and a refresh every thirty seconds.
func DefaultTuning() Tuning {
	return Tuning{CommentLimit: CommentLimit, GaugeInterval: tickets.GaugeInterval}
}

// Declared is what the application declared.
type Declared struct {
	db       *database.Declared
	outbox   *usecase.Declared
	settings *clientsettings.Declared
	web      *httpfw.Declared
	auth     *auth.Declared
	adm      *admin.Declared
	fc       *flowcatalyst.Declared
	locks    *locks.Declared
	secrets  *secrets.Declared
	clients  *module.Declared
}

// OpenAPI writes the document of everything declared, or every problem found; it needs no
// configuration and no database.
func (d Declared) OpenAPI() (document []byte, problems []string) { return d.web.OpenAPI() }

// DeclareCore declares only the write path: the database, the outbox, the client settings and the
// tickets (the Java TicketsCore). It needs no HTTP or auth settings.
func DeclareCore(app *fw.App) Declared {
	d := Declared{db: database.Declare(app), outbox: usecase.Declare(app), settings: clientsettings.Declare(app)}
	tickets.Declare(app)
	return d
}

// Declare declares what the web application uses, in the Java module order: the write path, the HTTP
// module with the tickets API, the session table and the auth module, the admin area (the skeleton, the
// configuration page and the service's own section), the platform with the definitions, the lock
// store, the secrets, the HTTP clients. Everything the openapi command needs is declared here, so
// `tickets-trial openapi` needs no database or configuration.
func Declare(app *fw.App) Declared {
	d := DeclareCore(app)
	d.web = httpfw.Declare(app)
	tickets.DeclareAPI(d.web)
	authdb.Declare(app)
	d.auth = auth.Declare(app, d.web)
	d.adm = admin.Declare(d.web, fw.MustApplicationCode(tickets.Application))
	d.adm.Config()
	d.adm.Section(tickets.AdminSection())
	d.fc = flowcatalyst.Declare(app)
	tickets.DefineFor(d.fc)
	d.fc.AdminDefinitions(d.adm)
	d.locks = locks.Declare(app)
	dblock.Declare(app)
	d.secrets = secrets.Declare(app)
	d.clients = module.Declare(app)
	return d
}

// Deps are the parts of the application that tests and the end-to-end launcher replace; a plain
// `serve` leaves them zero.
type Deps struct {
	// Tuning is the numbers a test changes; DefaultTuning when zero.
	Tuning *Tuning
	// AuthOptions are applied after the service's own, so they win.
	AuthOptions []auth.Option
	// SPA is the single-page application; the embedded build when nil.
	SPA fs.FS
	// Policies are the use cases' conflict policies; DefaultPolicies when nil.
	Policies *tickets.Policies
	// Store replaces the ticket repository (a test's gated wrapper).
	Store tickets.Store
	// Served is called once the server is built.
	Served func(*Built)
}

// Built is what the build function made, for tests and the launcher.
type Built struct {
	Auth    *auth.Module
	DB      *database.DB
	Service *tickets.Service
	Locks   *locks.Locks
	// Platform is the platform module (the sync, the receivers).
	Platform *flowcatalyst.Platform
	// Runner and Deps are what the service was defined with, for a test that defines another one.
	Runner *usecase.Runner
	Deps   tickets.Deps
}

// BuildWeb is the build function for `tickets-trial serve`.
func BuildWeb(d Declared, x Deps) fw.BuildFunc {
	return func(ctx context.Context, b *fw.Boot) (fw.Serve, error) {
		tuning := DefaultTuning()
		if x.Tuning != nil {
			tuning = *x.Tuning
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
		var store tickets.Store = tickets.Repository{}
		if x.Store != nil {
			store = x.Store
		}
		policies, err := policiesOf(x)
		if err != nil {
			return nil, err
		}
		deps := tickets.Deps{Tickets: store, Ids: b.Ids(), Events: event.NewFactory(b.Clock(), b.Ids()), Clock: b.Clock()}
		svc, err := tickets.NewService(runner, deps, policies)
		if err != nil {
			return nil, err
		}

		// The scheduled-job receiver takes an overlap lock; the database store is shared by replicas.
		all, err := locks.Build(ctx, b, d.locks, locks.WithStore("main", dblock.Store(dbs)))
		if err != nil {
			return nil, err
		}
		// A client's key for the notification service: one environment variable per client
		// (TICKETS_NOTIFY_<CLIENT>__NOTIFY_KEY). The base URL of the service is configuration.
		resolver, err := secrets.Build(ctx, b, d.secrets, secrets.WithStore("env", secrets.Env("TICKETS_NOTIFY_")))
		if err != nil {
			return nil, err
		}
		clients, err := module.Build(ctx, b, d.clients, module.WithClient(tickets.NotifierClient, module.DefaultClientOptions()))
		if err != nil {
			return nil, err
		}
		fc, err := flowcatalyst.Build(ctx, b, d.fc, flowcatalyst.WithDatabases(dbs), flowcatalyst.WithLocks(all))
		if err != nil {
			return nil, err
		}
		if err := tickets.RegisterReceivers(fc,
			&tickets.Notifier{Clients: clients, Secrets: resolver, Log: b.Logger("tickets-receivers")},
			&tickets.Sweeper{DB: db, Service: svc, Clock: b.Clock(), Log: b.Logger("tickets-receivers")}); err != nil {
			return nil, err
		}
		if err := tickets.Gauges(b, db, all, tuning.GaugeInterval); err != nil {
			return nil, err
		}

		failures, err := auth.RedirectFailuresTo("/sign-in-failed")
		if err != nil {
			return nil, err
		}
		opts := append([]auth.Option{
			auth.SessionStore(authdb.Factory(dbs)),
			auth.Application(fw.MustApplicationCode(tickets.Application)),
			auth.PostLogoutRedirect("/signed-out"),
			// A failed sign-in lands on the SPA's page for it, not on the JSON envelope.
			auth.FailureHandler(failures),
			// The roles are written once (tickets.Roles) and the sync sends the same ones to the platform.
			auth.Permissions(auth.ScopesMapper().And(auth.ByRole(tickets.RoleTable()))),
		}, x.AuthOptions...)
		mod, err := d.auth.Build(ctx, b, opts...)
		if err != nil {
			return nil, err
		}

		rt := httpfw.NewRoutes("tickets-api")
		api := &tickets.API{Service: svc, DB: db, Store: store, Clock: b.Clock(), CommentLimit: tuning.CommentLimit}
		if err := api.Bind(rt); err != nil {
			return nil, err
		}
		api.BindAdmin(rt)
		adminRoutes, err := d.adm.Build(b)
		if err != nil {
			return nil, err
		}
		receiverRoutes, err := fc.Routes()
		if err != nil {
			return nil, err
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
		srv, err := httpfw.NewServer(b, d.web, []*httpfw.Routes{mod.Routes(), rt, adminRoutes, receiverRoutes}, httpfw.WithResolver(mod.Resolver()))
		if err != nil {
			return nil, err
		}
		if x.Served != nil {
			x.Served(&Built{Auth: mod, DB: db, Service: svc, Locks: all, Platform: fc, Runner: runner, Deps: deps})
		}
		return srv, nil
	}
}

func policiesOf(x Deps) (tickets.Policies, error) {
	if x.Policies != nil {
		return *x.Policies, nil
	}
	return tickets.DefaultPolicies()
}

// BuildCore is the build function of the write path alone, for tests of the use cases: no HTTP, no
// platform.
func BuildCore(d Declared, x Deps) fw.BuildFunc {
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
		var store tickets.Store = tickets.Repository{}
		if x.Store != nil {
			store = x.Store
		}
		policies, err := policiesOf(x)
		if err != nil {
			return nil, err
		}
		deps := tickets.Deps{Tickets: store, Ids: b.Ids(), Events: event.NewFactory(b.Clock(), b.Ids()), Clock: b.Clock()}
		svc, err := tickets.NewService(runner, deps, policies)
		if err != nil {
			return nil, err
		}
		if x.Served != nil {
			x.Served(&Built{DB: db, Service: svc, Runner: runner, Deps: deps})
		}
		return nil, nil //nolint:nilnil // nothing to serve: the caller drives the use cases
	}
}
