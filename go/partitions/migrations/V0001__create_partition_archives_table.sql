CREATE TABLE fw_partition_archives (
    table_name     text        NOT NULL,
    partition_name text        NOT NULL,
    part           int         NOT NULL,
    row_count      bigint      NOT NULL,
    byte_count     bigint      NOT NULL,
    sha256         text        NOT NULL,
    object_key     text        NOT NULL,
    archived_at    timestamptz NOT NULL,
    PRIMARY KEY (table_name, partition_name, part)
);
