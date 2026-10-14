-- The SDK table holds client_id as VARCHAR(26), but a client id (a platform client
-- identifier) may be up to 100 characters. Widening keeps V0001 byte-identical to the SDK file.
ALTER TABLE outbox_messages ALTER COLUMN client_id TYPE VARCHAR(100);
