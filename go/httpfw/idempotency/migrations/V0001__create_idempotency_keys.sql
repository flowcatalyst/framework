-- Requests sent with an Idempotency-Key (an offline queue replays what it could not confirm): the
-- first one's answer, so a repeat gets that answer instead of running again. One row per client,
-- caller and key; `token` names the reservation of the request that is running it (status NULL
-- until it completes). Rows older than the retention are expired: a repeat runs again, and the
-- framework prunes them.
CREATE TABLE fw_idempotency_keys (
    client_id       TEXT        NOT NULL,
    principal_id    TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_hash    TEXT        NOT NULL,
    token           TEXT        NOT NULL,
    status          INTEGER,
    content_type    TEXT,
    response_body   BYTEA,
    created_at      TIMESTAMPTZ NOT NULL,
    completed_at    TIMESTAMPTZ,
    PRIMARY KEY (client_id, principal_id, idempotency_key)
);

CREATE INDEX idx_fw_idempotency_keys_created ON fw_idempotency_keys (created_at);
