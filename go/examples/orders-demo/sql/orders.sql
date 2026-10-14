-- The orders example's static queries, checked by `go run ./tools/fwsql check` (sqlc vet with
-- db-prepare against a PostgreSQL migrated by the framework's runner, then sqlc diff).
-- Regenerate: go run ./tools/fwsql generate

-- name: FindHeader :one
-- The order's root row, for its client only.
SELECT version, customer, status
  FROM orders
 WHERE id = @id AND client_id = @client_id;

-- name: LinesOf :many
-- The order's lines in the order they were added.
SELECT line_id, sku, quantity, unit_price_cents
  FROM order_lines
 WHERE order_id = @order_id
 ORDER BY position;

-- name: InsertOrder :exec
INSERT INTO orders (id, client_id, version, customer, status)
VALUES (@id, @client_id, 1, @customer, @status);

-- name: UpdateOrder :execrows
-- The optimistic-locking write: the row count says whether the version still matched.
UPDATE orders
   SET version = @new_version, customer = @customer, status = @status
 WHERE id = @id AND client_id = @client_id AND version = @version;

-- name: DeleteOrder :execrows
DELETE FROM orders
 WHERE id = @id AND client_id = @client_id AND version = @version;

-- name: DeleteLines :exec
DELETE FROM order_lines WHERE order_id = @order_id;

-- name: InsertLines :copyfrom
INSERT INTO order_lines (order_id, position, line_id, sku, quantity, unit_price_cents)
VALUES (@order_id, @position, @line_id, @sku, @quantity, @unit_price_cents);

-- name: Summaries :many
-- One row per order of a client, with its line count, total and first SKU ('' without lines).
-- sqlc's static engine types min(l.sku)::text as a non-null string although it is NULL for an
-- order without lines, and keeps an overridden column (line_id) non-null even when it comes from
-- the outer-joined table (docs/guide/go/checked-sql.md): write such columns with coalesce.
SELECT o.id, o.customer, o.status, o.version,
       count(l.position) AS line_count,
       coalesce(sum(l.quantity * l.unit_price_cents), 0)::bigint AS total_cents,
       coalesce(min(l.sku), '')::text AS first_sku
  FROM orders o LEFT JOIN order_lines l ON l.order_id = o.id
 WHERE o.client_id = @client_id
 GROUP BY o.id
 ORDER BY o.id;

