package push

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"strconv"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
)

// The notification channels of A1.
const (
	channelOutbox = "fw_push_outbox" // a push was written (the relay listens)
	channelLog    = "fw_push_log"    // the relay logged pushes; the payload is the highest id
	channelEnd    = "fw_push_end"    // end the streams of a holder; the payload is its JSON
)

// PostgresLog is the A1 log driver (push contract section 4): the table fw_push_log on the default
// database instance, ids max(id)+1 assigned by the relay in its transaction (the primary key
// refuses a second relay), followers woken by pg_notify('fw_push_log', <highest id>).
type PostgresLog struct {
	db *database.DB
}

// NewPostgresLog returns the A1 driver on db, the database the push migrations ran on.
func NewPostgresLog(db *database.DB) *PostgresLog { return &PostgresLog{db: db} }

var _ Log = (*PostgresLog)(nil)

const appendSQL = `INSERT INTO fw_push_log (id, topic, type, client, data, logged_at)
SELECT r.id, r.topic, r.type, r.client, r.data, $6
FROM unnest($1::bigint[], $2::text[], $3::text[], $4::text[], $5::text[]) AS r(id, topic, type, client, data)`

// Append implements Log: inside the relay's transaction, ids from max(id)+1 in the rows' order.
func (l *PostgresLog) Append(ctx context.Context, tx pgx.Tx, rows []OutboxRow, at time.Time) error {
	if len(rows) == 0 {
		return nil
	}
	var top int64
	if err := tx.QueryRow(ctx, `SELECT coalesce(max(id), 0) FROM fw_push_log`).Scan(&top); err != nil {
		return fmt.Errorf("push: read the log's last id: %w", err)
	}
	ids := make([]int64, len(rows))
	topics := make([]string, len(rows))
	types := make([]string, len(rows))
	clients := make([]*string, len(rows))
	data := make([]string, len(rows))
	for i, r := range rows {
		ids[i] = top + int64(i) + 1
		topics[i], types[i], data[i] = r.Topic, r.Type, r.Data
		if r.Client != "" {
			c := r.Client
			clients[i] = &c
		}
	}
	if _, err := tx.Exec(ctx, appendSQL, ids, topics, types, clients, data, at); err != nil {
		return fmt.Errorf("push: append to the log: %w", err)
	}
	if _, err := tx.Exec(ctx, `SELECT pg_notify($1, $2)`, channelLog, strconv.FormatInt(ids[len(ids)-1], 10)); err != nil {
		return fmt.Errorf("push: notify the log's followers: %w", err)
	}
	return nil
}

// ReadAfter implements Log.
func (l *PostgresLog) ReadAfter(ctx context.Context, after int64, limit int) ([]Entry, error) {
	rows, err := l.db.Pool().Query(ctx,
		`SELECT id, topic, type, coalesce(client, ''), data, logged_at FROM fw_push_log WHERE id > $1 ORDER BY id LIMIT $2`, after, limit)
	if err != nil {
		return nil, fmt.Errorf("push: read the log: %w", err)
	}
	out, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (Entry, error) {
		var e Entry
		err := row.Scan(&e.ID, &e.Topic, &e.Type, &e.Client, &e.Data, &e.LoggedAt)
		return e, err
	})
	if err != nil {
		return nil, fmt.Errorf("push: read the log: %w", err)
	}
	return out, nil
}

// Latest implements Log.
func (l *PostgresLog) Latest(ctx context.Context) (int64, error) {
	var id int64
	if err := l.db.Pool().QueryRow(ctx, `SELECT coalesce(max(id), 0) FROM fw_push_log`).Scan(&id); err != nil {
		return 0, fmt.Errorf("push: read the log's last id: %w", err)
	}
	return id, nil
}

// Trimmed implements Log: the ids have no gaps, so everything below the oldest kept was deleted.
func (l *PostgresLog) Trimmed(ctx context.Context) (int64, error) {
	var id int64
	if err := l.db.Pool().QueryRow(ctx, `SELECT coalesce(min(id) - 1, 0) FROM fw_push_log`).Scan(&id); err != nil {
		return 0, fmt.Errorf("push: read the log's first id: %w", err)
	}
	return id, nil
}

// Prune implements Log. The newest row stays, so max(id) + 1 never starts again at 1.
func (l *PostgresLog) Prune(ctx context.Context, before time.Time) (int64, error) {
	tag, err := l.db.Pool().Exec(ctx,
		`DELETE FROM fw_push_log WHERE logged_at < $1 AND id < (SELECT max(id) FROM fw_push_log)`, before)
	if err != nil {
		return 0, fmt.Errorf("push: prune the log: %w", err)
	}
	return tag.RowsAffected(), nil
}

// Follow implements Log: LISTEN on fw_push_log and fw_push_end, on a connection of its own.
func (l *PostgresLog) Follow(ctx context.Context, f Follower) error {
	return listen(ctx, l.db, []string{channelLog, channelEnd}, func(channel, payload string) {
		switch channel {
		case channelLog:
			f.Grown()
		case channelEnd:
			var h Holder
			if json.Unmarshal([]byte(payload), &h) == nil && !h.IsZero() {
				f.End(h)
			}
		}
	}, f.Grown)
}

// End implements Log: pg_notify('fw_push_end', <the holder as JSON>), in its own transaction.
func (l *PostgresLog) End(ctx context.Context, h Holder) error {
	payload, err := json.Marshal(h)
	if err != nil {
		return err
	}
	if _, err := l.db.Pool().Exec(ctx, `SELECT pg_notify($1, $2)`, channelEnd, string(payload)); err != nil {
		return fmt.Errorf("push: notify the end of streams: %w", err)
	}
	return nil
}

// Ping implements Log.
func (l *PostgresLog) Ping(ctx context.Context) error { return l.db.Ping(ctx) }

// listen holds a connection of its own (taken out of the pool) listening on channels, calls
// listening once the LISTENs are in place and note for every notification, until ctx ends (nil) or
// the connection fails (the error).
func listen(ctx context.Context, db *database.DB, channels []string, note func(channel, payload string), listening func()) error {
	pooled, err := db.Pool().Acquire(ctx)
	if err != nil {
		if ctx.Err() != nil {
			return nil
		}
		return fmt.Errorf("push: take a connection to listen: %w", err)
	}
	conn := pooled.Hijack()
	defer func() { _ = conn.Close(context.WithoutCancel(ctx)) }()
	for _, ch := range channels {
		if _, err := conn.Exec(ctx, "LISTEN "+pgx.Identifier{ch}.Sanitize()); err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("push: listen on %s: %w", ch, err)
		}
	}
	listening()
	for {
		n, err := conn.WaitForNotification(ctx)
		if err != nil {
			if ctx.Err() != nil || errors.Is(err, context.Canceled) {
				return nil
			}
			return fmt.Errorf("push: wait for a notification: %w", err)
		}
		note(n.Channel, n.Payload)
	}
}
