CREATE TABLE things (
    id         bigint      PRIMARY KEY,
    name       text        NOT NULL,
    note       text,
    created_at timestamptz NOT NULL DEFAULT now()
);
