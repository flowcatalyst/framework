package push

import (
	"context"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/background"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
)

// relayLoop is the relay's loop and lock name (push contract section 3).
const relayLoop = "push-relay"

// relay moves pushes from fw_push_outbox into the log (push contract section 3). One instance at a
// time runs it, under the lock svc:push-relay.
type relay struct {
	db       *database.DB // the default instance: the outbox's
	log      Log
	clock    fw.Clock
	m        *hubMetrics
	batch    int
	interval time.Duration

	// beforeCommit, when set, runs after the rows were logged and deleted and before the commit;
	// an error rolls the run back (tests: a relay that dies mid-run).
	beforeCommit func(ctx context.Context) error
}

// takeSQL takes the oldest rows of the outbox as a queue: always from the head, in id order, never
// after a remembered id, so a row whose transaction commits after a later row's is taken by a later
// run. FOR UPDATE makes a second relay wait for the first instead of taking the same rows.
const takeSQL = `SELECT id, topic, type, coalesce(client, ''), data, created_at
FROM fw_push_outbox ORDER BY id LIMIT $1 FOR UPDATE`

// once makes one run: take up to batch rows in id order, append them to the log in that order,
// delete them, commit. It returns how many rows it moved. A run is not cancelled with ctx (shutdown,
// or the lock lost): it is let finish, bounded by opTimeout, because a relay run that a second relay
// overlaps is safe (FOR UPDATE makes it wait; A1's primary key and A2's outbox ids keep the log
// right), and a cancelled query costs the pool its connection.
func (r *relay) once(parent context.Context) (int, error) {
	ctx, cancel := opContext(parent)
	defer cancel()
	tx, err := r.db.Pool().BeginTx(ctx, pgx.TxOptions{IsoLevel: pgx.ReadCommitted})
	if err != nil {
		return 0, fmt.Errorf("push relay: begin: %w", err)
	}
	committed := false
	defer func() {
		if !committed {
			_ = tx.Rollback(context.WithoutCancel(ctx))
		}
	}()
	rows, err := tx.Query(ctx, takeSQL, r.batch)
	if err != nil {
		return 0, fmt.Errorf("push relay: read the outbox: %w", err)
	}
	taken, err := pgx.CollectRows(rows, func(row pgx.CollectableRow) (OutboxRow, error) {
		var o OutboxRow
		err := row.Scan(&o.ID, &o.Topic, &o.Type, &o.Client, &o.Data, &o.CreatedAt)
		return o, err
	})
	if err != nil {
		return 0, fmt.Errorf("push relay: read the outbox: %w", err)
	}
	if len(taken) == 0 {
		return 0, nil
	}
	now := r.clock.Now()
	if err := r.log.Append(ctx, tx, taken, now); err != nil {
		return 0, err
	}
	ids := make([]int64, len(taken))
	oldest := taken[0].CreatedAt
	for i, o := range taken {
		ids[i] = o.ID
		if o.CreatedAt.Before(oldest) {
			oldest = o.CreatedAt
		}
	}
	if _, err := tx.Exec(ctx, `DELETE FROM fw_push_outbox WHERE id = ANY($1)`, ids); err != nil {
		return 0, fmt.Errorf("push relay: delete from the outbox: %w", err)
	}
	if r.beforeCommit != nil {
		if err := r.beforeCommit(ctx); err != nil {
			return 0, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return 0, fmt.Errorf("push relay: commit: %w", err)
	}
	committed = true
	r.m.published.Add(float64(len(taken)))
	r.m.relayLag.Record(now.Sub(oldest))
	return len(taken), nil
}

// drain runs until the outbox is empty (a run that moves fewer than a batch) or ctx ends.
func (r *relay) drain(ctx context.Context) error {
	for ctx.Err() == nil {
		n, err := r.once(ctx)
		if err != nil || n < r.batch {
			return err
		}
	}
	return nil
}

// run is the loop's task, admitted by the lock gate: it keeps relaying while it holds the lock,
// woken by LISTEN fw_push_outbox and at least every push.relay.interval, until ctx ends (shutdown or
// the lock lost). A failure ends the run; the loop runs it again after the interval.
func (r *relay) run(ctx context.Context, _ background.Tick) error {
	notes := make(chan struct{}, 1)
	note := func() {
		select {
		case notes <- struct{}{}:
		default:
		}
	}
	listenCtx, stop := context.WithCancel(ctx)
	listened := make(chan error, 1)
	go func() {
		listened <- listen(listenCtx, r.db, []string{channelOutbox}, func(string, string) { note() }, note)
	}()
	defer func() {
		stop()
		if listened != nil {
			<-listened
		}
	}()
	tick := time.NewTicker(r.interval)
	defer tick.Stop()
	for {
		if err := r.drain(ctx); err != nil {
			if ctx.Err() != nil {
				return nil
			}
			return err
		}
		select {
		case <-ctx.Done():
			return nil
		case err := <-listened:
			listened = nil
			if ctx.Err() != nil {
				return nil
			}
			return fmt.Errorf("push relay: %w", err)
		case <-notes:
		case <-tick.C:
		}
	}
}
