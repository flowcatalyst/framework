package partitions

import (
	"hash/fnv"
	"regexp"
	"strconv"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// The pure functions of the partitions contract (sections 2 and 3): intervals, partition names
// and bounds. They read no clock and no database; conformance/partitions/bounds.json holds their
// shared vectors.

// Interval is the width of one partition. All intervals are in UTC.
type Interval int

// The intervals.
const (
	// Day is one calendar day, 00:00 to 00:00 UTC.
	Day Interval = iota + 1
	// Week is one ISO week: Monday 00:00 UTC to the next Monday.
	Week
	// Month is one calendar month.
	Month
	// Year is one calendar year, 1 January 00:00 UTC to the next 1 January. For long-retention,
	// low-volume tables: retention of several years is then a handful of partitions.
	Year
)

func (i Interval) String() string {
	switch i {
	case Day:
		return "day"
	case Week:
		return "week"
	case Month:
		return "month"
	case Year:
		return "year"
	}
	return "unknown"
}

func (i Interval) valid() bool { return i == Day || i == Week || i == Month || i == Year }

// Start returns the start of the interval that contains t (in UTC).
func (i Interval) Start(t time.Time) time.Time {
	u := t.UTC()
	y, m, d := u.Date()
	switch i {
	case Week:
		day := time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
		return day.AddDate(0, 0, -((int(day.Weekday()) + 6) % 7)) // Monday = 0
	case Month:
		return time.Date(y, m, 1, 0, 0, 0, 0, time.UTC)
	case Year:
		return time.Date(y, time.January, 1, 0, 0, 0, 0, time.UTC)
	case Day:
		return time.Date(y, m, d, 0, 0, 0, 0, time.UTC)
	}
	panic("partitions: invalid Interval")
}

// Next returns the start of the interval after the one that starts at start.
func (i Interval) Next(start time.Time) time.Time {
	u := start.UTC()
	switch i {
	case Week:
		return u.AddDate(0, 0, 7)
	case Month:
		return u.AddDate(0, 1, 0)
	case Year:
		return u.AddDate(1, 0, 0)
	case Day:
		return u.AddDate(0, 0, 1)
	}
	panic("partitions: invalid Interval")
}

// span is the nominal width used by the rules that need a duration: a retention must be at least
// one span, and the retention pass never drops a partition that ended less than one span ago
// (contract section 6). Day 24h, Week 7 days, Month 31 days, Year 366 days.
func (i Interval) span() time.Duration {
	switch i {
	case Week:
		return 7 * 24 * time.Hour
	case Month:
		return 31 * 24 * time.Hour
	case Year:
		return 366 * 24 * time.Hour
	case Day:
		return 24 * time.Hour
	}
	return 0
}

type keyKind int

const (
	kindID keyKind = iota + 1
	kindColumn
	kindDate
)

// Key says what a table is partitioned on.
type Key struct {
	kind   keyKind
	column string
	prefix string
}

// ByID partitions on the id column "id", whose values are "<prefix>_<TSID>" (write-path contract
// section 8): the primary key stays "id" and a lookup by id prunes to one partition. The column
// must be declared COLLATE "C".
func ByID(prefix string) Key { return ByIDColumn("id", prefix) }

// ByIDColumn is [ByID] on a column other than "id".
func ByIDColumn(column, prefix string) Key {
	return Key{kind: kindID, column: column, prefix: prefix}
}

// ByColumn partitions on a timestamptz column (every unique constraint of the table must include
// it, which PostgreSQL enforces).
func ByColumn(column string) Key { return Key{kind: kindColumn, column: column} }

// ByDateColumn partitions on a date column (a service day, for example): the bounds are date
// literals. Every interval starts at UTC midnight, so the date of a bound is exact. A date column is
// declared with this key and a timestamptz column with [ByColumn]; start-up refuses the other pairing.
func ByDateColumn(column string) Key { return Key{kind: kindDate, column: column} }

// Column returns the partition key column.
func (k Key) Column() string { return k.column }

// Prefix returns the id prefix of a [ByID] key, and "" for a [ByColumn] key.
func (k Key) Prefix() string { return k.prefix }

// IsID reports whether k is a [ByID] key.
func (k Key) IsID() bool { return k.kind == kindID }

func (k Key) String() string {
	switch k.kind {
	case kindID:
		return "ByID(" + strconv.Quote(k.prefix) + ") on " + k.column
	case kindColumn:
		return "ByColumn(" + strconv.Quote(k.column) + ")"
	case kindDate:
		return "ByDateColumn(" + strconv.Quote(k.column) + ")"
	}
	return "no key"
}

// tsidInstantShift is the number of low bits of a TSID below its milliseconds: 12 sequence bits
// and 10 random bits (write-path contract section 8).
const tsidInstantShift = 22

// Bound returns the text of the partition bound for the instant t, as the SQL literal inside
// FOR VALUES FROM ('...') TO ('...'): for a [ByID] key the smallest id that carries t's
// millisecond, prefix + "_" + the TSID of (milliseconds << 22); for a [ByColumn] key t in UTC as a
// timestamptz literal "2026-11-01 00:00:00+00"; for a [ByDateColumn] key the UTC date of t as the
// literal "2026-11-01" (every interval starts at UTC midnight, so the date is exact). A bound is a lower bound inclusive and an upper
// bound exclusive, as PostgreSQL RANGE is. An instant before 1970 is bound as 1970-01-01.
func (k Key) Bound(t time.Time) string {
	if k.kind == kindID {
		return IDBound(k.prefix, t)
	}
	if k.kind == kindDate {
		return t.UTC().Format("2006-01-02")
	}
	return t.UTC().Format("2006-01-02 15:04:05") + "+00"
}

// IDBound is the id bound of the instant t: prefix + "_" + EncodeTSID(milliseconds << 22).
func IDBound(prefix string, t time.Time) string {
	ms := max(t.UnixMilli(), 0)
	return prefix + "_" + fw.EncodeTSID(uint64(ms)<<tsidInstantShift)
}

// IDRange returns the id bounds of a time window for a query on a [ByID] table:
//
//	WHERE id >= $lo AND id < $hi
//
// selects the rows whose id carries a millisecond in [from, to), and the planner prunes to the
// partitions that overlap the window. A time-window query on such a table is written with these
// bounds, never with a function of the id (a function defeats pruning).
func IDRange(prefix string, from, to time.Time) (lo, hi string) {
	return IDBound(prefix, from), IDBound(prefix, to)
}

// nameSuffix matches the "_pYYYYMMDD" a partition name ends in.
var nameSuffix = regexp.MustCompile(`^_p(\d{8})$`)

// maxIdentifier is PostgreSQL's identifier limit in bytes.
const maxIdentifier = 63

// nameSuffixLen is len("_p20261101").
const nameSuffixLen = 10

// PartitionName returns the name of the partition of the table (unqualified name) whose interval
// starts at start: "<table>_p<YYYYMMDD>", the same shape for every interval so names sort in time
// order. The partition is created in the schema of the parent.
func PartitionName(table string, start time.Time) string {
	return table + "_p" + start.UTC().Format("20060102")
}

// parsePartitionName returns the interval start a name carries for the table, and false when the
// name is not one of this framework's names for that table: another shape, a date that is not a
// start of the interval, or another table's name that merely begins the same way.
func parsePartitionName(table string, interval Interval, name string) (time.Time, bool) {
	if len(name) != len(table)+nameSuffixLen || name[:len(table)] != table {
		return time.Time{}, false
	}
	m := nameSuffix.FindStringSubmatch(name[len(table):])
	if m == nil {
		return time.Time{}, false
	}
	start, err := time.Parse("20060102", m[1])
	if err != nil || !interval.Start(start).Equal(start) {
		return time.Time{}, false
	}
	return start, true
}

// advisoryKeySalt separates this module's advisory lock keys from every other use of 64-bit
// advisory locks (the migration runner's is database.AdvisoryLockKey).
const advisoryKeySalt = "fw-partitions:"

// LockKey returns the advisory lock key that serialises the creation of partitions of one table:
// the FNV-1a 64-bit hash (offset 14695981039346656037, prime 1099511628211) of the UTF-8 bytes of
// "fw-partitions:" + schema + "." + table, as a signed 64-bit integer. It is passed to
// pg_advisory_xact_lock(bigint).
func LockKey(schema, table string) int64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(advisoryKeySalt + schema + "." + table))
	return int64(h.Sum64())
}
