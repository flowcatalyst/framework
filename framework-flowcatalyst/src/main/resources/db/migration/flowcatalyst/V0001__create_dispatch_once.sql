-- DispatchDelivery.once: one row per (handler, dispatch job) whose work has committed.
CREATE TABLE fw_dispatch_once (
    handler      text        NOT NULL,
    job_id       text        NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (handler, job_id)
);
