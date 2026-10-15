package flowcatalyst

import (
	"context"
	"fmt"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
)

// OutboxCheckName is the name of the outbox health check; PlatformCheckName that of the platform
// check. Both are informational (flowcatalyst contract sections 1 and 3): the platform is not on
// the request path, so an outage must not take every replica out of rotation.
// app.health.critical can make either decide readiness again.
const (
	OutboxCheckName   = "outbox"
	PlatformCheckName = "flowcatalyst"
	outboxTable       = "outbox_messages"
)

// oldestUndelivered: two scalar sub-queries so each uses its partial index (status = 0 pending,
// status = 9 in progress); LEAST ignores NULLs in PostgreSQL. Terminal failure statuses are not a
// backlog: the processor will not retry them. Nothing is written.
const oldestUndelivered = "SELECT LEAST(" +
	"(SELECT min(created_at) FROM outbox_messages WHERE status = 0), " +
	"(SELECT min(created_at) FROM outbox_messages WHERE status = 9))"

// outboxCheck is Down when the oldest undelivered outbox row is strictly older than staleAfter,
// measured with the application's clock (the clock the outbox writer stamps created_at with). It
// reads the table only; it never claims or updates rows.
func outboxCheck(db *database.DB, staleAfter time.Duration, clock fw.Clock) fw.HealthCheck {
	return func(ctx context.Context) error {
		var oldest *time.Time
		if err := db.Pool().QueryRow(ctx, oldestUndelivered).Scan(&oldest); err != nil {
			return fmt.Errorf("the outbox could not be read: %w", err)
		}
		if oldest == nil {
			return nil
		}
		age := clock.Now().Sub(*oldest)
		if age > staleAfter {
			return fmt.Errorf("the oldest undelivered outbox row has waited %ds (stale after %ds); is the platform's outbox processor running against this database?",
				int64(age/time.Second), int64(staleAfter/time.Second))
		}
		return nil
	}
}
