package flowcatalyst_test

import (
	"context"
	"errors"
	"fmt"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

// dbRig starts a rig on a database of its own, with the outbox and the module's migrations applied.
func dbRig(t *testing.T, o rigOptions) *rig {
	t.Helper()
	db := fwtest.NewDatabase(t)
	o.dbURL = db.URL
	o.migrate = true
	prev := o.declare
	o.declare = func(app *fw.App) {
		usecase.Declare(app)
		if prev != nil {
			prev(app)
		}
	}
	return startRig(t, o)
}

func (r *rig) db() *database.DB {
	r.t.Helper()
	db, ok := r.dbs.Default()
	if !ok {
		r.t.Fatal("no database")
	}
	return db
}

func insertOutbox(t *testing.T, db *database.DB, id string, status int, createdAt time.Time) {
	t.Helper()
	_, err := db.Pool().Exec(t.Context(),
		"INSERT INTO outbox_messages (id, type, payload, status, created_at) VALUES ($1, 'EVENT', '{}', $2, $3)", id, status, createdAt)
	if err != nil {
		t.Fatal(err)
	}
}

func outboxCheck(t *testing.T, r *rig) fw.HealthResult {
	t.Helper()
	res, ok := r.health().Result("outbox")
	if !ok {
		t.Fatal("there is no outbox health check")
	}
	return res
}

func TestAnEmptyOutboxIsUpAndInformational(t *testing.T) {
	r := dbRig(t, rigOptions{})
	res := outboxCheck(t, r)
	if res.Status != fw.Up || res.Criticality != fw.Informational {
		t.Fatalf("%+v", res)
	}
}

func TestAPendingRowYoungerThanOrExactlyAtTheLimitIsUp(t *testing.T) {
	r := dbRig(t, rigOptions{})
	insertOutbox(t, r.db(), "o1", 0, testNow.Add(-4*time.Minute))
	if res := outboxCheck(t, r); res.Status != fw.Up {
		t.Fatalf("younger: %+v", res)
	}
	insertOutbox(t, r.db(), "o2", 0, testNow.Add(-5*time.Minute)) // exactly stale-after
	if res := outboxCheck(t, r); res.Status != fw.Up {
		t.Fatalf("exactly at the limit: %+v", res)
	}
}

func TestAPendingRowOlderThanTheLimitIsDownAndSaysWhy(t *testing.T) {
	r := dbRig(t, rigOptions{})
	insertOutbox(t, r.db(), "o1", 0, testNow.Add(-5*time.Minute-time.Second))
	res := outboxCheck(t, r)
	if res.Status != fw.Down || !strings.Contains(res.Reason, "waited 301s (stale after 300s)") || !strings.Contains(res.Reason, "outbox processor") {
		t.Fatalf("%+v", res)
	}
	// the application's clock decides, not the database's
	r.clock.Advance(-time.Hour)
	if res := outboxCheck(t, r); res.Status != fw.Up {
		t.Fatalf("a row from the future of the application's clock: %+v", res)
	}
}

func TestAStaleBacklogIsInformationalByDefaultAndCriticalWhenConfigured(t *testing.T) {
	r := dbRig(t, rigOptions{})
	insertOutbox(t, r.db(), "o1", 0, testNow.Add(-time.Hour))
	if rep := r.health(); !rep.IsReady() {
		t.Fatal("a stale backlog made the application not ready")
	}
	r2 := dbRig(t, rigOptions{env: map[string]string{"APP_HEALTH_CRITICAL": "outbox"}})
	insertOutbox(t, r2.db(), "o1", 0, testNow.Add(-time.Hour))
	if rep := r2.health(); rep.IsReady() {
		t.Fatal("app.health.critical did not make the outbox check decide readiness")
	}
}

func TestAnInProgressRowCountsAsUndeliveredAndTerminalFailuresDoNot(t *testing.T) {
	r := dbRig(t, rigOptions{})
	insertOutbox(t, r.db(), "stuck", 9, testNow.Add(-time.Hour))
	if res := outboxCheck(t, r); res.Status != fw.Down {
		t.Fatalf("in progress: %+v", res)
	}
	r2 := dbRig(t, rigOptions{})
	for i, status := range []int{1, 2, 3, 4, 5, 6} {
		insertOutbox(t, r2.db(), fmt.Sprintf("t%d", i), status, testNow.Add(-24*time.Hour))
	}
	if res := outboxCheck(t, r2); res.Status != fw.Up {
		t.Fatalf("terminal statuses: %+v", res)
	}
}

func TestTheOutboxLimitIsASettingAndTheCheckOnlyReads(t *testing.T) {
	r := dbRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_OUTBOX_STALE_AFTER": "2h"}})
	insertOutbox(t, r.db(), "o1", 0, testNow.Add(-time.Hour))
	if res := outboxCheck(t, r); res.Status != fw.Up {
		t.Fatalf("%+v", res)
	}
	var before, after int
	var updated time.Time
	_ = r.db().Pool().QueryRow(t.Context(), "SELECT status, retry_count, updated_at FROM outbox_messages WHERE id='o1'").Scan(&before, &after, &updated)
	outboxCheck(t, r)
	var s2, r2 int
	var u2 time.Time
	_ = r.db().Pool().QueryRow(t.Context(), "SELECT status, retry_count, updated_at FROM outbox_messages WHERE id='o1'").Scan(&s2, &r2, &u2)
	if s2 != before || r2 != after || !u2.Equal(updated) {
		t.Fatal("the check wrote to the table")
	}
}

func TestWithoutTheTableOrADatabaseThereIsNoOutboxCheck(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL}) // no migrations: no outbox_messages
	if _, ok := r.health().Result("outbox"); ok {
		t.Fatal("a check without the table")
	}
	if !r.logs.has("has no outbox_messages table, so no 'outbox' health check") {
		t.Fatalf("logs:\n%s", r.logs.all())
	}
	r2 := startRig(t, rigOptions{})
	if _, ok := r2.health().Result("outbox"); ok {
		t.Fatal("a check without a database")
	}
	r3 := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_ENABLED": "false"}})
	if _, ok := r3.health().Result("outbox"); ok {
		t.Fatal("a check while disabled")
	}
}

func TestTheDatabaseGoingAwayIsDownNotUp(t *testing.T) {
	r := dbRig(t, rigOptions{})
	r.db().Pool().Close()
	res := outboxCheck(t, r)
	if res.Status == fw.Up {
		t.Fatalf("%+v", res)
	}
}

// --- Once -----------------------------------------------------------------------------------

func deliveryMeta(job string) flowcatalyst.DeliveryMeta {
	return flowcatalyst.DeliveryMeta{JobID: job, EventType: evt}
}

func createWorkTable(t *testing.T, db *database.DB) {
	t.Helper()
	if _, err := db.Pool().Exec(t.Context(), "CREATE TABLE IF NOT EXISTS work (id serial PRIMARY KEY, who text NOT NULL)"); err != nil {
		t.Fatal(err)
	}
}

func work(who string) func(ctx context.Context, tx *database.Tx) (int, error) {
	return func(ctx context.Context, tx *database.Tx) (int, error) {
		var id int
		err := tx.Conn().QueryRow(ctx, "INSERT INTO work (who) VALUES ($1) RETURNING id", who).Scan(&id)
		return id, err
	}
}

func count(t *testing.T, db *database.DB, table string) int {
	t.Helper()
	var n int
	if err := db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM "+table).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestTheModuleContributesTheTable(t *testing.T) {
	r := dbRig(t, rigOptions{})
	ok, err := r.db().TableExists(t.Context(), "fw_dispatch_once")
	if err != nil || !ok {
		t.Fatalf("fw_dispatch_once: %v %v", ok, err)
	}
}

func TestTheWorkRunsOnceAndTheRepeatIsSkipped(t *testing.T) {
	r := dbRig(t, rigOptions{})
	db := r.db()
	createWorkTable(t, db)
	first, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "mark-paid", work("first"))
	if err != nil {
		t.Fatal(err)
	}
	if v, ran := first.Value(); !ran || !first.Ran() || v != 1 {
		t.Fatalf("first %+v", first)
	}
	again, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "mark-paid", work("again"))
	if err != nil {
		t.Fatal(err)
	}
	if _, ran := again.Value(); ran || again.Ran() {
		t.Fatalf("the repeat ran: %+v", again)
	}
	if count(t, db, "work") != 1 || count(t, db, "fw_dispatch_once") != 1 {
		t.Fatal("the work or the record is not once")
	}
}

func TestEachHandlerNameAndEachJobIsSeparate(t *testing.T) {
	r := dbRig(t, rigOptions{})
	db := r.db()
	createWorkTable(t, db)
	for _, c := range []struct{ job, handler string }{{"dj_1", "a"}, {"dj_1", "b"}, {"dj_2", "a"}} {
		res, err := flowcatalyst.Once(t.Context(), deliveryMeta(c.job), db, c.handler, work(c.job+c.handler))
		if err != nil || !res.Ran() {
			t.Fatalf("%v: %+v %v", c, res, err)
		}
	}
	if count(t, db, "work") != 3 {
		t.Fatal("work")
	}
}

func TestAFailureRollsBackTheWorkAndTheRecordSoTheRetryRuns(t *testing.T) {
	r := dbRig(t, rigOptions{})
	db := r.db()
	createWorkTable(t, db)
	boom := errors.New("boom")
	_, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "h", func(ctx context.Context, tx *database.Tx) (int, error) {
		if _, err := work("lost")(ctx, tx); err != nil {
			return 0, err
		}
		return 0, boom
	})
	if !errors.Is(err, boom) {
		t.Fatalf("err = %v", err)
	}
	if count(t, db, "work") != 0 || count(t, db, "fw_dispatch_once") != 0 {
		t.Fatal("the failed run left something behind")
	}
	res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "h", work("retry"))
	if err != nil || !res.Ran() {
		t.Fatalf("the retry: %+v %v", res, err)
	}
	// a panic rolls back too
	func() {
		defer func() { _ = recover() }()
		_, _ = flowcatalyst.Once(t.Context(), deliveryMeta("dj_2"), db, "h", func(ctx context.Context, tx *database.Tx) (int, error) {
			_, _ = work("lost")(ctx, tx)
			panic("boom")
		})
	}()
	if count(t, db, "fw_dispatch_once") != 1 {
		t.Fatal("a panic left its record")
	}
}

func TestAConcurrentRepeatWaitsForTheFirstAndThenSkips(t *testing.T) {
	r := dbRig(t, rigOptions{})
	db := r.db()
	createWorkTable(t, db)
	inside := make(chan struct{})
	release := make(chan struct{})
	var wg sync.WaitGroup
	var ranA, ranB atomic.Bool
	wg.Go(func() {
		res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "h", func(ctx context.Context, tx *database.Tx) (int, error) {
			close(inside)
			<-release
			return work("a")(ctx, tx)
		})
		if err != nil {
			t.Error(err)
		}
		ranA.Store(res.Ran())
	})
	<-inside
	wg.Go(func() {
		res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "h", work("b"))
		if err != nil {
			t.Error(err)
		}
		ranB.Store(res.Ran())
	})
	time.Sleep(200 * time.Millisecond) // the second is waiting on the first's uncommitted record
	if ranB.Load() {
		t.Fatal("the second ran while the first was in flight")
	}
	close(release)
	wg.Wait()
	if !ranA.Load() || ranB.Load() || count(t, db, "work") != 1 {
		t.Fatalf("a=%v b=%v work=%d", ranA.Load(), ranB.Load(), count(t, db, "work"))
	}
}

func TestAConcurrentRepeatRunsWhenTheFirstFails(t *testing.T) {
	r := dbRig(t, rigOptions{})
	db := r.db()
	createWorkTable(t, db)
	inside := make(chan struct{})
	release := make(chan struct{})
	var wg sync.WaitGroup
	wg.Go(func() {
		_, _ = flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "h", func(context.Context, *database.Tx) (int, error) {
			close(inside)
			<-release
			return 0, errors.New("first fails")
		})
	})
	<-inside
	var ran atomic.Bool
	wg.Go(func() {
		res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), db, "h", work("b"))
		if err != nil {
			t.Error(err)
		}
		ran.Store(res.Ran())
	})
	time.Sleep(200 * time.Millisecond)
	close(release)
	wg.Wait()
	if !ran.Load() || count(t, db, "work") != 1 {
		t.Fatalf("ran=%v work=%d", ran.Load(), count(t, db, "work"))
	}
}

func TestAMalformedHandlerNameOrAnEmptyJobIsRefused(t *testing.T) {
	r := dbRig(t, rigOptions{})
	for _, bad := range []string{"", "has space", "a/b", strings.Repeat("a", 201)} {
		if _, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db(), bad, work("x")); !errors.Is(err, flowcatalyst.ErrInvalidHandlerName) {
			t.Errorf("handler %q: %v", bad, err)
		}
	}
	if _, err := flowcatalyst.Once(t.Context(), flowcatalyst.DeliveryMeta{}, r.db(), "h", work("x")); err == nil {
		t.Error("an empty job id accepted")
	}
	if _, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db(), strings.Repeat("a", 200), func(context.Context, *database.Tx) (int, error) { return 0, nil }); err != nil {
		t.Errorf("200 characters: %v", err)
	}
}

func TestEndToEndARedeliveredJobIsAckedButHandledOnce(t *testing.T) {
	r := dbRig(t, rigOptions{})
	db := r.db()
	createWorkTable(t, db)
	// a second application on the same database registers the handler
	handled := &recorderOf[bool]{}
	r2 := startRig(t, rigOptions{dbURL: "", register: func(fc *flowcatalyst.Platform) error {
		return flowcatalyst.OnDispatch(fc.Receivers(), evt, func(ctx context.Context, _ fw.Context, d flowcatalyst.Delivery[invoice]) (flowcatalyst.DispatchResult, error) {
			res, err := flowcatalyst.Once(ctx, d.Meta, db, "mark-paid", work("paid"))
			if err != nil {
				return nil, err
			}
			handled.add(res.Ran())
			return flowcatalyst.Ack{}, nil
		})
	}})
	for range 2 {
		if got := r2.dispatch(envelope, nil); got.status != 200 || got.body != `{"ack":true}` {
			t.Fatalf("%d %s", got.status, got.body)
		}
	}
	if got := handled.get(); len(got) != 2 || !got[0] || got[1] || count(t, db, "work") != 1 {
		t.Fatalf("handled %v, work %d", got, count(t, db, "work"))
	}
}

// --- the purge ------------------------------------------------------------------------------

func insertOnce(t *testing.T, db *database.DB, job string, processedAt time.Time) {
	t.Helper()
	if _, err := db.Pool().Exec(t.Context(), "INSERT INTO fw_dispatch_once (handler, job_id, processed_at) VALUES ('h', $1, $2)", job, processedAt); err != nil {
		t.Fatal(err)
	}
}

func jobsLeft(t *testing.T, db *database.DB) string {
	t.Helper()
	rows, err := db.Pool().Query(t.Context(), "SELECT job_id FROM fw_dispatch_once ORDER BY job_id")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		out = append(out, s)
	}
	return strings.Join(out, ",")
}

func TestTheDefaultRetentionIs30DaysAndTheStartUpRunDeletesOlderRowsOnly(t *testing.T) {
	db := fwtest.NewDatabase(t)
	// the purge runs at start-up, after the migrations: seed through a first start
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	insertOnce(t, r.db(), "old", testNow.Add(-30*24*time.Hour-time.Hour))
	insertOnce(t, r.db(), "edge", testNow.Add(-30*24*time.Hour+time.Minute))
	insertOnce(t, r.db(), "fresh", testNow.Add(-time.Hour))
	_ = r.run.Shutdown()
	r2 := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	waitFor(t, "the start-up purge", func() bool { return jobsLeft(t, r2.db()) == "edge,fresh" })
}

func TestTheRetentionSettingDecidesTheCutoffAndABatchLoopDrains(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	for i := range 5 {
		insertOnce(t, r.db(), fmt.Sprintf("j%d", i), testNow.Add(-time.Duration(i)*24*time.Hour))
	}
	_ = r.run.Shutdown()
	r2 := startRig(t, rigOptions{
		dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) },
		env: map[string]string{"FLOWCATALYST_RECEIVERS_ONCE_RETENTION": "47h"},
	})
	waitFor(t, "the purge by the setting", func() bool { return jobsLeft(t, r2.db()) == "j0,j1" })
}

func TestThePurgeDeletesInBatchesUntilNothingOldIsLeft(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	if _, err := r.db().Pool().Exec(t.Context(),
		"INSERT INTO fw_dispatch_once (handler, job_id, processed_at) SELECT 'h', 'old-' || g, $1 FROM generate_series(1, 12000) g", testNow.Add(-90*24*time.Hour)); err != nil {
		t.Fatal(err)
	}
	insertOnce(t, r.db(), "fresh", testNow)
	_ = r.run.Shutdown()
	r2 := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	waitFor(t, "12 000 rows to go in batches of 5 000", func() bool { return count(t, r2.db(), "fw_dispatch_once") == 1 })
}

func TestARunWhileAnotherReplicaHoldsTheLockDeletesNothing(t *testing.T) {
	db := fwtest.NewDatabase(t)
	clk := fwtest.NewClock(testNow)
	store, factory := sharedStore(clk)
	// another replica holds the purge lock from the start, so no run of this test's rigs purges the
	// row while it is being inserted
	holder := newLockHolder(t, store, clk)
	holder()
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, clock: clk, lockFactory: factory, declare: func(app *fw.App) { usecase.Declare(app) }})
	insertOnce(t, r.db(), "old", testNow.Add(-90*24*time.Hour))
	_ = r.run.Shutdown()
	r2 := startRig(t, rigOptions{dbURL: db.URL, migrate: true, clock: clk, lockFactory: factory, declare: func(app *fw.App) { usecase.Declare(app) }})
	time.Sleep(400 * time.Millisecond)
	if got := jobsLeft(t, r2.db()); got != "old" {
		t.Fatalf("rows %q: a replica that did not hold the lock purged", got)
	}
}

func TestWithoutALockStoreTheRunStillDeletes(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	insertOnce(t, r.db(), "old", testNow.Add(-90*24*time.Hour))
	_ = r.run.Shutdown()
	r2 := startRig(t, rigOptions{dbURL: db.URL, migrate: true, declare: func(app *fw.App) { usecase.Declare(app) }})
	waitFor(t, "the purge", func() bool { return jobsLeft(t, r2.db()) == "" })
}

func TestThePurgeRepeatsAndUsesTheApplicationsClockNotTheDatabases(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, env: map[string]string{"FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL": "50ms"}, declare: func(app *fw.App) { usecase.Declare(app) }})
	// a row from 31 days before the application's (fake, 2026) time: old by that clock, though
	// the database's own clock is years later
	insertOnce(t, r.db(), "old-by-app-clock", testNow.Add(-31*24*time.Hour))
	insertOnce(t, r.db(), "new-by-app-clock", testNow.Add(-29*24*time.Hour))
	waitFor(t, "a repeated run", func() bool { return jobsLeft(t, r.db()) == "new-by-app-clock" })
	insertOnce(t, r.db(), "another-old", testNow.Add(-40*24*time.Hour))
	waitFor(t, "the next run", func() bool { return jobsLeft(t, r.db()) == "new-by-app-clock" })
}

func TestThePurgeStopsAtShutdown(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL, migrate: true, env: map[string]string{"FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL": "20ms"}, declare: func(app *fw.App) { usecase.Declare(app) }})
	_ = r.run.Shutdown()
	// the pool is closed by shutdown, so use a new connection
	d2, err := database.Open(t.Context(), "check", db.URL)
	if err != nil {
		t.Fatal(err)
	}
	defer d2.Close()
	insertOnce(t, d2, "old", testNow.Add(-90*24*time.Hour))
	time.Sleep(200 * time.Millisecond)
	if got := jobsLeft(t, d2); got != "old" {
		t.Fatalf("rows %q: the purge ran after shutdown", got)
	}
}

func TestWithoutTheTableThereIsNoPurgeTask(t *testing.T) {
	db := fwtest.NewDatabase(t)
	r := startRig(t, rigOptions{dbURL: db.URL}) // no migrations
	if !r.logs.has("so no purge task") {
		t.Fatalf("logs:\n%s", r.logs.all())
	}
}
