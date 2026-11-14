package partitions

import (
	"fmt"
	"regexp"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

type retentionKind int

const (
	retDrop retentionKind = iota + 1
	retArchive
	retKeep
)

// Retention says what happens to a partition once its whole range is older than a duration. It is
// required: a Table without one does not start. The zero Retention is "not given".
type Retention struct {
	kind  retentionKind
	after time.Duration
	store string
}

// DropAfter drops a partition when its whole range ended at least d ago. d must be at least one
// interval (day 24h, week 7 days, month 31 days).
func DropAfter(d time.Duration) Retention { return Retention{kind: retDrop, after: d} }

// ArchiveThenDropAfter writes a partition to the named object store as gzip'd NDJSON and drops
// it, once its whole range ended at least d ago. Nothing is dropped unless the archive was
// written and verified.
func ArchiveThenDropAfter(d time.Duration, store string) Retention {
	return Retention{kind: retArchive, after: d, store: store}
}

// KeepForever never removes a partition: the decision to keep everything is written down.
func KeepForever() Retention { return Retention{kind: retKeep} }

func (r Retention) String() string {
	switch r.kind {
	case retDrop:
		return "DropAfter(" + r.after.String() + ")"
	case retArchive:
		return "ArchiveThenDropAfter(" + r.after.String() + ", " + fmt.Sprintf("%q", r.store) + ")"
	case retKeep:
		return "KeepForever()"
	}
	return "none"
}

// AheadMin and AheadMax bound [Table.Ahead].
const (
	AheadMin = 1
	AheadMax = 24
)

// MaxEnsureRange is the most partitions one [Partitions.Ensure] call creates.
const MaxEnsureRange = 400

// Table declares one partitioned table. Which tables are partitioned and for how long they are
// kept are facts of the application (config-and-code contract), so they are code, given to
// [WithTable].
type Table struct {
	// Name is the table, optionally schema-qualified ("readings", "telemetry.readings"):
	// lower-case letters, digits and underscores.
	Name string
	// Database names the database instance of the table; empty means the default instance.
	Database string
	// By is the partition key: [ByID] or [ByColumn].
	By Key
	// Interval is the width of a partition: [Day], [Week] or [Month].
	Interval Interval
	// Ahead is how many partitions beyond the current one are kept created: 1 to 24.
	Ahead int
	// Retention is what becomes of old partitions. Required.
	Retention Retention
}

var identifier = regexp.MustCompile(`^[a-z_][a-z0-9_]*$`)

// splitName splits "schema.table" or "table"; the schema is "" when not given.
func splitName(name string) (schema, table string, ok bool) {
	parts := strings.Split(name, ".")
	switch len(parts) {
	case 1:
		return "", parts[0], identifier.MatchString(parts[0])
	case 2:
		return parts[0], parts[1], identifier.MatchString(parts[0]) && identifier.MatchString(parts[1])
	}
	return "", "", false
}

// check returns every problem of the declaration that can be found without the database.
func (t Table) check() []string {
	var out []string
	if _, table, ok := splitName(t.Name); !ok {
		out = append(out, fmt.Sprintf("name %q must be [schema.]table, each part matching [a-z_][a-z0-9_]*", t.Name))
	} else if len(table)+nameSuffixLen > maxIdentifier {
		out = append(out, fmt.Sprintf("table name %q is too long: its partition names (%s_pYYYYMMDD) would be %d bytes, over PostgreSQL's %d",
			table, table, len(table)+nameSuffixLen, maxIdentifier))
	}
	switch t.By.kind {
	case kindID:
		if !fw.ValidIDPrefix(t.By.prefix) {
			out = append(out, fmt.Sprintf("ByID prefix %q must match [a-z][a-z0-9]*", t.By.prefix))
		}
		if !identifier.MatchString(t.By.column) {
			out = append(out, fmt.Sprintf("key column %q must match [a-z_][a-z0-9_]*", t.By.column))
		}
	case kindColumn:
		if !identifier.MatchString(t.By.column) {
			out = append(out, fmt.Sprintf("key column %q must match [a-z_][a-z0-9_]*", t.By.column))
		}
	default:
		out = append(out, "no partition key: use partitions.ByID or partitions.ByColumn")
	}
	if !t.Interval.valid() {
		out = append(out, "no interval: use partitions.Day, partitions.Week or partitions.Month")
	}
	if t.Ahead < AheadMin || t.Ahead > AheadMax {
		out = append(out, fmt.Sprintf("Ahead must be %d to %d, was %d", AheadMin, AheadMax, t.Ahead))
	}
	switch t.Retention.kind {
	case retDrop, retArchive:
		if t.Interval.valid() && t.Retention.after < t.Interval.span() {
			out = append(out, fmt.Sprintf("retention %s is shorter than one %s interval (%s)", t.Retention.after, t.Interval, t.Interval.span()))
		}
		if t.Retention.kind == retArchive && t.Retention.store == "" {
			out = append(out, "ArchiveThenDropAfter needs the name of an object store")
		}
	case retKeep:
	default:
		out = append(out, "no retention: say what becomes of old partitions with partitions.DropAfter, ArchiveThenDropAfter or KeepForever")
	}
	return out
}

// due reports whether the partition that starts at start may be removed at now: its whole range
// ended at least the retention ago, and, whatever the retention says, it ended at least one
// interval ago. The second condition is the guard against a skewed clock or a misconfigured
// retention: the current and future partitions are never due (contract section 6).
func (t Table) due(start, now time.Time) bool {
	if t.Retention.kind != retDrop && t.Retention.kind != retArchive {
		return false
	}
	upper := t.Interval.Next(start)
	if upper.After(now.Add(-t.Retention.after)) {
		return false
	}
	return !upper.After(now.Add(-t.Interval.span()))
}
