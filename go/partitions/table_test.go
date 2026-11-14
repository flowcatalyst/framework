package partitions

import (
	"strings"
	"testing"
	"time"
)

func goodTable() Table {
	return Table{Name: "readings", By: ByID("rdg"), Interval: Month, Ahead: 3, Retention: DropAfter(90 * 24 * time.Hour)}
}

func TestTableChecks(t *testing.T) {
	t.Parallel()
	if got := goodTable().check(); len(got) != 0 {
		t.Fatalf("a good table has problems: %v", got)
	}
	long := strings.Repeat("a", 54) // 54 + 10 = 64 bytes
	for _, c := range []struct {
		name   string
		mutate func(*Table)
		want   string
	}{
		{"no retention", func(t *Table) { t.Retention = Retention{} }, "no retention"},
		{"retention shorter than an interval", func(t *Table) { t.Retention = DropAfter(24 * time.Hour) }, "shorter than one month"},
		{"archive without a store", func(t *Table) { t.Retention = ArchiveThenDropAfter(90*24*time.Hour, "") }, "needs the name of an object store"},
		{"bad prefix", func(t *Table) { t.By = ByID("Rdg") }, "prefix"},
		{"bad column", func(t *Table) { t.By = ByColumn("Recorded At") }, "column"},
		{"no key", func(t *Table) { t.By = Key{} }, "no partition key"},
		{"ahead 0", func(t *Table) { t.Ahead = 0 }, "Ahead must be 1 to 24"},
		{"ahead 25", func(t *Table) { t.Ahead = 25 }, "Ahead must be 1 to 24"},
		{"no interval", func(t *Table) { t.Interval = 0 }, "no interval"},
		{"bad name", func(t *Table) { t.Name = "a.b.c" }, "[schema.]table"},
		{"upper case name", func(t *Table) { t.Name = "Readings" }, "[schema.]table"},
		{"name too long for partition names", func(t *Table) { t.Name = "s." + long }, "too long"},
	} {
		tb := goodTable()
		c.mutate(&tb)
		got := strings.Join(tb.check(), "; ")
		if !strings.Contains(got, c.want) {
			t.Errorf("%s: %q does not mention %q", c.name, got, c.want)
		}
	}
	okLong := goodTable()
	okLong.Name = strings.Repeat("a", 53)
	if got := okLong.check(); len(got) != 0 {
		t.Errorf("53 bytes plus the suffix is exactly 63: %v", got)
	}
	keep := goodTable()
	keep.Retention = KeepForever()
	if got := keep.check(); len(got) != 0 {
		t.Errorf("KeepForever: %v", got)
	}
}

func TestDue(t *testing.T) {
	t.Parallel()
	tb := goodTable()                                      // monthly, drop after 90 days
	now := time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)  // cutoff 2026-08-16
	aug := time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC)     // ends 2026-09-01: after the cutoff
	jul := time.Date(2026, 7, 1, 0, 0, 0, 0, time.UTC)     // ends 2026-08-01: before it
	cur := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)    // the current partition
	future := time.Date(2026, 12, 1, 0, 0, 0, 0, time.UTC) //
	if tb.due(aug, now) {
		t.Error("a partition that ends after the cutoff is due")
	}
	if !tb.due(jul, now) {
		t.Error("a partition that ended before the cutoff is not due")
	}
	if tb.due(cur, now) || tb.due(future, now) {
		t.Error("the current or a future partition is due")
	}
	// exactly at the cutoff: upper <= now - retention is due
	exact := time.Date(2026, 9, 1, 0, 0, 0, 0, time.UTC).Add(90 * 24 * time.Hour)
	if !tb.due(aug, exact) {
		t.Error("a partition whose upper bound equals the cutoff is not due")
	}
	keep := goodTable()
	keep.Retention = KeepForever()
	if keep.due(jul, now) {
		t.Error("KeepForever: due")
	}
}

// The skew guard: whatever the retention says, a partition that ended less than one interval ago
// is never due. The tables here bypass the declaration check (a retention under one interval is
// refused at start-up) to prove the guard on its own.
func TestDueSkewGuard(t *testing.T) {
	t.Parallel()
	now := time.Date(2026, 11, 14, 10, 0, 0, 0, time.UTC)
	daily := Table{Name: "t", By: ByColumn("c"), Interval: Day, Ahead: 1, Retention: DropAfter(time.Hour)}
	yesterday := time.Date(2026, 11, 13, 0, 0, 0, 0, time.UTC) // ended 10h ago
	if daily.due(yesterday, now) {
		t.Error("a day partition that ended 10 hours ago is due with a retention of one hour")
	}
	twoDays := time.Date(2026, 11, 12, 0, 0, 0, 0, time.UTC) // ended 34h ago
	if !daily.due(twoDays, now) {
		t.Error("a day partition that ended 34 hours ago is not due")
	}
	negative := Table{Name: "t", By: ByColumn("c"), Interval: Month, Ahead: 1, Retention: DropAfter(-365 * 24 * time.Hour)}
	for _, s := range []time.Time{time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 12, 1, 0, 0, 0, 0, time.UTC), time.Date(2027, 3, 1, 0, 0, 0, 0, time.UTC)} {
		if negative.due(s, now) {
			t.Errorf("partition %v is due with a negative retention", s)
		}
	}
}

// RemovalDue is the first instant at which due is true: due is true at it and false one nanosecond
// before, for every retention shape (the retention longer than the interval, and the skew guard
// deciding when the retention is shorter than the interval).
func TestRemovalDueIsTheFirstInstantTheTableIsDue(t *testing.T) {
	t.Parallel()
	start := time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC)
	for _, c := range []struct {
		name  string
		table Table
		want  time.Time
	}{
		{"retention longer than the span", goodTable(), time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC).Add(90 * 24 * time.Hour)},
		{
			"archive",
			Table{Name: "t", By: ByColumn("c"), Interval: Month, Ahead: 1, Retention: ArchiveThenDropAfter(40*24*time.Hour, "s")},
			time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC).Add(40 * 24 * time.Hour),
		},
		{
			"the guard decides",
			Table{Name: "t", By: ByColumn("c"), Interval: Month, Ahead: 1, Retention: DropAfter(time.Hour)},
			time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC).Add(31 * 24 * time.Hour),
		},
		{
			"a year",
			Table{Name: "t", By: ByDateColumn("d"), Interval: Year, Ahead: 1, Retention: DropAfter(3 * 366 * 24 * time.Hour)},
			time.Date(2027, 1, 1, 0, 0, 0, 0, time.UTC).Add(3 * 366 * 24 * time.Hour),
		},
	} {
		st := start
		if c.table.Interval == Year {
			st = time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
		}
		got, ok := c.table.removalDue(st)
		if !ok || !got.Equal(c.want) {
			t.Errorf("%s: removalDue %v (ok %v), want %v", c.name, got, ok, c.want)
		}
		if !c.table.due(st, got) {
			t.Errorf("%s: not due at removalDue", c.name)
		}
		if c.table.due(st, got.Add(-time.Nanosecond)) {
			t.Errorf("%s: already due one nanosecond before removalDue", c.name)
		}
	}
	keep := goodTable()
	keep.Retention = KeepForever()
	if _, ok := keep.removalDue(start); ok {
		t.Error("KeepForever has a removal instant")
	}
}

func TestYearAndDateDeclarations(t *testing.T) {
	t.Parallel()
	yearly := Table{Name: "history", By: ByDateColumn("service_day"), Interval: Year, Ahead: 2, Retention: DropAfter(5 * 366 * 24 * time.Hour)}
	if got := yearly.check(); len(got) != 0 {
		t.Fatalf("a yearly date-keyed table has problems: %v", got)
	}
	yearly.Retention = DropAfter(300 * 24 * time.Hour) // shorter than the 366-day nominal year
	if got := strings.Join(yearly.check(), "; "); !strings.Contains(got, "shorter than one year interval") {
		t.Errorf("retention under a year: %q", got)
	}
	yearly.By = ByDateColumn("Service Day")
	if got := strings.Join(yearly.check(), "; "); !strings.Contains(got, "column") {
		t.Errorf("bad date column: %q", got)
	}
}
