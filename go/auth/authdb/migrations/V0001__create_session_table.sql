CREATE TABLE fw_session (
    id_hash    text        PRIMARY KEY,
    kind       text        NOT NULL,
    data       jsonb       NOT NULL,
    expires_at timestamptz NOT NULL
);
CREATE INDEX fw_session_expires_at ON fw_session (expires_at);
