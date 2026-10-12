CREATE SEQUENCE fw_lock_fence;
CREATE TABLE fw_lock (
    lock_key      text        PRIMARY KEY,
    owner_token   text,
    fencing_token bigint      NOT NULL,
    expires_at    timestamptz NOT NULL
);
