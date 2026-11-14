package partitions

import (
	"context"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"

	"github.com/jackc/pgx/v5"
)

// Position says where a partition is against the module's clock.
type Position string

// The positions.
const (
	// PositionPast is a partition whose range ended.
	PositionPast Position = "PAST"
	// PositionCurrent is the partition that contains now.
	PositionCurrent Position = "CURRENT"
	// PositionFuture is a partition that starts after now.
	PositionFuture Position = "FUTURE"
)

// RetentionKind is what retention does to a partition past its time.
type RetentionKind string

// The retention kinds.
const (
	// RetentionDrop is [DropAfter].
	RetentionDrop RetentionKind = "DROP"
	// RetentionArchiveThenDrop is [ArchiveThenDropAfter].
	RetentionArchiveThenDrop RetentionKind = "ARCHIVE_THEN_DROP"
	// RetentionKeep is [KeepForever].
	RetentionKeep RetentionKind = "KEEP"
)

// RetentionStatus is a table's declared retention.
type RetentionStatus struct {
	Kind RetentionKind
	// After is the declared duration; zero for KEEP.
	After time.Duration
	// Store is the object store an archive goes to; empty unless Kind is ARCHIVE_THEN_DROP.
	Store string
}

// StepStatus is the outcome of one step of a pass, as the last-pass record keeps it.
type StepStatus string

// The step statuses.
const (
	// StepOK means the step ran and succeeded.
	StepOK StepStatus = "OK"
	// StepFailed means the step failed (the log has the cause).
	StepFailed StepStatus = "FAILED"
	// StepSkipped means the step does not apply to the table (retention for KeepForever, archive
	// unless the retention archives).
	StepSkipped StepStatus = "SKIPPED"
)

// LastPass is the last pass of a table, by whichever replica ran it (the loop or a manual one).
type LastPass struct {
	EndedAt time.Time
	Ensure  StepStatus
	// Retention and Archive are SKIPPED when the step does not apply to the table.
	Retention, Archive         StepStatus
	Created, Dropped, Archived int
}

// ArchivedPartition is what the manifest holds for a partition that is archived.
type ArchivedPartition struct {
	Objects    int
	Rows       int64
	Bytes      int64
	ArchivedAt time.Time
}

// PartitionStatus is one attached partition of a table.
type PartitionStatus struct {
	Name string
	// From is inclusive and To exclusive, in UTC.
	From, To time.Time
	Position Position
	// Bytes is pg_total_relation_size: the partition with its indexes and TOAST.
	Bytes int64
	// Rows is the planner's estimate (pg_class.reltuples); nil until the partition was analysed or
	// vacuumed. Never a count(*).
	Rows *int64
	// RemovalDue is the first instant at which retention removes the partition (the pass that runs
	// at or after it, skew guard included); nil for KeepForever.
	RemovalDue *time.Time
	// Archived is set when the manifest holds an archive of this partition.
	Archived *ArchivedPartition
}

// TableStatus is the state of one declared table, read from the catalog (a fixed number of queries,
// whatever the number of partitions).
type TableStatus struct {
	// Name is the schema-qualified table.
	Name string
	// Database is the database instance; "" is the default instance.
	Database string
	// Key is the declared partition key, "ByID(\"rdg\") on id".
	Key string
	// Interval is the declared interval.
	Interval  Interval
	Ahead     int
	Retention RetentionStatus
	// Healthy is the decision of the health check partitions:<table>: the partition of the next
	// interval is attached.
	Healthy bool
	// LastPass is nil when no pass was recorded (a new table, or an instance without
	// fw_partition_passes).
	LastPass *LastPass
	// Partitions are the attached partitions the module named, oldest first.
	Partitions []PartitionStatus
	// Detached names (schema-qualified) the tables that carry the module's detach mark and are not
	// attached: a pass that died between detaching and dropping, which the next pass finishes.
	Detached []string
	// Strays names (schema-qualified) the attached partitions that are not the module's names; the
	// module leaves them alone.
	Strays []string
	// Bytes sums the partitions; Rows sums the known estimates only.
	Bytes, Rows int64
}

// ArchiveRecord is one archived partition, from the manifest.
type ArchiveRecord struct {
	// Table is the schema-qualified table, Partition the partition's name.
	Table, Partition string
	// From and To are the partition's range, parsed from its name; zero when the name is not one of
	// the table's names (the declared interval changed).
	From, To   time.Time
	Objects    int
	Rows       int64
	Bytes      int64
	ArchivedAt time.Time
	// Key is the first object's key in the store ([ArchiveKey]); the others sit beside it.
	Key string
}

// MaxArchivePage is the most records [Partitions.Archives] returns.
const MaxArchivePage = 200

func (i Interval) code() string { return strings.ToUpper(i.String()) }

// Code returns the interval's wire name: DAY, WEEK, MONTH or YEAR.
func (i Interval) Code() string { return i.code() }

// Status returns the status of every declared table, in declaration order. It reads the catalog
// and the module's own tables only; it never counts rows.
func (p *Partitions) Status(ctx context.Context) ([]TableStatus, error) {
	out := make([]TableStatus, 0, len(p.targets))
	for _, tg := range p.targets {
		st, err := p.tableStatus(ctx, tg)
		if err != nil {
			return nil, fmt.Errorf("table %s: %w", tg.Name, err)
		}
		out = append(out, st)
	}
	return out, nil
}

// TableStatus returns the status of one table, named as declared or schema-qualified; false when
// no declared table has that name.
func (p *Partitions) TableStatus(ctx context.Context, table string) (TableStatus, bool, error) {
	tg, ok := p.lookup(table)
	if !ok {
		return TableStatus{}, false, nil
	}
	st, err := p.tableStatus(ctx, tg)
	if err != nil {
		return TableStatus{}, true, fmt.Errorf("table %s: %w", tg.Name, err)
	}
	return st, true, nil
}

type childStat struct {
	schema, name string
	bytes        int64
	tuples       float32
}

// tableStatus is five queries at most: the module's two tables (existence), the attached children
// with sizes and estimates, the manifest summary, the detached tables and the last pass.
func (p *Partitions) tableStatus(ctx context.Context, tg *target) (TableStatus, error) {
	pool := tg.db.Pool()
	now := p.clock.Now()
	manifest, passes, err := tg.auxTables(ctx)
	if err != nil {
		return TableStatus{}, err
	}

	rows, err := pool.Query(ctx, `SELECT n.nspname::text, c.relname::text, pg_total_relation_size(c.oid)::bigint, c.reltuples
FROM pg_inherits i
JOIN pg_class c ON c.oid = i.inhrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE i.inhparent = $1::regclass`, tg.ident())
	if err != nil {
		return TableStatus{}, err
	}
	var stats []childStat
	for rows.Next() {
		var c childStat
		if err := rows.Scan(&c.schema, &c.name, &c.bytes, &c.tuples); err != nil {
			rows.Close()
			return TableStatus{}, err
		}
		stats = append(stats, c)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return TableStatus{}, err
	}
	kids := make([]child, len(stats))
	for i, c := range stats {
		kids[i] = child{schema: c.schema, name: c.name}
	}
	starts, strays := tg.ours(kids)

	archived := map[string]ArchivedPartition{}
	if manifest {
		archived, err = manifestSummary(ctx, tg)
		if err != nil {
			return TableStatus{}, err
		}
	}
	detached, err := tg.detachedTables(ctx)
	if err != nil {
		return TableStatus{}, err
	}
	var last *LastPass
	if passes {
		if last, err = readLastPass(ctx, tg); err != nil {
			return TableStatus{}, err
		}
	}

	st := TableStatus{
		Name: tg.qualified, Database: tg.Database, Key: tg.By.String(), Interval: tg.Interval, Ahead: tg.Ahead,
		Retention: tg.retentionStatus(), Healthy: tg.nextPartitionProblem(kids, now) == nil, LastPass: last,
		Partitions: []PartitionStatus{}, Detached: []string{}, Strays: []string{},
	}
	for _, c := range stats {
		start, ours := starts[c.name]
		if !ours || c.schema != tg.schema {
			continue
		}
		ps := PartitionStatus{Name: c.name, From: start, To: tg.Interval.Next(start), Bytes: c.bytes}
		switch {
		case ps.To.After(now) && !start.After(now):
			ps.Position = PositionCurrent
		case start.After(now):
			ps.Position = PositionFuture
		default:
			ps.Position = PositionPast
		}
		if c.tuples >= 0 {
			n := int64(c.tuples)
			ps.Rows = &n
			st.Rows += n
		}
		if due, ok := tg.removalDue(start); ok {
			ps.RemovalDue = &due
		}
		if a, ok := archived[c.name]; ok {
			ps.Archived = &a
		}
		st.Bytes += c.bytes
		st.Partitions = append(st.Partitions, ps)
	}
	slices.SortFunc(st.Partitions, func(a, b PartitionStatus) int { return a.From.Compare(b.From) })
	for _, name := range detached {
		st.Detached = append(st.Detached, tg.schema+"."+name)
	}
	slices.Sort(st.Detached)
	for _, c := range strays {
		st.Strays = append(st.Strays, c.schema+"."+c.name)
	}
	slices.Sort(st.Strays)
	return st, nil
}

func (tg *target) retentionStatus() RetentionStatus {
	switch tg.Retention.kind {
	case retDrop:
		return RetentionStatus{Kind: RetentionDrop, After: tg.Retention.after}
	case retArchive:
		return RetentionStatus{Kind: RetentionArchiveThenDrop, After: tg.Retention.after, Store: tg.Retention.store}
	case retKeep:
	}
	return RetentionStatus{Kind: RetentionKeep}
}

// manifestSummary is one query: the archived partitions of the table, with objects, rows, bytes and
// the latest archive time.
func manifestSummary(ctx context.Context, tg *target) (map[string]ArchivedPartition, error) {
	rows, err := tg.db.Pool().Query(ctx, `SELECT partition_name, count(*)::int, sum(row_count)::bigint, sum(byte_count)::bigint, max(archived_at)
FROM fw_partition_archives WHERE table_name = $1 GROUP BY partition_name`, tg.qualified)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]ArchivedPartition{}
	for rows.Next() {
		var name string
		var a ArchivedPartition
		if err := rows.Scan(&name, &a.Objects, &a.Rows, &a.Bytes, &a.ArchivedAt); err != nil {
			return nil, err
		}
		a.ArchivedAt = a.ArchivedAt.UTC()
		out[name] = a
	}
	return out, rows.Err()
}

func readLastPass(ctx context.Context, tg *target) (*LastPass, error) {
	var lp LastPass
	var ensure, retention, archive string
	err := tg.db.Pool().QueryRow(ctx, `SELECT ended_at, ensure_status, retention_status, archive_status, created, dropped, archived
FROM fw_partition_passes WHERE table_name = $1`, tg.qualified).Scan(&lp.EndedAt, &ensure, &retention, &archive, &lp.Created, &lp.Dropped, &lp.Archived)
	if err != nil {
		if errors.Is(err, pgx.ErrNoRows) {
			return nil, nil //nolint:nilnil // no pass recorded is "none", not an error
		}
		return nil, err
	}
	lp.EndedAt = lp.EndedAt.UTC()
	lp.Ensure, lp.Retention, lp.Archive = StepStatus(ensure), StepStatus(retention), StepStatus(archive)
	return &lp, nil
}

// Archives returns the archived partitions of a table (named as declared or schema-qualified) from
// the manifest, newest first, in pages: after is the Partition name of the last record of the
// previous page ("" for the first page), limit is 1 to [MaxArchivePage] (a smaller or larger value is
// clamped). A table that is not declared is [ErrTableNotDeclared]; an instance without the manifest
// has none. One query per call.
func (p *Partitions) Archives(ctx context.Context, table, after string, limit int) ([]ArchiveRecord, error) {
	tg, ok := p.lookup(table)
	if !ok {
		return nil, p.notDeclared(table)
	}
	limit = max(1, min(MaxArchivePage, limit))
	manifest, _, err := tg.auxTables(ctx)
	if err != nil {
		return nil, err
	}
	if !manifest {
		return []ArchiveRecord{}, nil
	}
	rows, err := tg.db.Pool().Query(ctx, `SELECT partition_name, count(*)::int, sum(row_count)::bigint, sum(byte_count)::bigint, max(archived_at),
       coalesce(max(object_key) FILTER (WHERE part = 1), '')
FROM fw_partition_archives WHERE table_name = $1 AND ($2 = '' OR partition_name < $2)
GROUP BY partition_name ORDER BY partition_name DESC LIMIT $3`, tg.qualified, after, limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []ArchiveRecord{}
	for rows.Next() {
		r := ArchiveRecord{Table: tg.qualified}
		if err := rows.Scan(&r.Partition, &r.Objects, &r.Rows, &r.Bytes, &r.ArchivedAt, &r.Key); err != nil {
			return nil, err
		}
		r.ArchivedAt = r.ArchivedAt.UTC()
		if start, ok := parsePartitionName(tg.rel, tg.Interval, r.Partition); ok {
			r.From, r.To = start, tg.Interval.Next(start)
		}
		out = append(out, r)
	}
	return out, rows.Err()
}
