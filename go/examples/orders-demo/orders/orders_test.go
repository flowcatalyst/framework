package orders_test

import (
	"context"
	"log/slog"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
	"github.com/flowcatalyst/framework/go/usecase/usecasetest"
)

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		return database.ApplyMigrations(ctx, db, []fw.MigrationSet{
			{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()},
			{Module: orders.ModuleID, Files: orders.Migrations()},
		}, slog.New(slog.DiscardHandler))
	}))
}

func open(t *testing.T) *database.DB {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	return db
}

var ids = fw.NewIds(fwtest.NewClock(time.UnixMilli(1_760_000_000_000)))

func newOrder(customer string, skus ...string) domain.Order {
	c, _ := domain.ParseCustomerName(customer)
	o := domain.PlaceOrder(id.New[domain.OrderKind](ids), c)
	for _, s := range skus {
		sku, _ := domain.ParseSKU(s)
		q, _ := domain.NewQuantity(2)
		p, _ := domain.NewCents(150)
		o = o.WithLine(domain.NewOrderLine(id.New[domain.LineKind](ids), sku, q, p))
	}
	return o
}

func sameOrder(a, b domain.Order) bool {
	if domain.StatusOf(a) != domain.StatusOf(b) || a.ID() != b.ID() || a.Customer() != b.Customer() {
		return false
	}
	return slices.Equal(a.Lines(), b.Lines())
}

func TestOrderRepositoryKeepsTheContract(t *testing.T) {
	t.Parallel()
	usecasetest.Repository(t, usecasetest.Harness[domain.Order, domain.OrderID]{
		Repo: orders.Repository{},
		DB:   open(t),
		New: func() (domain.Order, domain.OrderID) {
			o := newOrder("Contract", "A-1", "B-2")
			return o, o.ID()
		},
		Change: func(o domain.Order) domain.Order {
			if open, ok := o.(domain.OpenOrder); ok {
				return open.Cancel()
			}
			return o
		},
		Equal: sameOrder,
	})
}

// insertAll stores orders for a client through the repository.
func insertAll(t *testing.T, db *database.DB, client fw.ClientID, list ...domain.Order) {
	t.Helper()
	r, err := fw.New("seed", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir())).Start(t.Context(),
		func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
			runner, err := usecase.NewRunner(b, &usecase.Declared{}, db)
			if err != nil {
				return nil, err
			}
			for _, o := range list {
				d, err := usecase.Define(runner, seed{o: o})
				if err != nil {
					return nil, err
				}
				if _, err := d.Run(context.Background(), fw.ContextFor(client), struct{}{}); err != nil {
					return nil, err
				}
			}
			return nil, nil
		})
	if err != nil {
		t.Fatal(err)
	}
	_ = r.Shutdown()
}

// seed is a use case that inserts one given order (test data through the real write path).
type seed struct{ o domain.Order }

type seeded struct{ fwMeta }

func (seed) Name() usecase.Name                                   { return usecase.MustName("seed") }
func (seed) Validate(context.Context, fw.Context, struct{}) error { return nil }
func (seed) Authorize(context.Context, fw.Context, usecase.Valid[struct{}]) (usecase.Grant, error) {
	return usecase.Public(), nil
}

func (s seed) Execute(_ context.Context, cx fw.Context, _ usecase.Authorized[struct{}], _ *database.DB) (*usecase.Plan[seeded], error) {
	ev := seeded{fwMeta: newMeta(cx, s.o.ID().String())}
	return usecase.NewPlan(ev, usecase.Insert(usecase.NewFresh(cx, s.o), orders.Repository{})), nil
}

func TestListOrdersEveryFilterCombination(t *testing.T) {
	t.Parallel()
	db := open(t)
	acme, other := fw.MustClientID("acme"), fw.MustClientID("other")
	a1 := newOrder("Acme Ltd", "X")
	a2 := newOrder("Acme 50% off_sale", "Y")
	b1 := newOrder("Beta GmbH")
	cancelled := newOrder("Acme Cancelled").(domain.OpenOrder).Cancel()
	foreign := newOrder("Acme abroad")
	insertAll(t, db, acme, a1, a2, b1, cancelled)
	insertAll(t, db, other, foreign)

	open, can := domain.StatusOpen, domain.StatusCancelled
	cases := []struct {
		name string
		q    orders.ListQuery
		want []domain.Order
	}{
		{"everything of the client, newest first", orders.ListQuery{Size: 10}, []domain.Order{cancelled, b1, a2, a1}},
		{"by status", orders.ListQuery{Status: &open, Size: 10}, []domain.Order{b1, a2, a1}},
		{"by another status", orders.ListQuery{Status: &can, Size: 10}, []domain.Order{cancelled}},
		{"by search", orders.ListQuery{Search: "Acme", Size: 10}, []domain.Order{cancelled, a2, a1}},
		{"by status and search", orders.ListQuery{Status: &open, Search: "Acme", Size: 10}, []domain.Order{a2, a1}},
		{"LIKE wildcards in the search are literal", orders.ListQuery{Search: "50%", Size: 10}, []domain.Order{a2}},
		{"an underscore is literal", orders.ListQuery{Search: "e_5", Size: 10}, nil},
		{"page 1", orders.ListQuery{Size: 3, Page: 1}, []domain.Order{cancelled, b1, a2}},
		{"page 2", orders.ListQuery{Size: 3, Page: 2}, []domain.Order{a1}},
		{"no match", orders.ListQuery{Search: "nobody", Size: 10}, nil},
	}
	cx := fw.ContextFor(acme)
	rm := orders.ReadModel{DB: db}
	for _, impl := range []struct {
		name string
		list func(context.Context, fw.Context, orders.ListQuery) ([]orders.OrderRow, error)
	}{{"Filter", rm.ListOrdersFilter}, {"go-jet", rm.ListOrdersJet}} {
		for _, c := range cases {
			t.Run(impl.name+"/"+c.name, func(t *testing.T) {
				got, err := impl.list(t.Context(), cx, c.q)
				if err != nil {
					t.Fatal(err)
				}
				var gotIDs, wantIDs []string
				for _, r := range got {
					gotIDs = append(gotIDs, r.ID.String()+" "+r.Status+" "+r.Customer)
				}
				for _, o := range c.want {
					wantIDs = append(wantIDs, o.ID().String()+" "+domain.StatusOf(o)+" "+o.Customer().String())
				}
				if strings.Join(gotIDs, "|") != strings.Join(wantIDs, "|") {
					t.Errorf("got\n %v\nwant\n %v", gotIDs, wantIDs)
				}
			})
		}
	}
}
