package partitions

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

const boundsVector = "partitions/bounds.json"

func parseInterval(t *testing.T, s string) Interval {
	t.Helper()
	switch s {
	case "day":
		return Day
	case "week":
		return Week
	case "month":
		return Month
	case "year":
		return Year
	}
	t.Fatalf("unknown interval %q", s)
	return 0
}

func TestBoundsVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Bounds []struct {
			Interval  string `json:"interval"`
			By        string `json:"by"`
			Table     string `json:"table"`
			Prefix    string `json:"prefix"`
			Column    string `json:"column"`
			Instant   string `json:"instant"`
			Partition string `json:"partition"`
			Lower     string `json:"lower"`
			Upper     string `json:"upper"`
		} `json:"bounds"`
		IDBounds []struct {
			Prefix  string `json:"prefix"`
			Instant string `json:"instant"`
			Bound   string `json:"bound"`
		} `json:"idBounds"`
		LockKeys []struct {
			Schema string `json:"schema"`
			Table  string `json:"table"`
			Key    string `json:"key"`
		} `json:"lockKeys"`
	}
	conformance.ReadJSON(t, boundsVector, &doc)
	if len(doc.Bounds) == 0 || len(doc.IDBounds) == 0 || len(doc.LockKeys) == 0 {
		t.Fatalf("%s: a section is empty", boundsVector)
	}
	for _, c := range doc.Bounds {
		at, err := time.Parse(time.RFC3339Nano, c.Instant)
		if err != nil {
			t.Fatal(err)
		}
		iv := parseInterval(t, c.Interval)
		key := ByColumn(c.Column)
		switch c.By {
		case "id":
			key = ByID(c.Prefix)
		case "date":
			key = ByDateColumn(c.Column)
		}
		start := iv.Start(at)
		where := conformance.Where(boundsVector, c.Interval+" "+c.By+" "+c.Instant, "partition")
		if got := PartitionName(c.Table, start); got != c.Partition {
			t.Errorf("%s: name %q, want %q", where, got, c.Partition)
		}
		if got := key.Bound(start); got != c.Lower {
			t.Errorf("%s: lower %q, want %q", where, got, c.Lower)
		}
		if got := key.Bound(iv.Next(start)); got != c.Upper {
			t.Errorf("%s: upper %q, want %q", where, got, c.Upper)
		}
		if parsed, ok := parsePartitionName(c.Table, iv, c.Partition); !ok || !parsed.Equal(start) {
			t.Errorf("%s: name %q does not parse back to %v", where, c.Partition, start)
		}
	}
	for _, c := range doc.IDBounds {
		at, err := time.Parse(time.RFC3339Nano, c.Instant)
		if err != nil {
			t.Fatal(err)
		}
		if got := IDBound(c.Prefix, at); got != c.Bound {
			t.Errorf("%s: %q, want %q", conformance.Where(boundsVector, c.Instant, "bound"), got, c.Bound)
		}
	}
	for _, c := range doc.LockKeys {
		if got := strconv.FormatInt(LockKey(c.Schema, c.Table), 10); got != c.Key {
			t.Errorf("%s: key %s, want %s", conformance.Where(boundsVector, c.Schema+"."+c.Table, "key"), got, c.Key)
		}
	}
	t.Logf("%d bounds, %d id bounds, %d lock keys", len(doc.Bounds), len(doc.IDBounds), len(doc.LockKeys))
}

func TestISOWeekCrossesTheYear(t *testing.T) {
	t.Parallel()
	for _, in := range []string{"2026-12-31T10:00:00Z", "2027-01-03T23:59:59Z", "2026-12-28T00:00:00Z"} {
		at, _ := time.Parse(time.RFC3339, in)
		if got := Week.Start(at).Format("2006-01-02"); got != "2026-12-28" {
			t.Errorf("%s: week starts %s, want 2026-12-28", in, got)
		}
	}
	at, _ := time.Parse(time.RFC3339, "2027-01-04T00:00:00Z")
	if got := Week.Start(at).Format("2006-01-02"); got != "2027-01-04" {
		t.Errorf("week starts %s", got)
	}
}

func TestIntervalStartIsUTC(t *testing.T) {
	t.Parallel()
	// 2026-11-01 23:30 in UTC-5 is 2026-11-02 04:30 UTC: the UTC day decides.
	zone := time.FixedZone("x", -5*3600)
	at := time.Date(2026, 11, 1, 23, 30, 0, 0, zone)
	if got := Day.Start(at); !got.Equal(time.Date(2026, 11, 2, 0, 0, 0, 0, time.UTC)) {
		t.Errorf("day start %v", got)
	}
}

type fixedClock struct{ t time.Time }

func (c *fixedClock) Now() time.Time { return c.t }

// idInterval is the interval start whose id bounds contain the id, found by the id's own
// milliseconds, so the test checks the bounds against an independent computation.
func idInterval(t *testing.T, iv Interval, prefix, id string) time.Time {
	t.Helper()
	tail, ok := strings.CutPrefix(id, prefix+"_")
	if !ok {
		t.Fatalf("id %q lacks prefix %q", id, prefix)
	}
	v, ok := fw.DecodeTSID(tail)
	if !ok {
		t.Fatalf("id %q is not a TSID", id)
	}
	return iv.Start(time.UnixMilli(int64(v >> tsidInstantShift)))
}

// Every TSID the generator produces lands, in byte order, in [lower, upper) of the interval its own
// milliseconds belong to, and in no other interval's range (contract section 3.2).
func TestGeneratedIDsSortIntoTheirIntervalsBounds(t *testing.T) {
	t.Parallel()
	for _, iv := range []Interval{Day, Week, Month, Year} {
		start := iv.Start(time.Date(2026, 12, 15, 12, 0, 0, 0, time.UTC))
		end := iv.Next(start)
		key := ByID("rdg")
		for _, edge := range []time.Time{start.Add(-time.Millisecond), start, start.Add(time.Millisecond), end.Add(-time.Millisecond), end} {
			clock := &fixedClock{t: edge}
			ids := fw.NewIds(clock)
			n := 5000 // more than 4096 in one millisecond: the generator borrows the next one
			sawNext := false
			for range n {
				id := ids.Prefixed(fw.MustIDPrefix("rdg"))
				own := idInterval(t, iv, "rdg", id)
				if id < key.Bound(own) || id >= key.Bound(iv.Next(own)) {
					t.Fatalf("%s: id %s is outside the bounds of its own interval %v", iv, id, own)
				}
				for _, other := range []time.Time{iv.Next(own), iv.Start(own.Add(-time.Millisecond))} {
					if id >= key.Bound(other) && id < key.Bound(iv.Next(other)) {
						t.Fatalf("%s: id %s is inside the bounds of the neighbour %v too", iv, id, other)
					}
				}
				if own.Equal(end) && edge.Before(end) {
					sawNext = true
				}
			}
			if edge.Equal(end.Add(-time.Millisecond)) && !sawNext {
				t.Errorf("%s: 5000 ids in the last millisecond should borrow into the next interval", iv)
			}
		}
	}
}

func TestIDRangeSelectsAWindow(t *testing.T) {
	t.Parallel()
	from := time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)
	to := from.AddDate(0, 0, 7)
	lo, hi := IDRange("rdg", from, to)
	if lo != IDBound("rdg", from) || hi != IDBound("rdg", to) || lo >= hi {
		t.Fatalf("lo %s hi %s", lo, hi)
	}
	in := fw.NewIds(&fixedClock{t: from.Add(time.Hour)}).Prefixed(fw.MustIDPrefix("rdg"))
	before := fw.NewIds(&fixedClock{t: from.Add(-time.Millisecond)}).Prefixed(fw.MustIDPrefix("rdg"))
	after := fw.NewIds(&fixedClock{t: to}).Prefixed(fw.MustIDPrefix("rdg"))
	if in < lo || in >= hi {
		t.Errorf("an id inside the window is outside [%s, %s): %s", lo, hi, in)
	}
	for _, id := range []string{before, after} {
		if id >= lo && id < hi {
			t.Errorf("id %s outside the window is inside [%s, %s)", id, lo, hi)
		}
	}
}

func TestParsePartitionName(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		table, name string
		iv          Interval
		ok          bool
	}{
		{"readings", "readings_p20261101", Month, true},
		{"readings", "readings_p20261102", Month, false}, // not a month start
		{"readings", "readings_p20260101", Year, true},
		{"readings", "readings_p20260102", Year, false}, // not 1 January
		{"readings", "readings_p20261101", Year, false}, // a month start is not a year start
		{"readings", "readings_p20240101", Month, true}, // a year start is a month start
		{"readings", "readings_p20261228", Week, true},
		{"readings", "readings_p20261229", Week, false}, // a Tuesday
		{"readings", "readings_2026_11", Month, false},
		{"readings", "readings_p2026110", Month, false},
		{"readings", "readings_p20261301", Month, false},
		{"readings", "readings_old_p20261101", Month, false},
		{"readings", "other_p20261101", Month, false},
		{"readings", "readings_p20261101x", Month, false},
	} {
		if _, ok := parsePartitionName(c.table, c.iv, c.name); ok != c.ok {
			t.Errorf("%s as %s of %s: ok %v, want %v", c.name, c.iv, c.table, ok, c.ok)
		}
	}
}

func TestYearIntervalIsTheCalendarYearInUTC(t *testing.T) {
	t.Parallel()
	for _, c := range []struct{ at, start, next string }{
		{"2026-11-14T10:00:00Z", "2026-01-01", "2027-01-01"},
		{"2026-12-31T23:59:59.999Z", "2026-01-01", "2027-01-01"},
		{"2027-01-01T00:00:00Z", "2027-01-01", "2028-01-01"},
		{"2024-02-29T12:00:00Z", "2024-01-01", "2025-01-01"}, // a leap year
	} {
		at, _ := time.Parse(time.RFC3339Nano, c.at)
		start := Year.Start(at)
		if got := start.Format("2006-01-02"); got != c.start {
			t.Errorf("%s: year starts %s, want %s", c.at, got, c.start)
		}
		if got := Year.Next(start).Format("2006-01-02"); got != c.next {
			t.Errorf("%s: next year starts %s, want %s", c.at, got, c.next)
		}
	}
	// 2026-12-31 23:00 in UTC-5 is 2027-01-01 04:00 UTC: the UTC year decides
	at := time.Date(2026, 12, 31, 23, 0, 0, 0, time.FixedZone("x", -5*3600))
	if got := Year.Start(at); !got.Equal(time.Date(2027, 1, 1, 0, 0, 0, 0, time.UTC)) {
		t.Errorf("year start %v", got)
	}
	if got := Year.span(); got != 366*24*time.Hour {
		t.Errorf("year span %v", got)
	}
	if Year.Code() != "YEAR" || Year.String() != "year" || !Year.valid() {
		t.Errorf("year names: %s %s %v", Year.Code(), Year, Year.valid())
	}
}

func TestIDRangeAcrossAYearBoundary(t *testing.T) {
	t.Parallel()
	from := time.Date(2026, 12, 20, 0, 0, 0, 0, time.UTC)
	to := time.Date(2027, 1, 10, 0, 0, 0, 0, time.UTC)
	lo, hi := IDRange("rdg", from, to)
	yearStart := IDBound("rdg", time.Date(2027, 1, 1, 0, 0, 0, 0, time.UTC))
	if lo >= yearStart || yearStart >= hi {
		t.Fatalf("the window [%s, %s) does not straddle the year start %s", lo, hi, yearStart)
	}
	for _, c := range []struct {
		at time.Time
		in bool
	}{
		{time.Date(2026, 12, 19, 23, 59, 59, 999_000_000, time.UTC), false},
		{from, true},
		{time.Date(2026, 12, 31, 23, 59, 59, 999_000_000, time.UTC), true},
		{time.Date(2027, 1, 1, 0, 0, 0, 0, time.UTC), true},
		{to.Add(-time.Millisecond), true},
		{to, false},
	} {
		id := fw.NewIds(&fixedClock{t: c.at}).Prefixed(fw.MustIDPrefix("rdg"))
		if got := id >= lo && id < hi; got != c.in {
			t.Errorf("an id of %v: inside [%s, %s) is %v, want %v", c.at, lo, hi, got, c.in)
		}
	}
}

func TestDateBoundsAreDateLiterals(t *testing.T) {
	t.Parallel()
	key := ByDateColumn("service_day")
	at := time.Date(2026, 12, 31, 23, 59, 59, 0, time.UTC)
	for _, iv := range []Interval{Day, Week, Month, Year} {
		s := iv.Start(at)
		if got := key.Bound(s); got != s.Format("2006-01-02") || len(got) != 10 {
			t.Errorf("%s: lower %q", iv, got)
		}
	}
	// the date is the UTC date, not the local one
	zone := time.FixedZone("x", -5*3600)
	if got := key.Bound(time.Date(2026, 11, 1, 22, 0, 0, 0, zone)); got != "2026-11-02" {
		t.Errorf("bound of 22:00 UTC-5 is %q, want the UTC date 2026-11-02", got)
	}
	if key.String() != `ByDateColumn("service_day")` || key.IsID() {
		t.Errorf("key text %q", key)
	}
}
