package partitions_test

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/url"
	"slices"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/locks"
	"github.com/flowcatalyst/framework/go/objectstore"
	"github.com/flowcatalyst/framework/go/partitions"
)

const trailTable = `CREATE TABLE trail (id text COLLATE "C" PRIMARY KEY, note text) PARTITION BY RANGE (id)`

// operator is the Context of a platform operator, who the manual actions are recorded against.
func operator() fw.Context {
	return fw.ContextFor(fw.MustClientID("ops")).WithPrincipalOfKind(fw.MustPrincipalID("prn_OP1"), fw.PrincipalUser).WithTier(fw.TierAnchor)
}

func partitionNamed(t *testing.T, st partitions.TableStatus, name string) partitions.PartitionStatus {
	t.Helper()
	for _, p := range st.Partitions {
		if p.Name == name {
			return p
		}
	}
	t.Fatalf("no partition %s in %v", name, partitionNames(st))
	return partitions.PartitionStatus{}
}

func partitionNames(st partitions.TableStatus) []string {
	out := []string{}
	for _, p := range st.Partitions {
		out = append(out, p.Name)
	}
	return out
}

func tableStatus(t *testing.T, s *started, table string) partitions.TableStatus {
	t.Helper()
	st, ok, err := s.ps.TableStatus(t.Context(), table)
	if err != nil || !ok {
		t.Fatalf("TableStatus(%s): ok %v, err %v", table, ok, err)
	}
	return st
}

func TestStatusReadsDeclarationSizesEstimatesPositionsAndRemovalDue(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	r.exec(colTable)
	r.exec(trailTable)
	s := r.mustStart(config{store: objectstore.Memory(), tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90))),
		{Name: "samples", By: partitions.ByColumn("recorded_at"), Interval: partitions.Week, Ahead: 1, Retention: partitions.ArchiveThenDropAfter(days(30), "archive")},
		{Name: "trail", By: partitions.ByID("trl"), Interval: partitions.Month, Ahead: 2, Retention: partitions.KeepForever()},
	}})
	ctx := t.Context()
	all, err := s.ps.Status(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for _, st := range all {
		names = append(names, st.Name)
	}
	if !slices.Equal(names, []string{"public.readings", "public.samples", "public.trail"}) {
		t.Fatalf("declaration order: %v", names)
	}

	readings := all[0]
	if readings.Database != "" || readings.Key != `ByID("rdg") on id` || readings.Interval != partitions.Month || readings.Interval.Code() != "MONTH" || readings.Ahead != 3 {
		t.Errorf("declaration: %+v", readings)
	}
	if readings.Retention.Kind != partitions.RetentionDrop || readings.Retention.After != days(90) || readings.Retention.Store != "" {
		t.Errorf("retention: %+v", readings.Retention)
	}
	if !readings.Healthy || readings.LastPass != nil || len(readings.Detached) != 0 || len(readings.Strays) != 0 {
		t.Errorf("a fresh table: healthy %v, last pass %v, detached %v, strays %v", readings.Healthy, readings.LastPass, readings.Detached, readings.Strays)
	}
	if got := partitionNames(readings); !slices.Equal(got, []string{"readings_p20261101", "readings_p20261201", "readings_p20270101", "readings_p20270201"}) {
		t.Errorf("partitions %v", got)
	}
	var sum int64
	for _, p := range readings.Partitions {
		if p.Bytes <= 0 {
			t.Errorf("%s has no size", p.Name)
		}
		if p.Rows != nil {
			t.Errorf("%s: an estimate %d before the table was analysed", p.Name, *p.Rows)
		}
		sum += p.Bytes
	}
	if readings.Bytes != sum || readings.Rows != 0 {
		t.Errorf("sums: bytes %d (want %d), rows %d", readings.Bytes, sum, readings.Rows)
	}

	// positions against the module's clock, and the removal instants
	res, err := s.ps.Ensure(ctx, "readings", time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC), time.Date(2026, 7, 31, 0, 0, 0, 0, time.UTC))
	if err != nil || res.Created != 3 || res.AlreadyDue != 3 {
		t.Fatalf("Ensure: %+v, %v; May to July are three partitions past the retention", res, err)
	}
	readings = tableStatus(t, s, "readings")
	wantPos := map[string]partitions.Position{
		"readings_p20260501": partitions.PositionPast, "readings_p20260601": partitions.PositionPast, "readings_p20260701": partitions.PositionPast,
		"readings_p20261101": partitions.PositionCurrent, "readings_p20261201": partitions.PositionFuture, "readings_p20270201": partitions.PositionFuture,
	}
	for name, pos := range wantPos {
		if got := partitionNamed(t, readings, name).Position; got != pos {
			t.Errorf("%s: position %s, want %s", name, got, pos)
		}
	}
	if got := partitionNames(readings); !slices.IsSorted(got) || got[0] != "readings_p20260501" {
		t.Errorf("not oldest first: %v", got)
	}
	may := partitionNamed(t, readings, "readings_p20260501")
	if !may.From.Equal(time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC)) || !may.To.Equal(time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)) || may.From.Location() != time.UTC {
		t.Errorf("range %v to %v", may.From, may.To)
	}
	if may.RemovalDue == nil || !may.RemovalDue.Equal(time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC).Add(days(90))) {
		t.Errorf("DropAfter(90d): removal due %v", may.RemovalDue)
	}
	samples := tableStatus(t, s, "public.samples") // schema-qualified works too
	wk := partitionNamed(t, samples, "samples_p20261109")
	if wk.Position != partitions.PositionCurrent || wk.RemovalDue == nil || !wk.RemovalDue.Equal(time.Date(2026, 11, 16, 0, 0, 0, 0, time.UTC).Add(days(30))) {
		t.Errorf("ArchiveThenDropAfter(30d) week: %+v", wk)
	}
	if samples.Retention.Kind != partitions.RetentionArchiveThenDrop || samples.Retention.Store != "archive" || samples.Retention.After != days(30) {
		t.Errorf("archive retention %+v", samples.Retention)
	}
	trail := tableStatus(t, s, "trail")
	if trail.Retention.Kind != partitions.RetentionKeep || trail.Retention.After != 0 {
		t.Errorf("keep retention %+v", trail.Retention)
	}
	for _, p := range trail.Partitions {
		if p.RemovalDue != nil {
			t.Errorf("KeepForever partition %s has a removal instant %v", p.Name, p.RemovalDue)
		}
	}

	// estimates are absent until the table is analysed, present after
	r.exec("INSERT INTO readings (id) VALUES ($1), ($2), ($3)", idAt(t0), idAt(t0.Add(time.Hour)), idAt(t0.Add(2*time.Hour)))
	r.exec("ANALYZE readings")
	readings = tableStatus(t, s, "readings")
	for _, p := range readings.Partitions {
		if p.Rows == nil {
			t.Errorf("%s: no estimate after ANALYZE", p.Name)
		}
	}
	if nov := partitionNamed(t, readings, "readings_p20261101"); nov.Rows == nil || *nov.Rows != 3 || readings.Rows != 3 {
		t.Errorf("november estimate %v, total %d", nov.Rows, readings.Rows)
	}

	// the clock moves: November is past, December current
	r.clock.Set(time.Date(2026, 12, 10, 0, 0, 0, 0, time.UTC))
	readings = tableStatus(t, s, "readings")
	if partitionNamed(t, readings, "readings_p20261101").Position != partitions.PositionPast || partitionNamed(t, readings, "readings_p20261201").Position != partitions.PositionCurrent {
		t.Errorf("positions did not follow the clock: %+v", readings.Partitions)
	}

	// Healthy is the health check's decision: the next partition is attached or not
	r.clock.Set(t0)
	r.exec("DROP TABLE readings_p20261201")
	readings = tableStatus(t, s, "readings")
	rep := s.run.Health(ctx)
	res2, _ := rep.Result("partitions:readings")
	if readings.Healthy || res2.Status == fw.Up {
		t.Errorf("healthy %v, check %v: want both down with the next partition gone", readings.Healthy, res2.Status)
	}

	if _, ok, err := s.ps.TableStatus(ctx, "nope"); ok || err != nil {
		t.Errorf("unknown table: ok %v err %v", ok, err)
	}
}

func TestStatusListsStraysDetachedAndArchivedPartitionsAndPagesTheArchives(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(colTable)
	r.exec(`CREATE TABLE samples_legacy PARTITION OF samples FOR VALUES FROM ('2000-01-01 00:00:00+00') TO ('2001-01-01 00:00:00+00')`)
	// a pass that died between the detach and the drop: a plain table with the module's mark
	r.exec(`CREATE TABLE samples_p20260301 (id text NOT NULL, recorded_at timestamptz NOT NULL, payload text)`)
	r.exec(`COMMENT ON TABLE samples_p20260301 IS 'fw-partitions: detached from public.samples for archiving'`)
	// a table of a partition's name without the mark is not the module's
	r.exec(`CREATE TABLE samples_p20260401 (id text)`)
	s := r.mustStart(config{store: objectstore.Memory(), tables: []partitions.Table{
		{Name: "samples", By: partitions.ByColumn("recorded_at"), Interval: partitions.Month, Ahead: 1, Retention: partitions.ArchiveThenDropAfter(days(60), "archive")},
	}})
	ctx := t.Context()
	st := tableStatus(t, s, "samples")
	if !slices.Equal(st.Strays, []string{"public.samples_legacy"}) {
		t.Errorf("strays %v", st.Strays)
	}
	if !slices.Equal(st.Detached, []string{"public.samples_p20260301"}) {
		t.Errorf("detached %v (the unmarked table is not the module's)", st.Detached)
	}
	for _, p := range st.Partitions {
		if p.Name == "samples_legacy" {
			t.Error("a stray is listed as a partition")
		}
	}

	if _, err := s.ps.Ensure(ctx, "samples", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	for i := range 7 {
		r.exec("INSERT INTO samples (id, recorded_at, payload) VALUES ($1, $2, 'p')", fmt.Sprintf("s%d", i), time.Date(2026, 5, 3+i, 1, 0, 0, 0, time.UTC))
	}
	if err := s.ps.Pass(ctx); err != nil {
		t.Fatal(err)
	}
	st = tableStatus(t, s, "samples")
	if len(st.Detached) != 0 {
		t.Errorf("the pass finished the detached table: %v", st.Detached)
	}
	archives, err := s.ps.Archives(ctx, "samples", "", 50)
	if err != nil {
		t.Fatal(err)
	}
	if len(archives) != 2 || archives[0].Partition != "samples_p20260501" || archives[1].Partition != "samples_p20260301" {
		t.Fatalf("archives, newest first: %+v", archives)
	}
	may := archives[0]
	if may.Table != "public.samples" || may.Rows != 7 || may.Objects != 1 || may.Bytes <= 0 || may.ArchivedAt.IsZero() ||
		may.Key != partitions.ArchiveKey("public", "samples", "samples_p20260501") ||
		!may.From.Equal(time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC)) || !may.To.Equal(time.Date(2026, 6, 1, 0, 0, 0, 0, time.UTC)) {
		t.Errorf("record %+v", may)
	}
	// paging: after = the partition name of the last record of the previous page
	page1, err := s.ps.Archives(ctx, "samples", "", 1)
	if err != nil || len(page1) != 1 || page1[0].Partition != "samples_p20260501" {
		t.Fatalf("page 1: %+v %v", page1, err)
	}
	page2, err := s.ps.Archives(ctx, "public.samples", page1[0].Partition, 1)
	if err != nil || len(page2) != 1 || page2[0].Partition != "samples_p20260301" {
		t.Fatalf("page 2: %+v %v", page2, err)
	}
	if page3, err := s.ps.Archives(ctx, "samples", page2[0].Partition, 1); err != nil || len(page3) != 0 {
		t.Errorf("page 3: %+v %v", page3, err)
	}
	if _, err := s.ps.Archives(ctx, "nope", "", 1); !errors.Is(err, partitions.ErrTableNotDeclared) {
		t.Errorf("unknown table: %v", err)
	}
	if got, _ := s.ps.Archives(ctx, "samples", "", 0); len(got) != 1 {
		t.Errorf("a limit below 1 is 1: %d records", len(got))
	}

	// the partition comes back by hand (a backfill): the manifest says it was archived
	if _, err := s.ps.Ensure(ctx, "samples", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	st = tableStatus(t, s, "samples")
	back := partitionNamed(t, st, "samples_p20260501")
	if back.Archived == nil || back.Archived.Rows != 7 || back.Archived.Objects != 1 || back.Archived.Bytes != may.Bytes || back.Archived.ArchivedAt.IsZero() {
		t.Errorf("archived: %+v", back.Archived)
	}
	if partitionNamed(t, st, "samples_p20261101").Archived != nil {
		t.Error("a partition that was never archived says it was")
	}
}

func TestLastPassIsSharedAcrossReplicas(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	cfg := config{
		every:  20 * time.Millisecond,
		tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))},
	}
	quiet := cfg
	quiet.every = time.Hour // this replica never runs the loop in the test
	a := r.mustStart(quiet)
	b := r.mustStart(quiet)
	if st := tableStatus(t, b, "readings"); st.LastPass != nil {
		t.Fatalf("a pass was recorded before any ran: %+v", st.LastPass)
	}
	r.clock.Set(time.Date(2027, 3, 15, 12, 0, 0, 0, time.UTC)) // November is due, new partitions are needed
	loop := r.mustStart(cfg)
	_ = loop
	eventually(t, "the loop's pass to be recorded", func() bool { return tableStatus(t, a, "readings").LastPass != nil })
	for _, replica := range []*started{a, b} {
		st := tableStatus(t, replica, "readings")
		lp := st.LastPass
		if lp == nil || !lp.EndedAt.Equal(r.clock.Now()) || lp.EndedAt.Location() != time.UTC {
			t.Fatalf("last pass %+v", lp)
		}
		if lp.Ensure != partitions.StepOK || lp.Retention != partitions.StepOK || lp.Archive != partitions.StepSkipped {
			t.Errorf("statuses ensure %s retention %s archive %s; archive does not apply to DropAfter", lp.Ensure, lp.Retention, lp.Archive)
		}
	}
}

func TestPassStepStatusesAreRecordedAndKeepForeverSkipsRetention(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	r.exec(trailTable)
	store := &flaky{}
	s := r.mustStart(config{store: flakyFactory(store), tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive")),
		monthly("trail", partitions.ByID("trl"), partitions.KeepForever()),
	}})
	ctx := t.Context()
	if _, err := s.ps.Ensure(ctx, "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	insertOld(t, r, 5)
	store.set(fmt.Errorf("%w: disk on fire", objectstore.ErrStore), false, nil)

	results, err := s.ps.RunPass(ctx, operator(), "")
	if err != nil || len(results) != 2 {
		t.Fatalf("RunPass: %v, %v", results, err)
	}
	if results[0].Table != "public.readings" || results[0].Passed() || !slices.Equal(results[0].Failed, []partitions.Step{partitions.StepArchive}) {
		t.Errorf("the failing archive is in the result, not an error: %+v", results[0])
	}
	if results[0].Archived != 0 || results[0].Dropped != 0 || results[1].Table != "public.trail" || !results[1].Passed() {
		t.Errorf("results %+v", results)
	}
	lp := tableStatus(t, s, "readings").LastPass
	if lp == nil || lp.Ensure != partitions.StepOK || lp.Retention != partitions.StepOK || lp.Archive != partitions.StepFailed {
		t.Errorf("last pass %+v", lp)
	}
	if got := tableStatus(t, s, "readings").Detached; !slices.Equal(got, []string{"public.readings_p20260501"}) {
		t.Errorf("the failed archive left the table detached: %v", got)
	}
	keep := tableStatus(t, s, "trail").LastPass
	if keep == nil || keep.Retention != partitions.StepSkipped || keep.Archive != partitions.StepSkipped || keep.Ensure != partitions.StepOK {
		t.Errorf("KeepForever last pass %+v", keep)
	}

	// the store recovers: the next manual pass archives, drops, and says so
	store.set(nil, false, nil)
	results, err = s.ps.RunPass(ctx, operator(), "readings")
	if err != nil || len(results) != 1 || results[0].Archived != 1 || results[0].Dropped != 1 || !results[0].Passed() {
		t.Fatalf("recovery: %+v %v", results, err)
	}
	lp = tableStatus(t, s, "readings").LastPass
	if lp.Archive != partitions.StepOK || lp.Archived != 1 || lp.Dropped != 1 {
		t.Errorf("last pass after recovery %+v", lp)
	}
}

func TestManualActionsAreRecordedWithThePrincipal(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	ctx := t.Context()
	if _, err := s.ps.RunPass(ctx, operator(), "public.readings"); err != nil { // schema-qualified
		t.Fatal(err)
	}
	if _, err := s.ps.EnsureAs(ctx, operator(), "readings", time.Date(2026, 5, 10, 0, 0, 0, 0, time.UTC), time.Date(2026, 6, 10, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	if _, err := s.ps.EnsureAs(ctx, operator(), "readings", t0, t0.AddDate(0, 0, -1)); !errors.Is(err, partitions.ErrRangeInvalid) {
		t.Fatal(err)
	}
	if _, err := s.ps.RunPass(ctx, operator(), "nope"); !errors.Is(err, partitions.ErrTableNotDeclared) {
		t.Fatal(err)
	}
	logs := r.logs.String()
	for _, want := range []string{
		"partitions: manual pass", "partitions: manual ensure", "principal=prn_OP1", "principal_kind=USER", "tier=ANCHOR", "table=public.readings",
		"outcome=ok", "from=2026-05-10", "to=2026-06-10", "created=2", "outcome=\"refused: partitions: range is invalid: to is before from\"",
		"outcome=\"refused: partitions: table is not declared:",
	} {
		if !strings.Contains(logs, want) {
			t.Errorf("the log lacks %q:\n%s", want, logs)
		}
	}
}

// blocking is a store whose put waits, so a pass can be held in the middle of an archive.
type blocking struct {
	objectstore.Store
	entered chan struct{}
	release chan struct{}
	done    atomic.Bool
}

func (b *blocking) PutObject(ctx context.Context, key string, o objectstore.Object) error {
	if !b.done.Swap(true) {
		close(b.entered)
	}
	select {
	case <-b.release:
	case <-ctx.Done():
		return ctx.Err()
	}
	return b.Store.PutObject(ctx, key, o)
}

func blockingFactory(out *blocking) objectstore.StoreFactory {
	return func(ctx context.Context, b *fw.Boot, name string) (objectstore.Store, error) {
		inner, err := objectstore.Memory()(ctx, b, name)
		if err != nil {
			return nil, err
		}
		out.Store = inner
		return out, nil
	}
}

func TestARunningPassRefusesAManualPassUntilItEnds(t *testing.T) {
	t.Parallel()
	for _, withLocks := range []bool{false, true} {
		t.Run(fmt.Sprintf("withLocks=%v", withLocks), func(t *testing.T) {
			t.Parallel()
			r := newRig(t)
			r.exec(idTable)
			store := &blocking{entered: make(chan struct{}), release: make(chan struct{})}
			cfg := config{
				every: 20 * time.Millisecond, store: blockingFactory(store), lock: withLocks,
				tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.ArchiveThenDropAfter(days(90), "archive"))},
			}
			// the old partition exists and holds a row, so the loop's first pass archives and blocks in the put
			quiet := cfg
			quiet.every = time.Hour
			setup := r.mustStart(quiet)
			if _, err := setup.ps.Ensure(t.Context(), "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 5, 31, 0, 0, 0, 0, time.UTC)); err != nil {
				t.Fatal(err)
			}
			insertOld(t, r, 3)
			s := r.mustStart(cfg)
			select {
			case <-store.entered:
			case <-time.After(20 * time.Second):
				t.Fatal("the loop's pass never reached the archive")
			}
			// a bounded context: a manual pass that does not refuse would wait in the blocked put
			short, cancel := context.WithTimeout(t.Context(), 3*time.Second)
			defer cancel()
			if _, err := s.ps.RunPass(short, operator(), ""); !errors.Is(err, partitions.ErrPassRunning) {
				t.Fatalf("a manual pass during the loop's pass: %v, want ErrPassRunning", err)
			}
			if _, err := s.ps.RunPass(short, operator(), "readings"); !errors.Is(err, partitions.ErrPassRunning) {
				t.Fatalf("a manual pass of one table: %v", err)
			}
			close(store.release)
			eventually(t, "the loop's pass to end and a manual pass to be accepted", func() bool {
				res, err := s.ps.RunPass(t.Context(), operator(), "readings")
				return err == nil && len(res) == 1
			})
		})
	}
}

func TestAnotherReplicasPassRefusesAManualPass(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	shared := locks.NewMemoryStore(r.clock)
	cfg := config{
		every: time.Hour, lockStore: shared,
		tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))},
	}
	a := r.mustStart(cfg)
	// another replica holds the loop's service lock (it is running a pass)
	holder := locks.New(shared, locks.WithClock(r.clock))
	key, err := locks.ServiceKey(partitions.LockName)
	if err != nil {
		t.Fatal(err)
	}
	acq, err := holder.Acquire(t.Context(), key, time.Hour, 10*time.Second)
	if err != nil {
		t.Fatal(err)
	}
	held, ok := acq.(locks.Acquired)
	if !ok {
		t.Fatalf("could not take the lock: %T", acq)
	}
	r.exec("DROP TABLE readings_p20261201") // work a pass would do
	if _, err := a.ps.RunPass(t.Context(), operator(), ""); !errors.Is(err, partitions.ErrPassRunning) {
		t.Fatalf("a manual pass while another replica holds the lock: %v", err)
	}
	if slices.Contains(r.parts("readings"), "readings_p20261201") {
		t.Fatal("the refused pass did DDL")
	}
	if _, err := holder.Release(t.Context(), held.Lease); err != nil {
		t.Fatal(err)
	}
	res, err := a.ps.RunPass(t.Context(), operator(), "")
	if err != nil || len(res) != 1 || res[0].Created != 1 {
		t.Fatalf("after the lock is free: %+v %v", res, err)
	}
	if !slices.Contains(r.parts("readings"), "readings_p20261201") {
		t.Error("the manual pass did not recreate the partition")
	}
	// the lock is free again afterwards: the pass released it
	acq, err = holder.Acquire(t.Context(), key, time.Hour, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := acq.(locks.Acquired); !ok {
		t.Error("the manual pass kept the lock")
	}
}

func TestEnsureTypedRefusalsAndAlreadyDue(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	ctx := t.Context()
	for _, c := range []struct {
		name     string
		table    string
		from, to time.Time
		want     error
	}{
		{"unknown table", "nope", t0, t0, partitions.ErrTableNotDeclared},
		{"to before from", "readings", t0, t0.AddDate(0, 0, -1), partitions.ErrRangeInvalid},
		{"before 1970", "readings", time.Date(1969, 1, 1, 0, 0, 0, 0, time.UTC), t0, partitions.ErrRangeInvalid},
		{"too large", "readings", time.Date(1990, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC), partitions.ErrRangeTooLarge},
	} {
		if _, err := s.ps.Ensure(ctx, c.table, c.from, c.to); !errors.Is(err, c.want) {
			t.Errorf("%s: %v, want %v", c.name, err, c.want)
		}
	}
	// a detached table of a partition's name
	r.exec("CREATE TABLE readings_p20260801 (id text)")
	if _, err := s.ps.Ensure(ctx, "readings", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 8, 20, 0, 0, 0, 0, time.UTC)); !errors.Is(err, partitions.ErrDetachedLeftover) {
		t.Errorf("a detached leftover: %v", err)
	}
	r.exec("DROP TABLE readings_p20260801")
	// the cutoff is 2026-08-16: May to July are due, August (ends September 1) and later are not
	res, err := s.ps.Ensure(ctx, "readings", time.Date(2026, 5, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 12, 31, 0, 0, 0, 0, time.UTC))
	if err != nil || res.AlreadyDue != 3 || res.Created != 6 {
		t.Errorf("Ensure: %+v, %v; want 3 due (May to July), 6 created (May to October, the rest exist)", res, err)
	}
	res, err = s.ps.Ensure(ctx, "readings", time.Date(2026, 8, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 8, 20, 0, 0, 0, 0, time.UTC))
	if err != nil || res.AlreadyDue != 0 || res.Created != 0 {
		t.Errorf("an existing range that is not due: %+v, %v", res, err)
	}
}

func TestADateKeyedYearlyTableInsertsDropsAndReads(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(`CREATE TABLE runs (id text NOT NULL, service_day date NOT NULL, note text, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)`)
	s := r.mustStart(config{tables: []partitions.Table{
		{Name: "runs", By: partitions.ByDateColumn("service_day"), Interval: partitions.Year, Ahead: 1, Retention: partitions.DropAfter(2 * 366 * 24 * time.Hour)},
	}})
	if got := r.parts("runs"); !slices.Equal(got, []string{"runs_p20260101", "runs_p20270101"}) {
		t.Fatalf("partitions %v", got)
	}
	r.exec("INSERT INTO runs (id, service_day) VALUES ('a', '2026-12-31'), ('b', '2027-01-01')")
	if got := r.strings("SELECT id || '@' || tableoid::regclass::text FROM runs ORDER BY id"); !slices.Equal(got, []string{"a@runs_p20260101", "b@runs_p20270101"}) {
		t.Errorf("rows landed in %v", got)
	}
	st := tableStatus(t, s, "runs")
	if st.Key != `ByDateColumn("service_day")` || st.Interval != partitions.Year || st.Interval.Code() != "YEAR" || !st.Healthy {
		t.Errorf("status %+v", st)
	}
	p := partitionNamed(t, st, "runs_p20260101")
	if !p.From.Equal(time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)) || !p.To.Equal(time.Date(2027, 1, 1, 0, 0, 0, 0, time.UTC)) || p.Position != partitions.PositionCurrent {
		t.Errorf("2026: %+v", p)
	}
	if p.RemovalDue == nil || !p.RemovalDue.Equal(time.Date(2027, 1, 1, 0, 0, 0, 0, time.UTC).Add(2*366*24*time.Hour)) {
		t.Errorf("removal due %v", p.RemovalDue)
	}
	// 2026 ended 2027-01-01; with a retention of 732 days it goes at 2029-01-02 (365 + 366 + 1 days later)
	r.clock.Set(time.Date(2029, 1, 1, 0, 0, 0, 0, time.UTC))
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := r.parts("runs"); !slices.Contains(got, "runs_p20260101") || !slices.Contains(got, "runs_p20290101") {
		t.Fatalf("2026 must still be kept at 2029-01-01 (due 2029-01-02), partitions %v", got)
	}
	r.clock.Set(time.Date(2029, 1, 4, 0, 0, 0, 0, time.UTC))
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	if got := r.parts("runs"); slices.Contains(got, "runs_p20260101") {
		t.Errorf("2026 was not dropped after its removal instant: %v", got)
	}
	if got := r.strings("SELECT id FROM runs"); !slices.Equal(got, []string{"b"}) {
		t.Errorf("rows left %v: 2026's row is gone with its partition and 2027's stays (due 2030-01-02)", got)
	}
}

func TestADateColumnNeedsByDateColumnAndATimestamptzColumnNeedsByColumn(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name  string
		ddl   string
		table partitions.Table
		want  string
	}{
		{
			"ByColumn on a date", `CREATE TABLE runs (id text, service_day date NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)`,
			partitions.Table{Name: "runs", By: partitions.ByColumn("service_day"), Interval: partitions.Month, Ahead: 1, Retention: partitions.KeepForever()},
			"partitions.ByDateColumn",
		},
		{
			"ByDateColumn on a timestamptz", `CREATE TABLE runs (id text, service_day timestamptz NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)`,
			partitions.Table{Name: "runs", By: partitions.ByDateColumn("service_day"), Interval: partitions.Month, Ahead: 1, Retention: partitions.KeepForever()},
			"partitions.ByColumn",
		},
		{
			"ByDateColumn on a timestamp", `CREATE TABLE runs (id text, service_day timestamp NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)`,
			partitions.Table{Name: "runs", By: partitions.ByDateColumn("service_day"), Interval: partitions.Month, Ahead: 1, Retention: partitions.KeepForever()},
			"must be date",
		},
		{
			"ByColumn on a timestamp", `CREATE TABLE runs (id text, service_day timestamp NOT NULL, PRIMARY KEY (id, service_day)) PARTITION BY RANGE (service_day)`,
			partitions.Table{Name: "runs", By: partitions.ByColumn("service_day"), Interval: partitions.Month, Ahead: 1, Retention: partitions.KeepForever()},
			"must be timestamptz",
		},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			r := newRig(t)
			r.exec(c.ddl)
			_, err := r.start(config{tables: []partitions.Table{c.table}})
			if err == nil || !strings.Contains(err.Error(), c.want) {
				t.Fatalf("err %v, want a problem mentioning %q", err, c.want)
			}
		})
	}
}

func TestATableOnASecondDatabaseInstanceHasItsOwnStatusAndLastPass(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	other := fwtest.NewDatabase(t)
	r.otherURL = other.URL
	odb, err := database.Open(t.Context(), "other", other.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(odb.Close)
	for _, ddl := range []string{colTable} {
		if _, err := odb.Pool().Exec(t.Context(), ddl); err != nil {
			t.Fatal(err)
		}
	}
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{
		monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90))),
		{Name: "samples", Database: "other", By: partitions.ByColumn("recorded_at"), Interval: partitions.Month, Ahead: 1, Retention: partitions.DropAfter(days(60))},
	}})
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	st := tableStatus(t, s, "samples")
	if st.Database != "other" || len(st.Partitions) != 2 || !st.Healthy {
		t.Errorf("status of the table on the other instance: %+v", st)
	}
	if st.LastPass == nil || st.LastPass.Ensure != partitions.StepOK {
		t.Errorf("last pass %+v", st.LastPass)
	}
	var inOther, inMain int
	if err := odb.Pool().QueryRow(t.Context(), "SELECT count(*) FROM fw_partition_passes WHERE table_name = 'public.samples'").Scan(&inOther); err != nil {
		t.Fatal(err)
	}
	inMain = r.count("SELECT count(*) FROM fw_partition_passes WHERE table_name = 'public.samples'")
	if inOther != 1 || inMain != 0 {
		t.Errorf("the record lives in the table's own database: other %d, main %d", inOther, inMain)
	}
	if got := tableStatus(t, s, "readings"); got.Database != "" || got.LastPass == nil {
		t.Errorf("the default table: %+v", got)
	}
}

func TestAnInstanceWithoutThePassTableSaysSoAtStartUpAndRecordsNothing(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	other := fwtest.NewDatabase(t)
	r.otherURL = other.URL
	odb, err := database.Open(t.Context(), "other", other.URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(odb.Close)
	for _, ddl := range []string{colTable, "DROP TABLE fw_partition_passes"} {
		if _, err := odb.Pool().Exec(t.Context(), ddl); err != nil {
			t.Fatal(err)
		}
	}
	s := r.mustStart(config{tables: []partitions.Table{
		{Name: "samples", Database: "other", By: partitions.ByColumn("recorded_at"), Interval: partitions.Month, Ahead: 1, Retention: partitions.DropAfter(days(60))},
	}})
	if !strings.Contains(r.logs.String(), "fw_partition_passes does not exist in database instance 'other'") {
		t.Errorf("start-up did not say so:\n%s", r.logs.String())
	}
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatal(err)
	}
	st := tableStatus(t, s, "samples")
	if st.LastPass != nil {
		t.Errorf("a last pass without a table: %+v", st.LastPass)
	}
	if v := metric(t, s.b, "fw_partition_failures_total", "public.samples", "record"); v != 0 {
		t.Errorf("record failures counted for an instance that has no table: %v", v)
	}
	// the default instance with no declared table on it is not warned about
	r2 := newRig(t)
	r2.exec(colTable)
	r2.mustStart(config{tables: []partitions.Table{{Name: "samples", By: partitions.ByColumn("recorded_at"), Interval: partitions.Month, Ahead: 1, Retention: partitions.KeepForever()}}})
	if strings.Contains(r2.logs.String(), "fw_partition_passes does not exist") {
		t.Error("a warning for an instance that has the table")
	}
}

func TestAFailedRecordIsLoggedAndCountedAndNeverFailsThePass(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	// the table exists but cannot be written
	r.exec("ALTER TABLE fw_partition_passes ADD CONSTRAINT never CHECK (created < 0)")
	if err := s.ps.Pass(t.Context()); err != nil {
		t.Fatalf("a failed record failed the pass: %v", err)
	}
	if v := metric(t, s.b, "fw_partition_failures_total", "public.readings", "record"); v != 1 {
		t.Errorf("record failures %v, want 1", v)
	}
	if !strings.Contains(r.logs.String(), "could not record the last partition pass") {
		t.Errorf("not logged:\n%s", r.logs.String())
	}
}

// ---- the number of queries ----

// countingProxy forwards PostgreSQL connections and counts the statements that run (Bind and
// simple Query messages), so a test can show a read's query count.
type countingProxy struct {
	ln    net.Listener
	execs atomic.Int64
}

func startProxy(t *testing.T, target string) *countingProxy {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &countingProxy{ln: ln}
	t.Cleanup(func() { _ = ln.Close() })
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.serve(c, target)
		}
	}()
	return p
}

func (p *countingProxy) serve(client net.Conn, target string) {
	defer client.Close()
	server, err := net.Dial("tcp", target)
	if err != nil {
		return
	}
	defer server.Close()
	go func() { _, _ = io.Copy(client, server) }()
	br := bufio.NewReader(client)
	// the start-up message has no type byte: a length that includes itself
	var head [4]byte
	if _, err := io.ReadFull(br, head[:]); err != nil {
		return
	}
	n := int(head[0])<<24 | int(head[1])<<16 | int(head[2])<<8 | int(head[3])
	rest := make([]byte, n-4)
	if _, err := io.ReadFull(br, rest); err != nil {
		return
	}
	_, _ = server.Write(append(head[:], rest...))
	for {
		var h [5]byte
		if _, err := io.ReadFull(br, h[:]); err != nil {
			return
		}
		length := int(h[1])<<24 | int(h[2])<<16 | int(h[3])<<8 | int(h[4])
		body := make([]byte, length-4)
		if _, err := io.ReadFull(br, body); err != nil {
			return
		}
		if h[0] == 'B' || h[0] == 'Q' {
			p.execs.Add(1)
		}
		if _, err := server.Write(append(h[:], body...)); err != nil {
			return
		}
	}
}

func TestStatusMakesAFixedNumberOfQueriesWhateverThePartitionCount(t *testing.T) {
	t.Parallel()
	r := newRig(t)
	r.exec(idTable)
	u, err := url.Parse(r.url)
	if err != nil {
		t.Fatal(err)
	}
	proxy := startProxy(t, u.Host)
	u.Host = proxy.ln.Addr().String()
	r.url = u.String()
	s := r.mustStart(config{tables: []partitions.Table{monthly("readings", partitions.ByID("rdg"), partitions.DropAfter(days(90)))}})
	ctx := t.Context()

	queries := func() int64 {
		before := proxy.execs.Load()
		st, err := s.ps.Status(ctx)
		if err != nil || len(st) != 1 {
			t.Fatal(st, err)
		}
		return proxy.execs.Load() - before
	}
	queries() // warm: the statements are prepared on the connection
	few := queries()
	partitionsFew := len(tableStatus(t, s, "readings").Partitions)
	if _, err := s.ps.Ensure(ctx, "readings", time.Date(2024, 1, 1, 0, 0, 0, 0, time.UTC), time.Date(2026, 6, 30, 0, 0, 0, 0, time.UTC)); err != nil {
		t.Fatal(err)
	}
	queries()
	many := queries()
	partitionsMany := len(tableStatus(t, s, "readings").Partitions)
	if partitionsMany < partitionsFew+20 {
		t.Fatalf("the test did not add partitions: %d to %d", partitionsFew, partitionsMany)
	}
	if few != many || few < 1 || few > 5 {
		t.Errorf("Status ran %d queries with %d partitions and %d with %d; want the same fixed number, at most 5 for one table", few, partitionsFew, many, partitionsMany)
	}
	t.Logf("%d queries for %d partitions, %d for %d", few, partitionsFew, many, partitionsMany)
}
