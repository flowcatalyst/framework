-- Push to clients (push contract sections 2 and 4). fw_push_outbox holds the pushes written in
-- application transactions; the relay moves them, in id order, into fw_push_log, which every
-- instance follows. The log's id is max(id) + 1, assigned by the one relay in its transaction.
CREATE TABLE fw_push_outbox (
    id         BIGSERIAL PRIMARY KEY,
    topic      VARCHAR(255) NOT NULL,
    type       VARCHAR(100) NOT NULL,
    client     VARCHAR(100),             -- NULL: the platform client (section 5)
    data       TEXT NOT NULL,            -- the JSON object as written
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE fw_push_log (
    id         BIGINT PRIMARY KEY,
    topic      VARCHAR(255) NOT NULL,
    type       VARCHAR(100) NOT NULL,
    client     VARCHAR(100),
    data       TEXT NOT NULL,
    logged_at  TIMESTAMPTZ NOT NULL
);
CREATE INDEX fw_push_log_logged_at ON fw_push_log (logged_at);
