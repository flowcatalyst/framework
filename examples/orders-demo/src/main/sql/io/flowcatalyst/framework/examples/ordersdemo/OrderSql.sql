-- The order's SQL, checked against the migrated schema when the project builds.
-- Regenerate after changing it: ./mvnw -Pgenerate-sql -DskipTests package -pl examples/orders-demo -am

-- name: findHeader :one
-- The order's root row, for its client only.
-- param: id io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- column: status enum io.flowcatalyst.framework.examples.ordersdemo.OrderStatus
SELECT version, customer, status
  FROM orders
 WHERE id = :id AND client_id = :client_id;

-- name: linesOf :many
-- The order's lines in the order they were added.
-- param: order_id io.flowcatalyst.framework.examples.ordersdemo.OrderId
SELECT line_id, sku, quantity, unit_price_cents
  FROM order_lines
 WHERE order_id = :order_id
 ORDER BY position;

-- name: insertOrder :exec
-- param: id io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- param: status enum io.flowcatalyst.framework.examples.ordersdemo.OrderStatus
INSERT INTO orders (id, client_id, version, customer, status)
VALUES (:id, :client_id, 1, :customer, :status);

-- name: updateOrder :exec
-- The optimistic-locking write: the row count says whether the version still matched.
-- param: id io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- param: status enum io.flowcatalyst.framework.examples.ordersdemo.OrderStatus
UPDATE orders
   SET version = :new_version, customer = :customer, status = :status
 WHERE id = :id AND client_id = :client_id AND version = :version;

-- name: deleteOrder :exec
-- param: id io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- param: client_id io.flowcatalyst.framework.context.ClientId
DELETE FROM orders
 WHERE id = :id AND client_id = :client_id AND version = :version;

-- name: deleteLines :exec
-- param: order_id io.flowcatalyst.framework.examples.ordersdemo.OrderId
DELETE FROM order_lines WHERE order_id = :order_id;

-- name: insertLine :exec
-- param: order_id io.flowcatalyst.framework.examples.ordersdemo.OrderId
INSERT INTO order_lines (order_id, position, line_id, sku, quantity, unit_price_cents)
VALUES (:order_id, :position, :line_id, :sku, :quantity, :unit_price_cents);

-- name: summaries :many
-- One row per order of a client, with its line count and total. The LEFT JOIN makes every
-- column nullable to the generator, so the ones that cannot be say so with !; first_sku is null for an order
-- with no lines.
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- column: id ! io.flowcatalyst.framework.examples.ordersdemo.OrderId
-- column: customer !
-- column: status ! enum io.flowcatalyst.framework.examples.ordersdemo.OrderStatus
-- column: version !
-- column: first_sku ?
SELECT o.id, o.customer, o.status, o.version,
       count(l.position) AS "line_count!",
       coalesce(sum(l.quantity * l.unit_price_cents), 0)::bigint AS "total_cents!",
       min(l.sku) AS first_sku
  FROM orders o LEFT JOIN order_lines l ON l.order_id = o.id
 WHERE o.client_id = :client_id
 GROUP BY o.id
 ORDER BY o.id;
