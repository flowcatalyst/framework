-- Per-client settings (docs/contracts/client-settings.md section 3): one row per client, one
-- aggregate; `settings` is an object from setting key to the value's JSON.
CREATE TABLE fw_client_settings (
    client_id TEXT   PRIMARY KEY,
    version   BIGINT NOT NULL,
    settings  JSONB  NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(settings) = 'object')
);
