CREATE TABLE received_events (
    id          bigserial PRIMARY KEY,
    job_id      text        NOT NULL,
    event_type  text        NOT NULL,
    order_id    text        NOT NULL,
    principal   text        NOT NULL,
    causation   text        NOT NULL,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
