-- The SLA policy's SQL (one row per client), checked against the migrated schema.

-- name: findByClient :one
-- The client's policy, if it set one.
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- column: id ! io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId
SELECT id, version, low_minutes, normal_minutes, high_minutes, urgent_minutes
  FROM sla_policies
 WHERE client_id = :client_id;

-- name: findPolicy :one
-- By id, for the client only.
-- param: id io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId
-- param: client_id io.flowcatalyst.framework.context.ClientId
SELECT version, low_minutes, normal_minutes, high_minutes, urgent_minutes
  FROM sla_policies
 WHERE id = :id AND client_id = :client_id;

-- name: insertPolicy :exec
-- param: id io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId
-- param: client_id io.flowcatalyst.framework.context.ClientId
INSERT INTO sla_policies (id, client_id, version, low_minutes, normal_minutes, high_minutes, urgent_minutes)
VALUES (:id, :client_id, 1, :low_minutes, :normal_minutes, :high_minutes, :urgent_minutes);

-- name: updatePolicy :exec
-- The optimistic-locking write.
-- param: id io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId
-- param: client_id io.flowcatalyst.framework.context.ClientId
UPDATE sla_policies
   SET version = :new_version, low_minutes = :low_minutes, normal_minutes = :normal_minutes,
       high_minutes = :high_minutes, urgent_minutes = :urgent_minutes
 WHERE id = :id AND client_id = :client_id AND version = :version;

-- name: deletePolicy :exec
-- param: id io.flowcatalyst.framework.examples.ticketstrial.SlaPolicyId
-- param: client_id io.flowcatalyst.framework.context.ClientId
DELETE FROM sla_policies
 WHERE id = :id AND client_id = :client_id AND version = :version;
