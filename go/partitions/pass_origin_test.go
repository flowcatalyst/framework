package partitions_test

import (
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/partitions"
)

func TestTheLastPassSaysWhatTriggeredItAndWho(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	cfg := config{every: time.Hour, tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}}
	a := r.mustStart(cfg)
	b := r.mustStart(cfg) // another replica reads the record
	ctx := t.Context()
	origin := func() (partitions.PassTrigger, string) {
		t.Helper()
		lp := tableStatus(t, b, "readings").LastPass
		if lp == nil {
			t.Fatal("no last pass")
		}
		return lp.Trigger, lp.Actor
	}

	if err := a.ps.Pass(ctx); err != nil { // the loop's entry point
		t.Fatal(err)
	}
	if trig, actor := origin(); trig != partitions.TriggerLoop || actor != "" {
		t.Errorf("the loop's pass: trigger %q, actor %q", trig, actor)
	}
	if _, err := a.ps.RunPass(ctx, operator(), "readings"); err != nil {
		t.Fatal(err)
	}
	if trig, actor := origin(); trig != partitions.TriggerManual || actor != "prn_OP1" {
		t.Errorf("a manual pass: trigger %q, actor %q", trig, actor)
	}
	if _, err := a.ps.RunPass(ctx, fw.ContextFor(fw.MustClientID("ops")), ""); err != nil { // no principal
		t.Fatal(err)
	}
	if trig, actor := origin(); trig != partitions.TriggerManual || actor != "" {
		t.Errorf("a manual pass without a principal: trigger %q, actor %q", trig, actor)
	}
	if err := a.ps.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	if trig, actor := origin(); trig != partitions.TriggerLoop || actor != "" {
		t.Errorf("the next loop pass overwrites the record: trigger %q, actor %q", trig, actor)
	}
	// a refused manual pass records nothing
	if _, err := a.ps.RunPass(ctx, operator(), "nope"); err == nil {
		t.Fatal("an unknown table was run")
	}
	if trig, _ := origin(); trig != partitions.TriggerLoop {
		t.Errorf("a refused pass changed the record: %q", trig)
	}
}

func TestARowRecordedBeforeTheMigrationReadsWithoutTriggerAndActor(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{every: time.Hour, tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	r.exec(`INSERT INTO fw_partition_passes (table_name, ended_at, ensure_status, retention_status, archive_status, created, dropped, archived, pass_trigger, actor)
VALUES ('public.readings', $1, 'OK', 'OK', 'SKIPPED', 1, 0, 0, NULL, NULL)
ON CONFLICT (table_name) DO UPDATE SET pass_trigger = NULL, actor = NULL`, t0)
	lp := tableStatus(t, s, "readings").LastPass
	if lp == nil || lp.Trigger != "" || lp.Actor != "" || lp.Ensure != partitions.StepOK {
		t.Errorf("a row without the origin: %+v", lp)
	}
}

func TestATableMadeByHandInTheShapeOfV0002StillRecordsAndReadsAndWarnsOnce(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	other := fwtest.NewDatabase(t)
	r.otherURL = other.URL
	odb, err := database.Open(t.Context(), "other", other.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(odb.Close)
	for _, ddl := range []string{colTable, "ALTER TABLE fw_partition_passes DROP COLUMN pass_trigger, DROP COLUMN actor"} {
		if _, err := odb.Pool().Exec(t.Context(), ddl); err != nil {
			t.Fatal(err)
		}
	}
	s := r.mustStart(config{every: time.Hour, tables: []partitions.Table{
		{Name: "samples", Database: "other", By: partitions.ByColumn("recorded_at"), Interval: partitions.Month, Ahead: 1, Retention: partitions.DropAfter(days(60))},
	}})
	if n := strings.Count(r.logs.String(), "has no pass_trigger and actor columns"); n != 1 || !strings.Contains(r.logs.String(), "V0003__add_partition_pass_trigger.sql") {
		t.Errorf("the start-up warning appeared %d times:\n%s", n, r.logs.String())
	}
	if _, err := s.ps.RunPass(t.Context(), operator(), ""); err != nil {
		t.Fatal(err)
	}
	st := tableStatus(t, s, "samples")
	if st.LastPass == nil || st.LastPass.Ensure != partitions.StepOK || st.LastPass.Trigger != "" || st.LastPass.Actor != "" {
		t.Errorf("last pass on the hand-made table: %+v", st.LastPass)
	}
	if v := metric(t, s.b, "fw_partition_failures_total", "public.samples", "record"); v != 0 {
		t.Errorf("record failures %v", v)
	}
}

func TestRowsAreNotKnownUntilAPartitionHasAnEstimate(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	r.exec(trailTable)
	s := r.mustStart(config{every: time.Hour, tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()),
		monthly("trail", partitions.ByID("trl"), partitions.KeepForever()),
	}})
	st := tableStatus(t, s, "readings")
	n := len(st.Partitions)
	if n < 2 || st.Rows != nil || st.PartitionsWithoutEstimate != n {
		t.Fatalf("before ANALYZE: rows %v, without estimate %d of %d", st.Rows, st.PartitionsWithoutEstimate, n)
	}
	// analyse only the current partition, which holds three rows
	r.exec("INSERT INTO readings (id) VALUES ($1), ($2), ($3)", idAt(t0), idAt(t0.Add(time.Hour)), idAt(t0.Add(2*time.Hour)))
	r.exec("ANALYZE readings_p20261101")
	st = tableStatus(t, s, "readings")
	if st.Rows == nil || *st.Rows != 3 || st.PartitionsWithoutEstimate != n-1 {
		t.Errorf("after analysing one partition: rows %v, without estimate %d (want 3, %d)", st.Rows, st.PartitionsWithoutEstimate, n-1)
	}
	// a table with no partitions is empty, not unknown
	for _, name := range r.parts("trail") {
		r.exec("ALTER TABLE trail DETACH PARTITION " + name)
	}
	empty := tableStatus(t, s, "trail")
	if len(empty.Partitions) != 0 || empty.Rows == nil || *empty.Rows != 0 || empty.PartitionsWithoutEstimate != 0 {
		t.Errorf("no partitions: rows %v, without estimate %d", empty.Rows, empty.PartitionsWithoutEstimate)
	}
}

func TestTheStatusKeyIsStructuredForEachKind(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	r.exec(colTable)
	r.exec(`CREATE TABLE runs (id text NOT NULL, service_day date NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)`)
	r.exec(`CREATE TABLE evts (event_id text COLLATE "C" PRIMARY KEY) PARTITION BY RANGE (event_id)`)
	s := r.mustStart(config{every: time.Hour, tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.KeepForever()),
		monthly("samples", partitions.ByColumn("recorded_at"), partitions.KeepForever()),
		monthly("runs", partitions.ByDateColumn("service_day"), partitions.KeepForever()),
		monthly("evts", partitions.ByIDColumn("event_id", "evt"), partitions.KeepForever()),
	}})
	for _, c := range []struct {
		table  string
		kind   partitions.KeyKind
		column string
		prefix string
		typ    partitions.KeyColumnType
	}{
		{"readings", partitions.KeyKindID, "id", "rdg", ""},
		{"samples", partitions.KeyKindColumn, "recorded_at", "", partitions.KeyTimestamptz},
		{"runs", partitions.KeyKindColumn, "service_day", "", partitions.KeyDate},
		{"evts", partitions.KeyKindID, "event_id", "evt", ""},
	} {
		k := tableStatus(t, s, c.table).Key
		if k.Kind() != c.kind || k.Column() != c.column || k.Prefix() != c.prefix || k.ColumnType() != c.typ {
			t.Errorf("%s: kind %q column %q prefix %q type %q", c.table, k.Kind(), k.Column(), k.Prefix(), k.ColumnType())
		}
	}
}
