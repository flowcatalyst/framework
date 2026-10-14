package orders

import (
	"context"
	"fmt"
	"strings"

	pg "github.com/go-jet/jet/v2/postgres"
	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/internal/jet/public/table"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/sqlfw"
)

// OrderRow is one row of the order list.
type OrderRow struct {
	ID       domain.OrderID `db:"id"`
	Customer string         `db:"customer"`
	Status   string         `db:"status"`
}

// ListQuery is the list screen's filter: optional status, optional customer search, paging.
// Always scoped by the caller's client.
type ListQuery struct {
	Status *string
	Search string
	Size   int // rows per page, at least 1
	Page   int // from 1
}

func (q ListQuery) offset() int { return (max(q.Page, 1) - 1) * q.Size }

// escapeLike escapes LIKE's wildcards in user input.
func escapeLike(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// ReadModel reads lists for screens: no aggregates, plain typed queries.
type ReadModel struct {
	DB *database.DB
}

// ListOrdersFilter is the list query with database.Filter: no dependency, column names checked
// by the tests that run every filter combination.
func (r ReadModel) ListOrdersFilter(ctx context.Context, cx fw.Context, q ListQuery) ([]OrderRow, error) {
	if q.Size < 1 {
		return nil, fmt.Errorf("list size must be at least 1")
	}
	var f database.Filter
	f.Eq("client_id", cx.Client().String())
	if q.Status != nil {
		f.Eq("status", *q.Status)
	}
	if q.Search != "" {
		f.Clause("customer LIKE $%d", "%"+escapeLike(q.Search)+"%")
	}
	limit, offset := f.Arg(q.Size), f.Arg(q.offset())
	sql := "SELECT id, customer, status FROM orders" + f.Where() +
		fmt.Sprintf(" ORDER BY id DESC LIMIT $%d OFFSET $%d", limit, offset)
	rows, err := r.DB.Pool().Query(ctx, sql, f.Args()...)
	if err != nil {
		return nil, err
	}
	return pgx.CollectRows(rows, pgx.RowToStructByName[OrderRow])
}

// ListOrdersJet is the same list query with go-jet: the statement is built from the table code
// that `fwsql generate` writes from a migrated database, so a renamed column or a string compared
// with a number column fails to compile; pgx runs and scans it (sqlfw.Query), never jet's own
// mapper.
func (r ReadModel) ListOrdersJet(ctx context.Context, cx fw.Context, q ListQuery) ([]OrderRow, error) {
	if q.Size < 1 {
		return nil, fmt.Errorf("list size must be at least 1")
	}
	o := table.Orders
	cond := o.ClientID.EQ(pg.String(cx.Client().String()))
	if q.Status != nil {
		cond = cond.AND(o.Status.EQ(pg.String(*q.Status)))
	}
	if q.Search != "" {
		cond = cond.AND(o.Customer.LIKE(pg.String("%" + escapeLike(q.Search) + "%")))
	}
	stmt := pg.SELECT(o.ID.AS("id"), o.Customer.AS("customer"), o.Status.AS("status")).
		FROM(o).
		WHERE(cond).
		ORDER_BY(o.ID.DESC()).
		LIMIT(int64(q.Size)).
		OFFSET(int64(q.offset()))
	return sqlfw.Query(ctx, r.DB, stmt, pgx.RowToStructByName[OrderRow])
}
