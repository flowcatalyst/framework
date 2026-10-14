package orders

import (
	"context"
	"errors"
	"fmt"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/dbq"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// Repository stores orders with sqlc's generated queries. The lines belong to the order: they are
// rewritten with it and covered by its version.
type Repository struct{}

var _ usecase.Repository[domain.Order, domain.OrderID] = Repository{}

// Find reads an order of the client; false when there is none.
func (Repository) Find(ctx context.Context, db *database.DB, client fw.ClientID, orderID domain.OrderID) (usecase.Loaded[domain.Order], bool, error) {
	q := dbq.New(db.Pool())
	header, err := q.FindHeader(ctx, dbq.FindHeaderParams{ID: orderID, ClientID: client.String()})
	if errors.Is(err, pgx.ErrNoRows) {
		return usecase.Loaded[domain.Order]{}, false, nil
	}
	if err != nil {
		return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s: %w", orderID, err)
	}
	rows, err := q.LinesOf(ctx, orderID)
	if err != nil {
		return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s lines: %w", orderID, err)
	}
	lines := make([]domain.OrderLine, 0, len(rows))
	for _, r := range rows {
		sku, err := domain.ParseSKU(r.Sku)
		if err != nil {
			return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s: stored line: %w", orderID, err)
		}
		qty, err := domain.NewQuantity(int64(r.Quantity))
		if err != nil {
			return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s: stored line: %w", orderID, err)
		}
		price, err := domain.NewCents(r.UnitPriceCents)
		if err != nil {
			return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s: stored line: %w", orderID, err)
		}
		lines = append(lines, domain.NewOrderLine(r.LineID, sku, qty, price))
	}
	customer, err := domain.ParseCustomerName(header.Customer)
	if err != nil {
		return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s: stored customer: %w", orderID, err)
	}
	order, ok := domain.Restore(orderID, customer, header.Status, lines)
	if !ok {
		return usecase.Loaded[domain.Order]{}, false, fmt.Errorf("order %s: unknown stored status %q", orderID, header.Status)
	}
	return usecase.LoadedFrom(client, header.Version, order), true, nil
}

// Insert stores a new order at version 1.
func (Repository) Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, o domain.Order) error {
	q := dbq.New(tx.Conn())
	if err := q.InsertOrder(ctx, dbq.InsertOrderParams{
		ID: o.ID(), ClientID: client.String(), Customer: o.Customer().String(), Status: domain.StatusOf(o),
	}); err != nil {
		return err
	}
	return writeLines(ctx, q, o)
}

// Update writes the order at v.Next where it still is at v.Expected, then rewrites its lines.
func (Repository) Update(ctx context.Context, tx *database.Tx, client fw.ClientID, o domain.Order, v usecase.VersionCheck) (int64, error) {
	q := dbq.New(tx.Conn())
	n, err := q.UpdateOrder(ctx, dbq.UpdateOrderParams{
		NewVersion: v.Next, Customer: o.Customer().String(), Status: domain.StatusOf(o),
		ID: o.ID(), ClientID: client.String(), Version: v.Expected,
	})
	if err != nil || n != 1 {
		return n, err // 0: stale, the runner applies the policy; above 1: the runner fails it
	}
	if err := q.DeleteLines(ctx, o.ID()); err != nil {
		return n, err
	}
	return n, writeLines(ctx, q, o)
}

// Delete deletes the order at v.Expected; the lines go by ON DELETE CASCADE.
func (Repository) Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, o domain.Order, v usecase.VersionCheck) (int64, error) {
	return dbq.New(tx.Conn()).DeleteOrder(ctx, dbq.DeleteOrderParams{ID: o.ID(), ClientID: client.String(), Version: v.Expected})
}

func writeLines(ctx context.Context, q *dbq.Queries, o domain.Order) error {
	lines := o.Lines()
	if len(lines) == 0 {
		return nil
	}
	params := make([]dbq.InsertLinesParams, len(lines))
	for i, l := range lines {
		params[i] = dbq.InsertLinesParams{
			OrderID: o.ID(), Position: int32(i), LineID: l.ID(), Sku: l.SKU().String(), //nolint:gosec // a handful of lines
			Quantity: l.Quantity().Int(), UnitPriceCents: l.UnitPrice().Int(),
		}
	}
	_, err := q.InsertLines(ctx, params)
	return err
}
