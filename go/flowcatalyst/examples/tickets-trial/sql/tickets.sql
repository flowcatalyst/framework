-- The tickets service's static queries (the Java TicketSql.sql), checked by `go run ./tools/fwsql
-- check` (sqlc vet with db-prepare against a PostgreSQL migrated by the framework's runner, then
-- sqlc diff). Regenerate: go run ./tools/fwsql generate

-- name: FindHeader :one
-- The ticket's root row, for its client only.
SELECT version, subject, description, priority, status, requester_id, assignee_id,
       opened_at, due_at, breached_at, resolved_at, resolution
  FROM tickets
 WHERE id = @id AND client_id = @client_id;

-- name: CommentsOf :many
-- The ticket's comments in the order they were posted.
SELECT comment_id, author_id, body, posted_at
  FROM ticket_comments
 WHERE ticket_id = @ticket_id
 ORDER BY position;

-- name: CommentCount :one
-- How many comments are stored, which is the next position to write.
SELECT count(*) AS n FROM ticket_comments WHERE ticket_id = @ticket_id;

-- name: InsertTicket :exec
INSERT INTO tickets (id, client_id, version, subject, description, priority, status, requester_id,
                     assignee_id, opened_at, due_at, breached_at, resolved_at, resolution)
VALUES (@id, @client_id, 1, @subject, @description, @priority, @status, @requester_id,
        @assignee_id, @opened_at, @due_at, @breached_at, @resolved_at, @resolution);

-- name: UpdateTicket :execrows
-- The optimistic-locking write: the row count says whether the version still matched.
UPDATE tickets
   SET version = @new_version, subject = @subject, description = @description, priority = @priority,
       status = @status, assignee_id = @assignee_id, due_at = @due_at, breached_at = @breached_at,
       resolved_at = @resolved_at, resolution = @resolution
 WHERE id = @id AND client_id = @client_id AND version = @version;

-- name: DeleteTicket :execrows
DELETE FROM tickets
 WHERE id = @id AND client_id = @client_id AND version = @version;

-- name: InsertComment :exec
INSERT INTO ticket_comments (ticket_id, position, comment_id, author_id, body, posted_at)
VALUES (@ticket_id, @position, @comment_id, @author_id, @body, @posted_at);

-- name: List :many
-- A client's tickets, newest first, one page. Every filter is optional (null means any); the
-- cursor is the id of the last ticket of the previous page. Asking for one more than the page
-- tells the caller whether there is a next page.
SELECT id, subject, priority, status, requester_id, assignee_id, opened_at, due_at, breached_at, version
  FROM tickets
 WHERE client_id = @client_id
   AND (sqlc.narg('status')::text IS NULL OR status = sqlc.narg('status')::text)
   AND (sqlc.narg('assignee_id')::text IS NULL OR assignee_id = sqlc.narg('assignee_id')::text)
   AND (sqlc.narg('requester_id')::text IS NULL OR requester_id = sqlc.narg('requester_id')::text)
   AND (sqlc.narg('after')::text IS NULL OR id < sqlc.narg('after')::text)
 ORDER BY id DESC
 LIMIT @page_size;

-- name: OverdueClients :many
-- The clients that have a ticket that ran out of time and was not flagged yet: the one query in this
-- application that is not scoped to a client, and it returns client codes only. The SLA sweep then
-- acts for each of them with that client's own context (fw.EachClient).
SELECT DISTINCT client_id
  FROM tickets
 WHERE breached_at IS NULL
   AND status IN ('OPEN', 'IN_PROGRESS')
   AND due_at < @now
 ORDER BY client_id;

-- name: OverdueFor :many
-- One client's tickets that ran out of time and were not flagged yet, oldest deadline first.
SELECT id, due_at
  FROM tickets
 WHERE client_id = @client_id
   AND breached_at IS NULL
   AND status IN ('OPEN', 'IN_PROGRESS')
   AND due_at < @now
 ORDER BY due_at
 LIMIT @page_size;

-- name: Counts :many
-- Open work per status for one client (the admin overview).
SELECT status, count(*) AS n,
       count(*) FILTER (WHERE breached_at IS NOT NULL) AS overdue
  FROM tickets
 WHERE client_id = @client_id
 GROUP BY status
 ORDER BY status;

-- name: Totals :one
-- Open and overdue tickets across every client (the metrics loop).
SELECT count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS')) AS open,
       count(*) FILTER (WHERE status IN ('OPEN', 'IN_PROGRESS') AND breached_at IS NOT NULL) AS overdue
  FROM tickets;
