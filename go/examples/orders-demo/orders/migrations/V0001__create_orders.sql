CREATE TABLE orders (
    id          VARCHAR(26) PRIMARY KEY,
    client_id   TEXT        NOT NULL,
    version     BIGINT      NOT NULL,
    customer    TEXT        NOT NULL,
    status      TEXT        NOT NULL
);

CREATE INDEX idx_orders_client ON orders (client_id, id);

-- The lines belong to the order: they are rewritten with it and covered by its version.
-- The CHECKs are the last line of defence; use cases validate before they get here.
CREATE TABLE order_lines (
    order_id         VARCHAR(26) NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    position         INTEGER     NOT NULL,
    line_id          TEXT        NOT NULL,
    sku              TEXT        NOT NULL CHECK (length(sku) <= 20),
    quantity         INTEGER     NOT NULL CHECK (quantity > 0),
    unit_price_cents BIGINT      NOT NULL CHECK (unit_price_cents >= 0),
    PRIMARY KEY (order_id, position)
);
