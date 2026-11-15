ALTER TABLE fw_partition_passes
    ADD COLUMN pass_trigger text,   -- LOOP | MANUAL; NULL: recorded before this column existed
    ADD COLUMN actor        text;   -- the principal id of a manual pass; NULL for the loop's
