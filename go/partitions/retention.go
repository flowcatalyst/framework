package partitions

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"slices"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Step names a step of a pass, as the failure counter and a [PassResult] name it.
type Step string

// The steps of a pass.
const (
	// StepEnsure is creating the partitions ahead.
	StepEnsure Step = "ensure"
	// StepRetention is listing, detaching and dropping the due partitions.
	StepRetention Step = "retention"
	// StepArchive is writing a due partition to its object store (ArchiveThenDropAfter only).
	StepArchive Step = "archive"
)

// stepError says which step an error belongs to; Error and Unwrap are the cause's.
type stepError struct {
	step Step
	err  error
}

func (e *stepError) Error() string { return e.err.Error() }
func (e *stepError) Unwrap() error { return e.err }

// retained is what a retention pass did: partitions dropped, partitions archived, and the steps that
// failed.
type retained struct {
	dropped, archived int
	failed            []Step
}

func (r *retained) fail(err error) {
	step := StepRetention
	if se, ok := errors.AsType[*stepError](err); ok {
		step = se.step
	}
	if !slices.Contains(r.failed, step) {
		r.failed = append(r.failed, step)
	}
}

// retain removes the partitions of the table that are due at now (contract section 5). One
// partition's failure does not stop the others; nothing is dropped unless every step before the
// drop succeeded. For ArchiveThenDrop it also finishes partitions an earlier pass detached and
// did not get to drop (recovery).
func (p *Partitions) retain(ctx context.Context, tg *target, now time.Time) (retained, error) {
	var out retained
	if tg.Retention.kind != retDrop && tg.Retention.kind != retArchive {
		return out, nil
	}
	kids, err := tg.children(ctx, tg.db.Pool())
	if err != nil {
		p.failures.With(tg.qualified, "retention").Inc()
		out.failed = append(out.failed, StepRetention)
		return out, fmt.Errorf("retention: list partitions: %w", err)
	}
	starts, strays := tg.ours(kids)
	p.logStrays(tg, strays)
	type dueChild struct {
		name     string
		start    time.Time
		detached bool
	}
	var due []dueChild
	for name, start := range starts {
		if tg.due(start, now) {
			due = append(due, dueChild{name: name, start: start})
		}
	}
	if tg.Retention.kind == retArchive {
		names, err := tg.detachedTables(ctx)
		if err != nil {
			p.failures.With(tg.qualified, "retention").Inc()
			out.failed = append(out.failed, StepRetention)
			return out, fmt.Errorf("retention: list detached tables: %w", err)
		}
		for _, name := range names {
			if start, ok := parsePartitionName(tg.rel, tg.Interval, name); ok && tg.due(start, now) {
				due = append(due, dueChild{name: name, start: start, detached: true})
			}
		}
	}
	slices.SortFunc(due, func(a, b dueChild) int { return a.start.Compare(b.start) })

	var errs []error
	for _, d := range due {
		if ctx.Err() != nil {
			errs = append(errs, ctx.Err())
			out.fail(ctx.Err())
			break
		}
		archived, err := p.remove(ctx, tg, d.name, d.detached)
		if archived {
			out.archived++
		}
		if err != nil {
			out.fail(err)
			errs = append(errs, fmt.Errorf("partition %s: %w", d.name, err))
			continue
		}
		out.dropped++
	}
	return out, errors.Join(errs...)
}

// detachMark is the table comment the archiving detach sets, in the detach's transaction, on the
// partition it detaches. Recovery touches only tables that carry it: a table of a partition's name
// that this module did not detach is never archived or dropped.
func (tg *target) detachMark() string {
	return "fw-partitions: detached from " + tg.qualified + " for archiving"
}

// detachedTables lists the plain tables of the parent's schema that are not partitions of anything
// and carry the detach mark: candidates for recovery when their names are this framework's
// partition names.
func (tg *target) detachedTables(ctx context.Context) ([]string, error) {
	rows, err := tg.db.Pool().Query(ctx, `SELECT c.relname FROM pg_class c
WHERE c.relnamespace = $1::regnamespace AND c.relkind = 'r' AND NOT c.relispartition AND c.relname LIKE $2
  AND obj_description(c.oid, 'pg_class') = $3`,
		quoteIdent(tg.schema), tg.rel+"\\_p%", tg.detachMark())
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var n string
		if err := rows.Scan(&n); err != nil {
			return nil, err
		}
		out = append(out, n)
	}
	return out, rows.Err()
}

// logStrays logs, once per partition, what is attached to the parent but not ours.
func (p *Partitions) logStrays(tg *target, strays []child) {
	p.strayMu.Lock()
	defer p.strayMu.Unlock()
	for _, c := range strays {
		k := tg.qualified + "/" + c.schema + "." + c.name
		if p.stray[k] {
			continue
		}
		p.stray[k] = true
		p.log.Warn("partition is not one of this framework's names for the table; left alone",
			slog.String("table", tg.qualified), slog.String("partition", c.schema+"."+c.name))
	}
}

// remove retires one due partition. DropAfter: detach and drop in one transaction. ArchiveThenDrop:
// detach first (a short transaction; from then on no write reaches the table through the parent, so
// the archive cannot differ from what is dropped), archive the detached table, verify, drop. A failure
// after the detach leaves the table detached, and the next pass finds it again (recovery); it is never
// re-attached. It reports whether the partition was archived (and verified), even when the drop then
// failed; an error carries the failed [Step].
func (p *Partitions) remove(ctx context.Context, tg *target, name string, detached bool) (archived bool, err error) {
	if tg.Retention.kind == retArchive {
		if !detached {
			if err := p.detach(ctx, tg, name, false); err != nil {
				p.failures.With(tg.qualified, "retention").Inc()
				p.log.Error("partition detach failed", slog.String("table", tg.qualified),
					slog.String("partition", name), slog.String("error", err.Error()))
				return false, &stepError{StepRetention, fmt.Errorf("detach: %w", err)}
			}
			p.log.Info("partition detached for archiving", slog.String("table", tg.qualified), slog.String("partition", name))
		}
		if _, err := p.archive(ctx, tg, name); err != nil {
			p.failures.With(tg.qualified, "archive").Inc()
			p.log.Error("partition archive failed; left detached, not dropped", slog.String("table", tg.qualified),
				slog.String("partition", name), slog.String("error", err.Error()))
			return false, &stepError{StepArchive, fmt.Errorf("archive: %w", err)}
		}
		archived = true
		if err := p.dropDetached(ctx, tg, name); err != nil {
			p.failures.With(tg.qualified, "retention").Inc()
			p.log.Error("partition drop failed", slog.String("table", tg.qualified),
				slog.String("partition", name), slog.String("error", err.Error()))
			return archived, &stepError{StepRetention, fmt.Errorf("drop: %w", err)}
		}
	} else if err := p.detach(ctx, tg, name, true); err != nil {
		p.failures.With(tg.qualified, "retention").Inc()
		p.log.Error("partition drop failed", slog.String("table", tg.qualified),
			slog.String("partition", name), slog.String("error", err.Error()))
		return false, &stepError{StepRetention, fmt.Errorf("drop: %w", err)}
	}
	tg.dropped.Inc()
	p.log.Info("partition dropped", slog.String("table", tg.qualified), slog.String("partition", name))
	return archived, nil
}

// detach detaches the partition in one transaction, and drops it in the same transaction when drop
// is true (a failure rolls the detach back).
//
// Plain DETACH, not DETACH ... CONCURRENTLY: the concurrent form cannot run in a transaction and
// leaves a detached-pending state that a failed run must finish. The plain form takes a stronger
// lock on the parent for an instant; lock_timeout bounds the wait, so a long query makes the pass
// fail and retry next time instead of queueing every insert behind the lock.
func (p *Partitions) detach(ctx context.Context, tg *target, name string, drop bool) error {
	return pgx.BeginFunc(ctx, tg.db.Pool(), func(tx pgx.Tx) error {
		if _, err := tx.Exec(ctx, "SET LOCAL lock_timeout = '"+ddlLockTimeout+"'"); err != nil {
			return err
		}
		ident := tg.childIdent(name)
		if _, err := tx.Exec(ctx, "ALTER TABLE "+tg.ident()+" DETACH PARTITION "+ident); err != nil {
			return err
		}
		if drop {
			_, err := tx.Exec(ctx, "DROP TABLE "+ident)
			return err
		}
		// COMMENT takes no parameters; the mark holds only the validated [schema.]table name
		_, err := tx.Exec(ctx, "COMMENT ON TABLE "+ident+" IS '"+strings.ReplaceAll(tg.detachMark(), "'", "''")+"'")
		return err
	})
}

func (p *Partitions) dropDetached(ctx context.Context, tg *target, name string) error {
	_, err := tg.db.Pool().Exec(ctx, "DROP TABLE "+tg.childIdent(name))
	return err
}
