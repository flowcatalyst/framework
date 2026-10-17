-- A ticket and its comments (one aggregate, covered by the ticket's version), and a client's SLA
-- policy (a second aggregate: one row per client). Every table carries client_id or hangs off one
-- that does; the CHECKs are the last line of defence, use cases validate before they get here.
CREATE TABLE tickets (
    id           VARCHAR(26) PRIMARY KEY,
    client_id    TEXT        NOT NULL,
    version      BIGINT      NOT NULL,
    subject      TEXT        NOT NULL CHECK (length(subject) <= 200),
    description  TEXT        NOT NULL,
    priority     TEXT        NOT NULL,
    status       TEXT        NOT NULL,
    requester_id TEXT        NOT NULL,
    assignee_id  TEXT,
    opened_at    TIMESTAMPTZ NOT NULL,
    due_at       TIMESTAMPTZ NOT NULL,
    breached_at  TIMESTAMPTZ,
    resolved_at  TIMESTAMPTZ,
    resolution   TEXT
);

CREATE INDEX idx_tickets_client ON tickets (client_id, id);

-- The SLA sweep looks across every client for tickets that ran out of time and were not flagged yet.
CREATE INDEX idx_tickets_sla_sweep ON tickets (due_at)
    WHERE breached_at IS NULL AND status IN ('OPEN', 'IN_PROGRESS');

-- Comments are only ever appended: the repository writes the positions it has not written yet.
CREATE TABLE ticket_comments (
    ticket_id  VARCHAR(26) NOT NULL REFERENCES tickets (id) ON DELETE CASCADE,
    position   INTEGER     NOT NULL,
    comment_id TEXT        NOT NULL,
    author_id  TEXT        NOT NULL,
    body       TEXT        NOT NULL CHECK (length(body) <= 4000),
    posted_at  TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (ticket_id, position)
);

-- A client's own SLA: minutes allowed to resolve a ticket, by priority. At most one per client.
CREATE TABLE sla_policies (
    id             VARCHAR(26) PRIMARY KEY,
    client_id      TEXT        NOT NULL UNIQUE,
    version        BIGINT      NOT NULL,
    low_minutes    INTEGER     NOT NULL CHECK (low_minutes > 0),
    normal_minutes INTEGER     NOT NULL CHECK (normal_minutes > 0),
    high_minutes   INTEGER     NOT NULL CHECK (high_minutes > 0),
    urgent_minutes INTEGER     NOT NULL CHECK (urgent_minutes > 0)
);
