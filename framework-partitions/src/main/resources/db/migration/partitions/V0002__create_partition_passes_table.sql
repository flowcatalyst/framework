CREATE TABLE fw_partition_passes (
    table_name       text        NOT NULL PRIMARY KEY,
    ended_at         timestamptz NOT NULL,
    ensure_status    text        NOT NULL,
    retention_status text        NOT NULL,
    archive_status   text        NOT NULL,
    created          int         NOT NULL,
    dropped          int         NOT NULL,
    archived         int         NOT NULL
);
