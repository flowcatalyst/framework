package push

import (
	"context"
	"time"

	"github.com/jackc/pgx/v5"
)

// Entry is one push in the log (push contract section 4): its id, the SSE event id, increasing;
// what was pushed; and when it was logged.
type Entry struct {
	// ID is the log id: positive and increasing (A1 contiguous, A2 not).
	ID int64
	// Topic and Type are the push's topic and type.
	Topic, Type string
	// Client is the client the push was written for; "" is the platform client (NULL in the
	// tables), whose pushes go to every stream on the topic.
	Client string
	// Data is the push's JSON object, compact, as it was written.
	Data string
	// LoggedAt is when the relay logged it.
	LoggedAt time.Time
	// OutboxID is the outbox row the push came from, for a driver that can log one row twice (A2);
	// 0 when the driver does not keep it (A1). The hub drops an outbox id it has already seen.
	OutboxID int64
}

// OutboxRow is one row of fw_push_outbox as the relay takes it.
type OutboxRow struct {
	ID          int64
	Topic, Type string
	// Client is "" for the platform client (NULL).
	Client    string
	Data      string
	CreatedAt time.Time
}

// Holder names the streams to end ([Pushes.EndStreams]): a stream ends when its principal, its
// field device or its field login family equals a field that is not empty.
type Holder struct {
	Principal string `json:"principal,omitempty"`
	Device    string `json:"device,omitempty"`
	Session   string `json:"session,omitempty"`
}

// IsZero reports whether the holder names nobody.
func (h Holder) IsZero() bool { return h.Principal == "" && h.Device == "" && h.Session == "" }

// Follower is what [Log.Follow] calls.
type Follower struct {
	// Grown is called when the log may have grown: the hub then reads after its last id.
	Grown func()
	// End is called with a holder some instance asked to end the streams of ([Log.End]).
	End func(Holder)
}

// Log is the log driver (push contract section 4): where the relay appends pushes and every
// instance's hub reads them. A1 ([PostgresLog], the default) keeps it in the table fw_push_log; A2
// keeps it in a Redis stream. The hub, the relay and the HTTP layer only use this interface.
// Implementations are safe for concurrent use.
type Log interface {
	// Append logs rows (the relay's batch, in outbox id order) in that order, with ids above every
	// id logged before, stamped at. tx is the relay's transaction, which deletes the rows from
	// fw_push_outbox after Append and then commits: A1 appends inside it (one transaction, so a
	// failure appends nothing), and a driver outside the database appends at once, so a crash
	// before the commit appends the rows again (it must keep [Entry.OutboxID] for the hub).
	// Append also wakes every follower once the rows are visible.
	Append(ctx context.Context, tx pgx.Tx, rows []OutboxRow, at time.Time) error
	// ReadAfter returns at most limit entries with an id above after, in id order.
	ReadAfter(ctx context.Context, after int64, limit int) ([]Entry, error)
	// Latest returns the highest id logged (0 when nothing was).
	Latest(ctx context.Context) (int64, error)
	// Trimmed returns the highest id the log no longer keeps, or may no longer keep (0 when
	// nothing was deleted): a reader after an id below it may have missed pushes and gets a reset.
	// A1: the oldest id kept minus one (its ids have no gaps); A2: the stream's
	// max-deleted-entry-id.
	Trimmed(ctx context.Context) (int64, error)
	// Prune deletes the entries logged before before, always keeping the newest (so ids keep
	// increasing), and returns how many it deleted.
	Prune(ctx context.Context, before time.Time) (int64, error)
	// Follow blocks until ctx ends (then it returns nil), calling f.Grown whenever the log may
	// have grown, once right after it starts following, and f.End for every [Log.End] of any
	// instance. It returns an error when it loses its connection; the hub calls it again after a
	// pause and polls meanwhile.
	Follow(ctx context.Context, f Follower) error
	// End asks every instance (this one included) to end the streams of a holder.
	End(ctx context.Context, h Holder) error
	// Ping checks that the log can be reached (the health check push:log).
	Ping(ctx context.Context) error
}
