package flowcatalyst

import (
	"context"
	"errors"
	"fmt"
	"regexp"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/internal/dbtx"
	"github.com/flowcatalyst/framework/go/locks"
)

var handlerNameRe = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,200}$`)

const insertOnce = "INSERT INTO fw_dispatch_once (handler, job_id) VALUES ($1, $2) ON CONFLICT DO NOTHING"

// OnceResult is the outcome of [Once]: the work ran and returned a value, or it had already been
// done for this job.
type OnceResult[R any] struct {
	ran   bool
	value R
}

// Ran reports whether the work ran in this call.
func (r OnceResult[R]) Ran() bool { return r.ran }

// Value returns the work's value, and false when the work was already done.
func (r OnceResult[R]) Value() (R, bool) { return r.value, r.ran }

// ErrInvalidHandlerName is wrapped by the error [Once] returns for a handler name outside
// [A-Za-z0-9._:-]{1,200}.
var ErrInvalidHandlerName = errors.New("a once handler name must match [A-Za-z0-9._:-]{1,200}")

// Once runs work at most once per (handler, dispatch job), in one transaction with the record of
// having run it (flowcatalyst contract section 4a): delivery is at-least-once, a handler can run
// twice for one job (a timeout after a commit, a lost response), and the job id is the idempotency
// key. The record row is inserted first (ON CONFLICT DO NOTHING), so a concurrent repeat waits for
// the first and then skips (or runs, if the first rolled back); if work returns an error or panics
// the transaction, record included, is rolled back and a redelivery runs it again.
//
// work gets the open transaction (READ COMMITTED); use tx.Conn() for its queries; it must not
// commit. The table fw_dispatch_once is created by the module's migration set, applied to the
// default database instance. Handler names are [A-Za-z0-9._:-]{1,200}. Old rows are purged by
// [Build] (flowcatalyst.receivers.once-retention).
//
// A use case joins this transaction with [usecase.Defined.RunJoined], handing it work's tx
// (write-path contract 4a): its writes, events and audit row go in the same transaction as the
// record, under a savepoint, and are committed or rolled back with it.
func Once[R any](ctx context.Context, meta DeliveryMeta, db *database.DB, handler string,
	work func(ctx context.Context, tx *database.Tx) (R, error),
) (OnceResult[R], error) {
	if !handlerNameRe.MatchString(handler) {
		return OnceResult[R]{}, ErrInvalidHandlerName
	}
	if meta.JobID == "" {
		return OnceResult[R]{}, errors.New("Once needs the delivery's metadata (its job id is empty)")
	}
	var out OnceResult[R]
	err := pgx.BeginTxFunc(ctx, db.Pool(), pgx.TxOptions{IsoLevel: pgx.ReadCommitted}, func(tx pgx.Tx) error {
		tag, err := tx.Exec(ctx, insertOnce, handler, meta.JobID)
		if err != nil {
			return err
		}
		if tag.RowsAffected() == 0 {
			return nil // already done: the transaction ends with nothing to keep
		}
		v, err := work(ctx, dbtx.New(tx))
		if err != nil {
			return err
		}
		out = OnceResult[R]{ran: true, value: v}
		return nil
	})
	if err != nil {
		return OnceResult[R]{}, err
	}
	return out, nil
}

// purge deletes fw_dispatch_once rows older than the retention, in batches.
const (
	purgeBatch      = 5_000
	purgeMaxBatches = 200
	purgeLockName   = "fw-dispatch-once-purge"
	purgeTable      = "fw_dispatch_once"
	deleteOld       = "DELETE FROM fw_dispatch_once WHERE ctid IN (SELECT ctid FROM fw_dispatch_once WHERE processed_at < $1 LIMIT $2)"
)

type oncePurge struct {
	db        *database.DB
	retention time.Duration
	now       func() time.Time
}

// deleteOlderThan deletes rows processed before cutoff, in batches of 5 000, at most 200 batches.
func (p *oncePurge) deleteOlderThan(ctx context.Context, cutoff time.Time) (int64, error) {
	var total int64
	for range purgeMaxBatches {
		if ctx.Err() != nil {
			return total, ctx.Err()
		}
		tag, err := p.db.Pool().Exec(ctx, deleteOld, cutoff, purgeBatch)
		if err != nil {
			return total, err
		}
		total += tag.RowsAffected()
		if tag.RowsAffected() < purgeBatch {
			break
		}
	}
	return total, nil
}

// runOnce is one purge: the cutoff is the application's clock minus the retention (not the
// database's clock).
func (p *oncePurge) runOnce(ctx context.Context) (int64, error) {
	return p.deleteOlderThan(ctx, p.now().Add(-p.retention))
}

func purgeGateKey() (locks.Key, error) {
	k, err := locks.ServiceKey(purgeLockName)
	if err != nil {
		return locks.Key{}, fmt.Errorf("purge lock key: %w", err)
	}
	return k, nil
}
