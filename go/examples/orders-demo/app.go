package main

import (
	"context"
	"fmt"
	"io"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/dbq"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

func newApp(opts ...fw.Option) *fw.App { return fw.New("orders-demo", opts...) }

type declared struct {
	db     *database.Declared
	outbox *usecase.Declared
}

// declare declares what the demo uses, in the Java demo's module order: database, outbox, orders.
func declare(app *fw.App) declared {
	d := declared{db: database.Declare(app), outbox: usecase.Declare(app)}
	orders.Declare(app)
	return d
}

type deps struct {
	out    io.Writer
	client fw.ClientID // the demo's client; "demo" when zero
	built  func(b *fw.Boot, r *usecase.Runner, db *database.DB)
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
