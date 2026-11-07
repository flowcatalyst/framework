-- Requests sent with an Idempotency-Key and the first one's answer, so a repeat gets the same answer
-- instead of doing the work again. Per client, caller and key; forgotten after idempotency.retention.
CREATE TABLE fw_idempotency_key (
    client_id     text        NOT NULL,
    principal_id  text        NOT NULL,
    idem_key      text        NOT NULL CHECK (length(idem_key) BETWEEN 1 AND 200),
    request_hash  text        NOT NULL,
    status        integer,
    content_type  text,
    response_body bytea,
    created_at    timestamptz NOT NULL,
    completed_at  timestamptz,
    PRIMARY KEY (client_id, principal_id, idem_key)
);

CREATE INDEX fw_idempotency_key_created ON fw_idempotency_key (created_at);
