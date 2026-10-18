package usecase_test

import (
	"bytes"
	"context"
	"errors"
	"log/slog"
	"math"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

const thingsSQL = `CREATE TABLE things (
    id        text PRIMARY KEY,
    client_id text NOT NULL,
    version   bigint NOT NULL,
    name      text NOT NULL CONSTRAINT things_name_key UNIQUE,
    parent    text REFERENCES things (id) DEFERRABLE INITIALLY DEFERRED
)`

func TestMain(m *testing.M) {
	fwtest.Main(m, fwtest.Template(func(ctx context.Context, url string) error {
		db, err := database.Open(ctx, "main", url)
		if err != nil {
			return err
		}
		defer db.Close()
		if err := database.ApplyMigrations(ctx, db, []fw.MigrationSet{{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()}},
			slog.New(slog.DiscardHandler)); err != nil {
			return err
		}
		_, err = db.Pool().Exec(ctx, thingsSQL)
		return err
	}))
}

// ---- the aggregate, its event and repository ------------------------------------------------

type thing struct {
	id, name, parent string
}

func (t thing) AuditEntity() usecase.Entity { return usecase.Entity{Type: "Thing", ID: t.id} }

type thingEvent struct {
	Meta event.Metadata `json:"-"`
	ID   string         `json:"id"`
	Name string         `json:"name"`
	Bad  float64        `json:"bad,omitzero"` // NaN makes the event's data fail to marshal
}

func (e thingEvent) Metadata() event.Metadata { return e.Meta }

var thingCreated = event.MustType("test:things:thing:created")

type thingRepo struct {
	updateRows *int64 // when set, Update reports this row count without writing
	insertErr  error
}

func (r thingRepo) Find(ctx context.Context, db *database.DB, client fw.ClientID, id string) (usecase.Loaded[thing], bool, error) {
	var t thing
	var c string
	var v int64
	err := db.Pool().QueryRow(ctx, "SELECT id, client_id, version, name FROM things WHERE id = $1 AND client_id = $2", id, client.String()).
		Scan(&t.id, &c, &v, &t.name)
	if errors.Is(err, pgx.ErrNoRows) {
		return usecase.Loaded[thing]{}, false, nil
	}
	if err != nil {
		return usecase.Loaded[thing]{}, false, err
	}
	return usecase.LoadedFrom(fw.MustClientID(c), v, t), true, nil
}

func (r thingRepo) Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, t thing) error {
	if r.insertErr != nil {
		return r.insertErr
	}
	var parent any
	if t.parent != "" {
		parent = t.parent
	}
	_, err := tx.Conn().Exec(ctx, "INSERT INTO things (id, client_id, version, name, parent) VALUES ($1, $2, 1, $3, $4)",
		t.id, client.String(), t.name, parent)
	return err
}

func (r thingRepo) Update(ctx context.Context, tx *database.Tx, client fw.ClientID, t thing, v usecase.VersionCheck) (int64, error) {
	if r.updateRows != nil {
		return *r.updateRows, nil
	}
	tag, err := tx.Conn().Exec(ctx, "UPDATE things SET name = $1, version = $2 WHERE id = $3 AND client_id = $4 AND version = $5",
		t.name, v.Next, t.id, client.String(), v.Expected)
	return tag.RowsAffected(), err
}

func (r thingRepo) Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, t thing, v usecase.VersionCheck) (int64, error) {
	tag, err := tx.Conn().Exec(ctx, "DELETE FROM things WHERE id = $1 AND client_id = $2 AND version = $3", t.id, client.String(), v.Expected)
	return tag.RowsAffected(), err
}

// ---- a use case whose phases the test sets -----------------------------------------------------

type cmd struct {
	Name string
	data bool
}

func (c cmd) AuditData() []usecase.AuditField {
	if !c.data {
		return nil
	}
	return []usecase.AuditField{usecase.Field("name", c.Name), usecase.Field("n", 2)}
}

type testCase struct {
	validate  func(cmd) error
	authorize func(fw.Context) (usecase.Grant, error)
	execute   func(ctx context.Context, cx fw.Context, c cmd, db *database.DB) (*usecase.Plan[thingEvent], error)
	attempts  *atomic.Int32
}

func (testCase) Name() usecase.Name { return usecase.MustName("test-thing") }

func (u testCase) Validate(_ context.Context, _ fw.Context, c cmd) error {
	if u.validate != nil {
		return u.validate(c)
	}
	return nil
}

func (u testCase) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[cmd]) (usecase.Grant, error) {
	if u.authorize != nil {
		return u.authorize(cx)
	}
	return usecase.Public(), nil
}

func (u testCase) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[cmd], db *database.DB) (*usecase.Plan[thingEvent], error) {
	if u.attempts != nil {
		u.attempts.Add(1)
	}
	return u.execute(ctx, cx, a.Command(), db)
}

// ---- the application around the runner --------------------------------------------------------

type env struct {
	runner  *usecase.Runner
	db      *database.DB
	events  *event.Factory
	cx      fw.Context
	boot    *fw.Boot
	clock   *fwtest.Clock
	logs    *bytes.Buffer
	running *fw.Running
}

func newEnv(t *testing.T, opts ...usecase.RunnerOption) *env {
	t.Helper()
	db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(db.Close)
	clock := fwtest.NewClock(time.Date(2026, 10, 8, 10, 15, 30, 123_000_000, time.UTC))
	logs := &bytes.Buffer{}
	app := fw.New("usecase-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test"}), fw.WithConfigDir(t.TempDir()),
		fw.WithClock(clock), fw.WithLogger(slog.New(slog.NewTextHandler(logs, nil))), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	outbox := usecase.Declare(app)
	e := &env{db: db, clock: clock, logs: logs}
	r, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		runner, err := usecase.NewRunner(b, outbox, db, opts...)
		e.runner, e.boot, e.events = runner, b, event.NewFactory(b.Clock(), b.Ids())
		return nil, err
	})
	if err != nil {
		t.Fatal(err)
	}
	e.running = r
	t.Cleanup(func() { _ = r.Shutdown() })
	e.cx = fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("user-1"))
	return e
}

func (e *env) define(t *testing.T, uc testCase, opts ...usecase.DefineOption) *usecase.Defined[cmd, thingEvent] {
	t.Helper()
	d, err := usecase.Define(e.runner, uc, opts...)
	if err != nil {
		t.Fatal(err)
	}
	return d
}

func (e *env) created(cx fw.Context, t thing) thingEvent {
	return thingEvent{Meta: e.events.Metadata(cx, thingCreated, t.id), ID: t.id, Name: t.name}
}

// insertPlan is the plan of a use case that creates a thing named after the command.
func (e *env) insertPlan(repo thingRepo) func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
	return func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t := thing{id: e.boot.Ids().Prefixed(fw.MustIDPrefix("thg")), name: c.Name}
		return usecase.NewPlan(e.created(cx, t), usecase.Insert(usecase.NewFresh(cx, t), repo)), nil
	}
}

func (e *env) count(t *testing.T, sql string) int {
	t.Helper()
	var n int
	if err := e.db.Pool().QueryRow(context.Background(), sql).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func wantError(t *testing.T, err error, kind usecase.Kind, code string) {
	t.Helper()
	ue, ok := usecase.AsError(err)
	if !ok {
		t.Fatalf("err %v (%T), want a *usecase.Error", err, err)
	}
	if ue.Kind() != kind || ue.Code() != code {
		t.Fatalf("got %s %s (%s), want %s %s", ue.Kind(), ue.Code(), ue.Message(), kind, code)
	}
}

// ---- the tests -------------------------------------------------------------------------------

func TestCommitWritesAggregateEventsAndOneAuditRow(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	d := e.define(t, testCase{execute: e.insertPlan(thingRepo{})})
	done, err := d.Run(t.Context(), e.cx, cmd{Name: "first", data: true})
	if err != nil {
		t.Fatal(err)
	}
	if done.Event().Name != "first" {
		t.Errorf("committed event %+v", done.Event())
	}
	if n := e.count(t, "SELECT count(*) FROM things WHERE version = 1 AND client_id = 'acme'"); n != 1 {
		t.Errorf("things at version 1: %d", n)
	}
	var types []string
	rows, err := e.db.Pool().Query(t.Context(), "SELECT type FROM outbox_messages ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		types = append(types, s)
	}
	if strings.Join(types, ",") != "EVENT,AUDIT_LOG" {
		t.Errorf("outbox rows %v", types)
	}
	var audit string
	if err := e.db.Pool().QueryRow(t.Context(), "SELECT payload FROM outbox_messages WHERE type = 'AUDIT_LOG'").Scan(&audit); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		`"entityType":"Thing"`, `"operation":"test-thing"`, `"operationData":"{\"name\":\"first\",\"n\":2}"`,
		`"principalId":"user-1"`, `"performedAt":"2026-10-08T10:15:30.123Z"`, `"source":"test:things"`,
	} {
		if !strings.Contains(audit, want) {
			t.Errorf("audit payload %s lacks %s", audit, want)
		}
	}
}

func TestValidationAndAuthorizationFailures(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	required := usecase.MustErrorCode("NAME_REQUIRED")
	cases := []struct {
		name string
		uc   testCase
		kind usecase.Kind
		code string
	}{
		{
			"validate refuses",
			testCase{validate: func(c cmd) error { return usecase.Validation(required, "A name is required.") }},
			usecase.KindValidation, "NAME_REQUIRED",
		},
		{
			"validate fails unexpectedly",
			testCase{validate: func(cmd) error { return errors.New("boom") }},
			usecase.KindInternal, "VALIDATE",
		},
		{"validate panics", testCase{validate: func(cmd) error { panic("bug") }}, usecase.KindInternal, "VALIDATE"},
		{"authorize refuses", testCase{authorize: func(fw.Context) (usecase.Grant, error) {
			return usecase.Require(fw.ContextFor(fw.MustClientID("x")), fw.MustPermission("test:things:write"))
		}}, usecase.KindAuthorization, "PERMISSION_REQUIRED"},
		{
			"authorize returns the zero grant",
			testCase{authorize: func(fw.Context) (usecase.Grant, error) { return usecase.Grant{}, nil }},
			usecase.KindAuthorization, "NOT_AUTHORIZED",
		},
		{
			"authorize fails unexpectedly",
			testCase{authorize: func(fw.Context) (usecase.Grant, error) { return usecase.Grant{}, errors.New("boom") }},
			usecase.KindInternal, "AUTHORIZE",
		},
		{"execute rejects", testCase{execute: func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
			return nil, usecase.NotFound(usecase.MustErrorCode("THING_NOT_FOUND"), "There is no such thing.")
		}}, usecase.KindNotFound, "THING_NOT_FOUND"},
		{"execute fails unexpectedly", testCase{execute: func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
			return nil, errors.New("connection reset")
		}}, usecase.KindInternal, "EXECUTE"},
		{"execute panics", testCase{execute: func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
			panic("nil map")
		}}, usecase.KindInternal, "EXECUTE"},
		{"execute returns no plan", testCase{execute: func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
			return nil, nil
		}}, usecase.KindInternal, "EXECUTE"},
	}
	for _, c := range cases {
		if c.uc.execute == nil {
			c.uc.execute = e.insertPlan(thingRepo{})
		}
		_, err := e.define(t, c.uc).Run(t.Context(), e.cx, cmd{Name: c.name})
		t.Run(c.name, func(t *testing.T) { wantError(t, err, c.kind, c.code) })
	}
	if n := e.count(t, "SELECT count(*) FROM outbox_messages"); n != 0 {
		t.Errorf("%d outbox rows after failures", n)
	}
	ue, _ := usecase.AsError(func() error {
		_, err := e.define(t, cases[7].uc).Run(t.Context(), e.cx, cmd{})
		return err
	}())
	if ue == nil || strings.Contains(ue.Message(), "connection reset") || ue.Message() != "The operation could not be completed (EXECUTE)." {
		t.Errorf("an internal message must not carry the cause: %v", ue)
	}
}

func TestCrossClientWriteIsRefusedAndNotRetried(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	var attempts atomic.Int32
	retry, _ := usecase.Retry(3, time.Millisecond)
	d := e.define(t, testCase{attempts: &attempts, execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_1", name: "other's"}
		loaded := usecase.LoadedFrom(fw.MustClientID("someone-else"), 3, t1) // a Find that forgot client_id
		return usecase.NewPlan(e.created(cx, t1), usecase.Update(loaded, thingRepo{})), nil
	}}, usecase.WithConflictPolicy(retry))
	_, err := d.Run(t.Context(), e.cx, cmd{})
	wantError(t, err, usecase.KindInternal, "CROSS_CLIENT_WRITE")
	if attempts.Load() != 1 {
		t.Errorf("%d attempts, want 1", attempts.Load())
	}
}

func TestStaleAggregateAppliesThePolicy(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	if _, err := e.define(t, testCase{execute: e.insertPlan(thingRepo{})}).Run(t.Context(), e.cx, cmd{Name: "v1"}); err != nil {
		t.Fatal(err)
	}
	var id string
	_ = e.db.Pool().QueryRow(t.Context(), "SELECT id FROM things").Scan(&id)
	rename := func(attempts *atomic.Int32, staleFirst int32) testCase {
		return testCase{attempts: attempts, execute: func(ctx context.Context, cx fw.Context, c cmd, db *database.DB) (*usecase.Plan[thingEvent], error) {
			loaded, found, err := thingRepo{}.Find(ctx, db, cx.Client(), id)
			if err != nil || !found {
				return nil, errors.New("not found")
			}
			if attempts.Load() <= staleFirst { // someone else commits between the load and the write
				if _, err := db.Pool().Exec(ctx, "UPDATE things SET version = version + 1 WHERE id = $1", id); err != nil {
					return nil, err
				}
			}
			changed := loaded.Get()
			changed.name = c.Name
			return usecase.NewPlan(e.created(cx, changed), usecase.Update(loaded.With(changed), thingRepo{})), nil
		}}
	}
	var a1 atomic.Int32
	_, err := e.define(t, rename(&a1, 99)).Run(t.Context(), e.cx, cmd{Name: "fail"})
	wantError(t, err, usecase.KindConflict, "STALE_AGGREGATE")
	if a1.Load() != 1 {
		t.Errorf("Fail: %d attempts", a1.Load())
	}

	retry, err := usecase.Retry(3, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	var a2 atomic.Int32
	_, err = e.define(t, rename(&a2, 99), usecase.WithConflictPolicy(retry)).Run(t.Context(), e.cx, cmd{Name: "never"})
	wantError(t, err, usecase.KindConflict, "STALE_AGGREGATE")
	if a2.Load() != 3 {
		t.Errorf("Retry(3): %d attempts", a2.Load())
	}

	var a3 atomic.Int32
	if _, err := e.define(t, rename(&a3, 1), usecase.WithConflictPolicy(retry)).Run(t.Context(), e.cx, cmd{Name: "second time"}); err != nil {
		t.Fatalf("the second attempt should commit: %v", err)
	}
	if a3.Load() != 2 {
		t.Errorf("%d attempts, want 2", a3.Load())
	}
	if n := e.count(t, "SELECT count(*) FROM things WHERE name = 'second time'"); n != 1 {
		t.Errorf("rename not committed")
	}
	if n := e.count(t, "SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'"); n != 2 {
		t.Errorf("events %d: the failed attempts must leave no rows", n)
	}
}

func TestDatabaseConflictsAndFailuresByStep(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	var attempts atomic.Int32
	retry, _ := usecase.Retry(2, time.Millisecond)
	serialization := testCase{attempts: &attempts, execute: func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
		return nil, &pgconn.PgError{Code: "40001", Message: "could not serialize access"}
	}}
	_, err := e.define(t, serialization, usecase.WithConflictPolicy(retry)).Run(t.Context(), e.cx, cmd{})
	wantError(t, err, usecase.KindConflict, "STALE_AGGREGATE")
	if attempts.Load() != 2 {
		t.Errorf("40001 must be retried: %d attempts", attempts.Load())
	}

	if _, err := e.define(t, testCase{execute: e.insertPlan(thingRepo{})}).Run(t.Context(), e.cx, cmd{Name: "taken"}); err != nil {
		t.Fatal(err)
	}
	var dup atomic.Int32
	_, err = e.define(t, testCase{attempts: &dup, execute: e.insertPlan(thingRepo{})}, usecase.WithConflictPolicy(retry)).
		Run(t.Context(), e.cx, cmd{Name: "taken"})
	wantError(t, err, usecase.KindConflict, "UNIQUE_VIOLATION")
	ue, ok := usecase.AsError(err)
	if !ok {
		t.Fatal(err)
	}
	if ue.Details()["constraint"] != "things_name_key" || dup.Load() != 1 {
		t.Errorf("details %v, attempts %d (a unique violation is not retried)", ue.Details(), dup.Load())
	}

	two := int64(2)
	cases := []struct {
		name string
		uc   testCase
		code string
	}{
		{"a repository error", testCase{execute: e.insertPlan(thingRepo{insertErr: errors.New("disk full")})}, "PERSIST"},
		{"an update of two rows", testCase{execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
			t1 := thing{id: "thg_x", name: "x"}
			return usecase.NewPlan(e.created(cx, t1), usecase.Update(usecase.LoadedFrom(cx.Client(), 1, t1), thingRepo{updateRows: &two})), nil
		}}, "PERSIST"},
		{"an event that does not marshal", testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
			t1 := thing{id: e.boot.Ids().Prefixed(fw.MustIDPrefix("thg")), name: "nan " + c.Name}
			ev := e.created(cx, t1)
			ev.Bad = math.NaN()
			return usecase.NewPlan(ev, usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{})), nil
		}}, "OUTBOX"},
		{"a deferred constraint failing at commit", testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
			t1 := thing{id: e.boot.Ids().Prefixed(fw.MustIDPrefix("thg")), name: "orphan " + c.Name, parent: "thg_missing"}
			return usecase.NewPlan(e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{})), nil
		}}, "COMMIT"},
	}
	for _, c := range cases {
		_, err := e.define(t, c.uc).Run(t.Context(), e.cx, cmd{Name: c.name})
		t.Run(c.name, func(t *testing.T) { wantError(t, err, usecase.KindInternal, c.code) })
	}
	if !strings.Contains(e.logs.String(), "disk full") {
		t.Error("the cause of an INTERNAL failure must be logged")
	}
	if n := e.count(t, "SELECT count(*) FROM things WHERE name LIKE 'nan %' OR name LIKE 'orphan %'"); n != 0 {
		t.Errorf("failed attempts left %d rows", n)
	}
}

type badAudit struct{}

func (badAudit) AuditData() []usecase.AuditField {
	return []usecase.AuditField{usecase.Field("f", func() {})}
}

func TestAuditFailureAndBeginFailure(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	type badCmdUseCase = usecase.UseCase[badAudit, thingEvent]
	var uc badCmdUseCase = auditUseCase{e: e}
	d, err := usecase.Define(e.runner, uc)
	if err != nil {
		t.Fatal(err)
	}
	_, err = d.Run(t.Context(), e.cx, badAudit{})
	wantError(t, err, usecase.KindInternal, "AUDIT")

	e.db.Close()
	_, err = e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_b", name: "b"}
		return usecase.NewPlan(e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{})), nil
	}}).Run(t.Context(), e.cx, cmd{})
	wantError(t, err, usecase.KindInternal, "BEGIN")
}

type auditUseCase struct{ e *env }

func (auditUseCase) Name() usecase.Name { return usecase.MustName("bad-audit") }

func (auditUseCase) Validate(context.Context, fw.Context, badAudit) error { return nil }

func (auditUseCase) Authorize(context.Context, fw.Context, usecase.Valid[badAudit]) (usecase.Grant, error) {
	return usecase.Public(), nil
}

func (u auditUseCase) Execute(_ context.Context, cx fw.Context, _ usecase.Authorized[badAudit], _ *database.DB) (*usecase.Plan[thingEvent], error) {
	t1 := thing{id: u.e.boot.Ids().Prefixed(fw.MustIDPrefix("thg")), name: "audited"}
	return usecase.NewPlan(u.e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{})), nil
}

func TestInterruptedWhileWaitingBetweenAttempts(t *testing.T) {
	t.Parallel()
	ctx, cancel := context.WithCancel(t.Context())
	e := newEnv(t, usecase.WithWait(func(ctx context.Context, _ time.Duration) error {
		cancel()
		<-ctx.Done()
		return ctx.Err()
	}))
	retry, _ := usecase.Retry(5, time.Hour)
	d := e.define(t, testCase{execute: func(context.Context, fw.Context, cmd, *database.DB) (*usecase.Plan[thingEvent], error) {
		return nil, &pgconn.PgError{Code: "40P01", Message: "deadlock detected"}
	}}, usecase.WithConflictPolicy(retry))
	_, err := d.Run(ctx, e.cx, cmd{})
	wantError(t, err, usecase.KindInternal, "INTERRUPTED")
}

func TestEmitOnlyPlanAuditsTheSubjectAndDispatchJobsAreWritten(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	job, err := usecase.NewDispatchJob("test:things:notify", "https://hooks.example.com/n", usecase.JobID("job-1"))
	if err != nil {
		t.Fatal(err)
	}
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		ev := thingEvent{Meta: e.events.Metadata(cx, thingCreated, "thg.with.dots"), ID: "x"}
		second := thingEvent{Meta: e.events.Metadata(cx, thingCreated, "thg.with.dots"), ID: "y"}
		return usecase.NewPlan(ev).Emit(second).Dispatch(job), nil
	}})
	if _, err := d.Run(t.Context(), e.cx, cmd{}); err != nil {
		t.Fatal(err)
	}
	var types []string
	rows, err := e.db.Pool().Query(t.Context(), "SELECT type FROM outbox_messages ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var s string
		_ = rows.Scan(&s)
		types = append(types, s)
	}
	if strings.Join(types, ",") != "EVENT,EVENT,DISPATCH_JOB,AUDIT_LOG" {
		t.Errorf("rows %v: events, then dispatch jobs, then the audit row", types)
	}
	var audit string
	_ = e.db.Pool().QueryRow(t.Context(), "SELECT payload FROM outbox_messages WHERE type = 'AUDIT_LOG'").Scan(&audit)
	if !strings.Contains(audit, `"entityType":"thing","entityId":"thg.with.dots"`) {
		t.Errorf("audit of an emit-only plan: %s", audit)
	}
}

func TestMetricsCountOutcomes(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	ok := e.define(t, testCase{execute: e.insertPlan(thingRepo{})})
	_, _ = ok.Run(t.Context(), e.cx, cmd{Name: "m1"})
	_, _ = ok.Run(t.Context(), e.cx, cmd{Name: "m1"}) // unique violation: conflict
	var buf bytes.Buffer
	if err := writeMetrics(&buf, e); err != nil {
		t.Fatal(err)
	}
	for _, want := range []string{
		`fw_usecase_runs_total{usecase="test-thing",outcome="committed"} 1`,
		`fw_usecase_runs_total{usecase="test-thing",outcome="conflict"} 1`,
		`fw_usecase_duration_seconds_count{usecase="test-thing"} 2`,
	} {
		if !strings.Contains(buf.String(), want) {
			t.Errorf("metrics lack %s:\n%s", want, buf.String())
		}
	}
}

func TestDefineAndPolicyRules(t *testing.T) {
	t.Parallel()
	for _, n := range []int{1, 11} {
		if _, err := usecase.Retry(n, time.Millisecond); err == nil {
			t.Errorf("Retry(%d) accepted", n)
		}
	}
	if _, err := usecase.Retry(2, -time.Millisecond); err == nil {
		t.Error("a negative backoff was accepted")
	}
	e := newEnv(t)
	if _, err := usecase.Define(e.runner, testCase{}, usecase.WithConflictPolicy(usecase.ConflictPolicy{})); err == nil {
		t.Error("the zero ConflictPolicy was accepted")
	}
	if _, err := usecase.ParseName("Place_Order"); err == nil {
		t.Error("a bad name was accepted")
	}
	if _, err := usecase.ParseErrorCode("order-not-found"); err == nil {
		t.Error("a bad error code was accepted")
	}
	if _, err := e.define(t, testCase{execute: e.insertPlan(thingRepo{})}).Run(t.Context(), fw.Context{}, cmd{}); err == nil {
		t.Error("the zero Context was accepted")
	}
}

// A service principal fans out over clients (core contract 3a): each run is scoped to the client
// in hand, Authorize decides per client (ForClient checks nothing itself), and an authorization
// refusal for one client is that client's Failed result while the others commit.
func TestEachClientRunsUseCasesPerClientAndAuthorizeDecidesPerClient(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	d := e.define(t, testCase{
		authorize: func(cx fw.Context) (usecase.Grant, error) {
			if cx.Client().String() == "globex" {
				return usecase.Require(cx, fw.MustPermission("test:things:write")) // not held for globex
			}
			return usecase.Public(), nil
		},
		execute: e.insertPlan(thingRepo{}),
	})
	base := fw.ContextFor(fw.MustClientID("_platform")).WithPrincipalOfKind(fw.MustPrincipalID("prn_svc"), fw.PrincipalService)

	res := fw.EachClient(t.Context(), nil, base, []fw.ClientID{fw.MustClientID("acme"), fw.MustClientID("globex"), fw.MustClientID("initech")},
		func(ctx context.Context, cx fw.Context) (string, error) {
			done, err := d.Run(ctx, cx, cmd{Name: "swept-" + cx.Client().String()})
			return done.Event().Name, err
		})

	if len(res) != 3 || !res[0].Done() || !res[2].Done() || !res[1].Failed() {
		t.Fatalf("results %+v", res)
	}
	wantError(t, res[1].Err, usecase.KindAuthorization, "PERMISSION_REQUIRED")
	for _, c := range []string{"acme", "initech"} {
		if n := e.count(t, "SELECT count(*) FROM things WHERE client_id = '"+c+"' AND name = 'swept-"+c+"'"); n != 1 {
			t.Errorf("things for %s: %d", c, n)
		}
	}
	if n := e.count(t, "SELECT count(*) FROM things WHERE client_id = 'globex' OR client_id = '_platform'"); n != 0 {
		t.Errorf("things written for the refused client or the base: %d", n)
	}
}
