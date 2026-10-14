package sqlfw

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
)

// Statement is a built SQL statement: go-jet's statements satisfy it. Sql returns the text with
// $n placeholders and the arguments to bind to them.
type Statement interface {
	Sql() (query string, args []any)
}

// Query runs stmt on db's pool and scans every row with scan, for example pgx.RowToStructByPos.
// jet builds the statement, pgx runs and scans it; jet's own result mapper is never used.
func Query[T any](ctx context.Context, db *database.DB, stmt Statement, scan pgx.RowToFunc[T]) ([]T, error) {
	query, args := stmt.Sql()
	rows, err := db.Pool().Query(ctx, query, args...)
	if err != nil {
		return nil, fmt.Errorf("sqlfw: query: %w", err)
	}
	out, err := pgx.CollectRows(rows, scan)
	if err != nil {
		return nil, fmt.Errorf("sqlfw: query: %w", err)
	}
	return out, nil
}
