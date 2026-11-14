package partitions

import (
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/jackc/pgx/v5"
)

// ensureCurrent creates the partitions of the interval that contains now and the next Ahead ones.
func (p *Partitions) ensureCurrent(ctx context.Context, tg *target, now time.Time) error {
	starts := make([]time.Time, 0, tg.Ahead+1)
	s := tg.Interval.Start(now)
	for range tg.Ahead + 1 {
		starts = append(starts, s)
		s = tg.Interval.Next(s)
	}
	if _, err := p.ensureStarts(ctx, tg, starts); err != nil {
		return err
	}
	p.setAhead(ctx, tg, now)
	return nil
}

// ensureStarts creates the missing partitions for the interval starts, in one transaction that
// holds the table's advisory lock (LockKey), so replicas starting together do not race on the
// catalog. Never a DEFAULT partition.
func (p *Partitions) ensureStarts(ctx context.Context, tg *target, starts []time.Time) (int, error) {
	created := 0
	err := pgx.BeginFunc(ctx, tg.db.Pool(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "SET LOCAL lock_timeout = '"+ddlLockTimeout+"'"); err != nil {
			return err
		}
		if _, err := tx.Exec(ctx, "SELECT pg_advisory_xact_lock($1)", LockKey(tg.schema, tg.rel)); err != nil {
			return err
		}
		kids, err := tg.children(ctx, tx)
		if err != nil {
			return err
		}
		have := map[string]bool{}
		for _, c := range kids {
			if c.schema == tg.schema {
				have[c.name] = true
			}
		}
		for _, start := range starts {
			name := PartitionName(tg.rel, start)
			if have[name] {
				continue
			}
			// CREATE TABLE IF NOT EXISTS ... PARTITION OF does nothing when a table of that name exists, even
			// one that is not attached: a detached leftover in a current or future range must be loud.
			var exists bool
			if err := tx.QueryRow(ctx, "SELECT to_regclass($1) IS NOT NULL", tg.childIdent(name)).Scan(&exists); err != nil {
				return err
			}
			if exists {
				return fmt.Errorf("table %s exists but is not attached as a partition of %s (a detached leftover?); "+
					"attach it, or archive and drop it, so inserts for its range have a partition", tg.childIdent(name), tg.ident())
			}
			sql := fmt.Sprintf("CREATE TABLE IF NOT EXISTS %s PARTITION OF %s FOR VALUES FROM ('%s') TO ('%s')",
				tg.childIdent(name), tg.ident(), tg.By.Bound(start), tg.By.Bound(tg.Interval.Next(start)))
			if _, err := tx.Exec(ctx, sql); err != nil {
				return fmt.Errorf("create partition %s: %w", name, err)
			}
			created++
			p.log.Info("partition created", slog.String("table", tg.qualified), slog.String("partition", name))
		}
		return nil
	})
	return created, err
}

// setAhead updates the gauge from the catalog: the partitions that start after the current interval.
func (p *Partitions) setAhead(ctx context.Context, tg *target, now time.Time) {
	kids, err := tg.children(ctx, tg.db.Pool())
	if err != nil {
		return
	}
	starts, _ := tg.ours(kids)
	cur := tg.Interval.Start(now)
	n := 0
	for _, s := range starts {
		if s.After(cur) {
			n++
		}
	}
	tg.ahead.Set(float64(n))
}
