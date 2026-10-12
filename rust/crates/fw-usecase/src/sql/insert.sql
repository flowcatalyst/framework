INSERT INTO outbox_messages (
  id, type, message_group, payload, status, created_at, updated_at,
  client_id, payload_size, headers
)
VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)