-- The ticket's SQL, checked against the migrated schema when the project builds.
-- Regenerate after changing it: ./mvnw -Pgenerate-sql -DskipTests package -pl examples/tickets-trial -am

-- name: findHeader :one
-- The ticket's root row, for its client only.
-- param: id io.flowcatalyst.framework.examples.ticketstrial.TicketId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- column: priority enum io.flowcatalyst.framework.examples.ticketstrial.Priority
-- column: status enum io.flowcatalyst.framework.examples.ticketstrial.TicketStatus
SELECT version, subject, description, priority, status, requester_id, assignee_id,
       opened_at, due_at, breached_at, resolved_at, resolution
  FROM tickets
 WHERE id = :id AND client_id = :client_id;

-- name: commentsOf :many
-- The ticket's comments in the order they were posted.
-- param: ticket_id io.flowcatalyst.framework.examples.ticketstrial.TicketId
SELECT comment_id, author_id, body, posted_at
  FROM ticket_comments
 WHERE ticket_id = :ticket_id
 ORDER BY position;

-- name: commentCount :one
-- How many comments are stored, which is the next position to write.
-- param: ticket_id io.flowcatalyst.framework.examples.ticketstrial.TicketId
-- column: n !
SELECT count(*) AS n FROM ticket_comments WHERE ticket_id = :ticket_id;

-- name: insertTicket :exec
-- param: id io.flowcatalyst.framework.examples.ticketstrial.TicketId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- param: priority enum io.flowcatalyst.framework.examples.ticketstrial.Priority
-- param: status enum io.flowcatalyst.framework.examples.ticketstrial.TicketStatus
-- param: assignee_id ?
-- param: breached_at ?
-- param: resolved_at ?
-- param: resolution ?
INSERT INTO tickets (id, client_id, version, subject, description, priority, status, requester_id,
                     assignee_id, opened_at, due_at, breached_at, resolved_at, resolution)
VALUES (:id, :client_id, 1, :subject, :description, :priority, :status, :requester_id,
        :assignee_id, :opened_at, :due_at, :breached_at, :resolved_at, :resolution);

-- name: updateTicket :exec
-- The optimistic-locking write: the row count says whether the version still matched.
-- param: id io.flowcatalyst.framework.examples.ticketstrial.TicketId
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- param: priority enum io.flowcatalyst.framework.examples.ticketstrial.Priority
-- param: status enum io.flowcatalyst.framework.examples.ticketstrial.TicketStatus
-- param: assignee_id ?
-- param: breached_at ?
-- param: resolved_at ?
-- param: resolution ?
UPDATE tickets
   SET version = :new_version, subject = :subject, description = :description, priority = :priority,
       status = :status, assignee_id = :assignee_id, due_at = :due_at, breached_at = :breached_at,
       resolved_at = :resolved_at, resolution = :resolution
 WHERE id = :id AND client_id = :client_id AND version = :version;

-- name: deleteTicket :exec
-- param: id io.flowcatalyst.framework.examples.ticketstrial.TicketId
-- param: client_id io.flowcatalyst.framework.context.ClientId
DELETE FROM tickets
 WHERE id = :id AND client_id = :client_id AND version = :version;

-- name: insertComment :exec
-- param: ticket_id io.flowcatalyst.framework.examples.ticketstrial.TicketId
INSERT INTO ticket_comments (ticket_id, position, comment_id, author_id, body, posted_at)
VALUES (:ticket_id, :position, :comment_id, :author_id, :body, :posted_at);

-- name: list :many
-- A client's tickets, newest first, one page. Every filter is optional (null means any); the
-- cursor is the id of the last ticket of the previous page. Asking for one more than the page
-- tells the caller whether there is a next page.
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- param: status ?
-- param: assignee_id ?
-- param: requester_id ?
-- param: after ?
-- column: id ! io.flowcatalyst.framework.examples.ticketstrial.TicketId
-- column: priority enum io.flowcatalyst.framework.examples.ticketstrial.Priority
-- column: status enum io.flowcatalyst.framework.examples.ticketstrial.TicketStatus
SELECT id, subject, priority, status, requester_id, assignee_id, opened_at, due_at, breached_at, version
  FROM tickets
 WHERE client_id = :client_id
   AND (:status::text IS NULL OR status = :status::text)
   AND (:assignee_id::text IS NULL OR assignee_id = :assignee_id::text)
   AND (:requester_id::text IS NULL OR requester_id = :requester_id::text)
   AND (:after::text IS NULL OR id < :after::text)
 ORDER BY id DESC
 LIMIT :page_size;

-- name: overdue :many
-- Every client's tickets that ran out of time and were not flagged yet: the one query in this
-- application that is not scoped to a client, for the SLA sweep (which then acts for each
-- ticket's own client). Oldest deadline first.
-- column: id ! io.flowcatalyst.framework.examples.ticketstrial.TicketId
SELECT id, client_id, due_at
  FROM tickets
 WHERE breached_at IS NULL
   AND status IN ('OPEN', 'IN_PROGRESS')
   AND due_at < :now
 ORDER BY due_at
 LIMIT :page_size;

-- name: counts :many
-- Open work per status for one client (the admin overview).
-- param: client_id io.flowcatalyst.framework.context.ClientId
-- column: status ! enum io.flowcatalyst.framework.examples.ticketstrial.TicketStatus
-- column: n !
-- column: overdue !
SELECT status, count(*) AS n,
       count(*) FILTER (WHERE breached_at IS NOT NULL) AS overdue
  FROM tickets
 WHERE client_id = :client_id
 GROUP BY status
 ORDER BY status;

-- name: totals :one
-- Open and overdue tickets across every client (the metrics loop).
-- column: open !
-- column: overdue !
SELECT count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')) AS open,
       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND breached_at IS NOT NULL) AS overdue
  FROM tickets;
