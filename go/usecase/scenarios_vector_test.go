package usecase_test

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"maps"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/usecase"
	"github.com/flowcatalyst/framework/go/usecase/usecasetest"
)

// The scenarios of conformance/usecase/scenarios.json (Java: UseCaseScenariosConformanceTest): a use
// case described by flags, run against PostgreSQL, compared on result, error, phase trace, attempts,
// waits and rows.

const scenarioTableSQL = `
CREATE TABLE thing (id text PRIMARY KEY, client_id text, version bigint, name text, code text UNIQUE);
CREATE FUNCTION thing_commit_check() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.name = 'commit-fails' THEN RAISE EXCEPTION 'deferred check failed' USING ERRCODE = '23514'; END IF;
  RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER thing_commit_check AFTER INSERT ON thing DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION thing_commit_check();`

type scnVector struct {
	Scenarios []scnCase `json:"scenarios"`
}

type scnCase struct {
	Name    string   `json:"name"`
	Prior   []string `json:"prior"`
	UseCase struct {
		Validate        string `json:"validate"`
		Authorize       string `json:"authorize"`
		Load            string `json:"load"`
		AuthorizeLoaded string `json:"authorizeLoaded"`
		Execute         string `json:"execute"`
		Conflict        struct {
			Policy      string `json:"policy"`
			MaxAttempts int    `json:"maxAttempts"`
			BackoffMs   int    `json:"backoffMs"`
		} `json:"conflict"`
	} `json:"useCase"`
	Command scnCommand `json:"command"`
	Client  string     `json:"client"`
	Faults  []struct {
		Kind     string `json:"kind"`
		State    string `json:"state"`
		Attempts []int  `json:"attempts"`
	} `json:"faults"`
	Join   *scnJoin `json:"join"`
	Expect struct {
		Result string `json:"result"`
		Error  *struct {
			Kind       string            `json:"kind"`
			Code       string            `json:"code"`
			HTTPStatus int               `json:"httpStatus"`
			Details    map[string]string `json:"details"`
		} `json:"error"`
		Attempts int      `json:"attempts"`
		Waits    int      `json:"waits"`
		Trace    []string `json:"trace"`
		Events   []struct {
			Type    string `json:"type"`
			Subject string `json:"subject"`
		} `json:"events"`
		Audits []struct {
			EntityType string `json:"entityType"`
			EntityID   string `json:"entityId"`
			Operation  string `json:"operation"`
		} `json:"audits"`
		Things                []string `json:"things"`
		SeenByOthersBeforeEnd []string `json:"seenByOthersBeforeEnd"`
		FollowUp              *struct {
			Result string `json:"result"`
			Events int    `json:"events"`
		} `json:"followUp"`
		Throws string `json:"throws"`
	} `json:"expect"`
}

type scnCommand struct {
	Op   string  `json:"op"`
	ID   string  `json:"id"`
	Name *string `json:"name"`
	Code *string `json:"code"`

	st *scnState // set by the harness; carries the audit fault
}

func (c scnCommand) AuditData() []usecase.AuditField {
	if c.st != nil && c.st.faultOn("audit") {
		return []usecase.AuditField{usecase.Field("f", func() {})}
	}
	return nil
}

type scnJoin struct {
	CallerInsert bool        `json:"callerInsert"`
	End          string      `json:"end"`
	AutoCommit   bool        `json:"autoCommit"`
	FollowUp     *scnCommand `json:"followUp"`
}

// scnThing is the aggregate: the row, without client and version (those live in Loaded).
type scnThing struct {
	id, name string
	code     *string
}

func (t scnThing) AuditEntity() usecase.Entity { return usecase.Entity{Type: "Thing", ID: t.id} }

type scnEvent struct {
	Meta event.Metadata `json:"-"`
	ID   string         `json:"id"`
	Bad  func()         `json:"bad,omitzero"`
}

func (e scnEvent) Metadata() event.Metadata { return e.Meta }

// scnState is the run's shared state: the trace, the attempt counter and the faults.
type scnState struct {
	mu      sync.Mutex
	trace   []string
	attempt int
	waits   int
	faults  map[string]map[int]string // kind -> attempt -> state
	pool    func() *database.DB
}

func (s *scnState) record(phase string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if phase == "validate" {
		s.attempt++
	}
	s.trace = append(s.trace, phase)
}

func (s *scnState) faultOn(kind string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	_, ok := s.faults[kind][s.attempt]
	return ok
}

func (s *scnState) faultState(kind string) (string, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	v, ok := s.faults[kind][s.attempt]
	return v, ok
}

// scnRepo is the repository over table thing, with the persist-step faults.
type scnRepo struct{ st *scnState }

func (r scnRepo) Find(ctx context.Context, db *database.DB, client fw.ClientID, id string) (usecase.Loaded[scnThing], bool, error) {
	var t scnThing
	var c string
	var v int64
	err := db.Pool().QueryRow(ctx, "SELECT id, client_id, version, name, code FROM thing WHERE id = $1 AND client_id = $2", id, client.String()).
		Scan(&t.id, &c, &v, &t.name, &t.code)
	if errors.Is(err, pgx.ErrNoRows) {
		return usecase.Loaded[scnThing]{}, false, nil
	}
	if err != nil {
		return usecase.Loaded[scnThing]{}, false, err
	}
	return usecase.LoadedFrom(fw.MustClientID(c), v, t), true, nil
}

func (r scnRepo) persistFault() error {
	if state, ok := r.st.faultState("sqlState"); ok {
		return &pgconn.PgError{Code: state, Message: "injected " + state}
	}
	if _, ok := r.st.faultState("persistError"); ok {
		return errors.New("injected persist error with secret text")
	}
	return nil
}

func (r scnRepo) Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, t scnThing) error {
	if err := r.persistFault(); err != nil {
		return err
	}
	_, err := tx.Conn().Exec(ctx, "INSERT INTO thing (id, client_id, version, name, code) VALUES ($1, $2, 1, $3, $4)", t.id, client.String(), t.name, t.code)
	return err
}

func (r scnRepo) Update(ctx context.Context, tx *database.Tx, client fw.ClientID, t scnThing, v usecase.VersionCheck) (int64, error) {
	if _, ok := r.st.faultState("concurrentWrite"); ok {
		// another writer bumps the row's version after this attempt loaded it
		if _, err := r.st.pool().Pool().Exec(ctx, "UPDATE thing SET version = version + 1 WHERE id = $1", t.id); err != nil {
			return 0, err
		}
	}
	if err := r.persistFault(); err != nil {
		return 0, err
	}
	tag, err := tx.Conn().Exec(ctx, "UPDATE thing SET name = $1, code = $2, version = $3 WHERE id = $4 AND client_id = $5 AND version = $6",
		t.name, t.code, v.Next, t.id, client.String(), v.Expected)
	return tag.RowsAffected(), err
}

func (r scnRepo) Delete(ctx context.Context, tx *database.Tx, client fw.ClientID, t scnThing, v usecase.VersionCheck) (int64, error) {
	if _, ok := r.st.faultState("concurrentWrite"); ok {
		if _, err := r.st.pool().Pool().Exec(ctx, "UPDATE thing SET version = version + 1 WHERE id = $1", t.id); err != nil {
			return 0, err
		}
	}
	if err := r.persistFault(); err != nil {
		return 0, err
	}
	tag, err := tx.Conn().Exec(ctx, "DELETE FROM thing WHERE id = $1 AND client_id = $2 AND version = $3", t.id, client.String(), v.Expected)
	return tag.RowsAffected(), err
}

// scnUseCase is the use case a scenario describes. It implements usecase.UseCase; scnLoaded wraps it
// for the scenarios with a load phase.
type scnUseCase struct {
	c      *scnCase
	st     *scnState
	repo   scnRepo
	events *event.Factory
}

var (
	typeCreated = event.MustType("things:stock:thing:created")
	typeChanged = event.MustType("things:stock:thing:changed")
	typeRemoved = event.MustType("things:stock:thing:removed")
)

func (u scnUseCase) Name() usecase.Name { return usecase.MustName("scenario") }

func (u scnUseCase) Validate(_ context.Context, _ fw.Context, _ scnCommand) error {
	u.st.record("validate")
	switch u.c.UseCase.Validate {
	case "refuse":
		return usecase.Validation(usecase.MustErrorCode("NAME_REQUIRED"), "A name is required.")
	case "throw":
		return errors.New("validate blew up: secret text")
	}
	return nil
}

func (u scnUseCase) Authorize(_ context.Context, _ fw.Context, _ usecase.Valid[scnCommand]) (usecase.Grant, error) {
	u.st.record("authorize")
	switch u.c.UseCase.Authorize {
	case "refuse":
		return usecase.Grant{}, usecase.Authorization(usecase.MustErrorCode("FORBIDDEN_OP"), "Not allowed.")
	case "throw":
		return usecase.Grant{}, errors.New("authorize blew up: secret text")
	}
	return usecase.Public(), nil
}

var errThingNotFound = usecase.NotFound(usecase.MustErrorCode("THING_NOT_FOUND"), "No such thing.")

func (u scnUseCase) event(cx fw.Context, t event.Type, id string) scnEvent {
	ev := scnEvent{Meta: u.events.Metadata(cx, t, id), ID: id}
	if u.st.faultOn("events") {
		ev.Bad = func() {}
	}
	return ev
}

func (u scnUseCase) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[scnCommand], db *database.DB) (*usecase.Plan[scnEvent], error) {
	u.st.record("execute")
	c := a.Command()
	switch u.c.UseCase.Execute {
	case "throw":
		return nil, errors.New("execute blew up: secret text")
	case "reject":
		return nil, usecase.BusinessRule(usecase.MustErrorCode("REJECTED"), "Rejected.")
	}
	switch c.Op {
	case "create":
		return u.create(cx, c)
	case "emit":
		// a plan with only an event (no aggregate): found through the repository, as the edit does
		u.st.record("find")
		if _, found, err := u.repo.Find(ctx, db, cx.Client(), c.ID); err != nil || !found {
			if err != nil {
				return nil, err
			}
			return nil, errThingNotFound
		}
		return usecase.NewPlan(u.event(cx, typeChanged, c.ID)), nil
	}
	if strings.HasPrefix(u.c.UseCase.Execute, "crossClient") {
		// the plan holds a thing of another client, made without reading it
		return u.apply(cx, c, usecase.LoadedFrom(cx.Client(), 1, scnThing{id: c.ID, name: deref(c.Name)}))
	}
	u.st.record("find")
	loaded, found, err := u.repo.Find(ctx, db, cx.Client(), c.ID)
	if err != nil {
		return nil, err
	}
	if !found {
		return nil, errThingNotFound
	}
	return u.apply(cx, c, loaded)
}

func (u scnUseCase) create(cx fw.Context, c scnCommand) (*usecase.Plan[scnEvent], error) {
	t := scnThing{id: c.ID, name: deref(c.Name), code: c.Code}
	if u.c.UseCase.Execute == "createThenClash" {
		clash := "CLASH-1"
		first := scnThing{id: "c1", name: "first", code: &clash}
		second := scnThing{id: "c2", name: "second", code: c.Code}
		return usecase.NewPlan(u.event(cx, typeCreated, "c1"),
			usecase.Insert(usecase.NewFresh(cx, first), u.repo), usecase.Insert(usecase.NewFresh(cx, second), u.repo)), nil
	}
	return usecase.NewPlan(u.event(cx, typeCreated, c.ID), usecase.Insert(usecase.NewFresh(cx, t), u.repo)), nil
}

// apply is the edit or remove of a thing that was found (by the use case or by its load phase).
func (u scnUseCase) apply(cx fw.Context, c scnCommand, loaded usecase.Loaded[scnThing]) (*usecase.Plan[scnEvent], error) {
	t := loaded.Get()
	switch u.c.UseCase.Execute {
	case "crossClient":
		other := usecase.LoadedFrom(fw.MustClientID("other"), loaded.Version(), t)
		return usecase.NewPlan(u.event(cx, typeChanged, c.ID), usecase.Update(other, u.repo)), nil
	case "crossClientDelete":
		other := usecase.LoadedFrom(fw.MustClientID("other"), 1, t)
		return usecase.NewPlan(u.event(cx, typeRemoved, c.ID), usecase.Delete(other, u.repo)), nil
	}
	if c.Op == "remove" {
		return usecase.NewPlan(u.event(cx, typeRemoved, c.ID), usecase.Delete(loaded, u.repo)), nil
	}
	if c.Name != nil {
		t.name = *c.Name
	}
	if c.Code != nil {
		t.code = c.Code
	}
	return usecase.NewPlan(u.event(cx, typeChanged, c.ID), usecase.Update(loaded.With(t), u.repo)), nil
}

// scnLoaded is the same use case with a load phase (usecase.LoadedUseCase) and the record check.
type scnLoaded struct{ scnUseCase }

type scnRecord = usecase.Loaded[scnThing]

func (u scnLoaded) Load(ctx context.Context, cx fw.Context, a usecase.Authorized[scnCommand], db *database.DB) (scnRecord, bool, error) {
	u.st.record("load")
	if u.c.UseCase.Load == "throw" {
		return scnRecord{}, false, errors.New("load blew up: secret text")
	}
	return u.repo.Find(ctx, db, cx.Client(), a.Command().ID)
}

func (u scnLoaded) WhenMissing() *usecase.Error { return errThingNotFound }

// scnChecked has the record-level check; the scenarios whose authorizeLoaded is "none" use scnLoaded.
type scnChecked struct{ scnLoaded }

var errLocked = usecase.Authorization(usecase.MustErrorCode("LOCKED"), "The thing is locked.")

func (u scnChecked) AuthorizeLoaded(_ context.Context, _ fw.Context, _ usecase.Authorized[scnCommand], r scnRecord) error {
	u.st.record("authorizeLoaded")
	switch u.c.UseCase.AuthorizeLoaded {
	case "refuse":
		return errLocked
	case "hide":
		return errThingNotFound
	case "throw":
		return errors.New("authorizeLoaded blew up: secret text")
	}
	if r.Get().name == "locked" {
		return errLocked
	}
	return nil
}

func (u scnLoaded) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[scnCommand], r scnRecord, _ *database.DB) (*usecase.Plan[scnEvent], error) {
	u.st.record("execute")
	switch u.c.UseCase.Execute {
	case "throw":
		return nil, errors.New("execute blew up: secret text")
	case "reject":
		return nil, usecase.BusinessRule(usecase.MustErrorCode("REJECTED"), "Rejected.")
	}
	return u.apply(cx, a.Command(), r)
}

func deref(s *string) string {
	if s == nil {
		return ""
	}
	return *s
}

// ---- rows -------------------------------------------------------------------------------------

func (c *scnCase) insertPrior(ctx context.Context, db *database.DB) error {
	for _, row := range c.Prior {
		f := strings.Split(row, "|")
		var code any
		if f[4] != "-" {
			code = f[4]
		}
		if _, err := db.Pool().Exec(ctx, "INSERT INTO thing (id, client_id, version, name, code) VALUES ($1,$2,$3,$4,$5)", f[0], f[1], f[2], f[3], code); err != nil {
			return err
		}
	}
	return nil
}

func queryer(ctx context.Context, q interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
},
) ([]string, error) {
	rows, err := q.Query(ctx, "SELECT id, client_id, version, name, coalesce(code, '-') FROM thing ORDER BY id")
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []string{}
	for rows.Next() {
		var id, client, name, code string
		var version int64
		if err := rows.Scan(&id, &client, &version, &name, &code); err != nil {
			return nil, err
		}
		out = append(out, fmt.Sprintf("%s|%s|%d|%s|%s", id, client, version, name, code))
	}
	return out, rows.Err()
}

func runScenario(t *testing.T, c *scnCase) {
	st := &scnState{faults: map[string]map[int]string{}}
	for _, f := range c.Faults {
		if st.faults[f.Kind] == nil {
			st.faults[f.Kind] = map[int]string{}
		}
		for _, at := range f.Attempts {
			st.faults[f.Kind][at] = f.State
		}
	}
	wait := func(_ context.Context, _ time.Duration) error {
		st.mu.Lock()
		st.waits++
		interrupted := false
		if _, ok := st.faults["interrupt"][st.attempt]; ok {
			interrupted = true
		}
		st.mu.Unlock()
		if interrupted {
			return errors.New("interrupted")
		}
		return nil
	}
	e := newEnv(t, usecase.WithWait(wait))
	ctx := t.Context()
	connString := e.db.Pool().Config().ConnString()
	side, err := database.Open(ctx, "side", connString)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(side.Close)
	st.pool = func() *database.DB { return side }
	if _, err := side.Pool().Exec(ctx, scenarioTableSQL); err != nil {
		t.Fatal(err)
	}
	if err := c.insertPrior(ctx, side); err != nil {
		t.Fatal(err)
	}
	base := scnUseCase{c: c, st: st, repo: scnRepo{st: st}, events: e.events}
	var policy usecase.DefineOption
	if c.UseCase.Conflict.Policy == "retry" {
		p, err := usecase.Retry(c.UseCase.Conflict.MaxAttempts, time.Duration(c.UseCase.Conflict.BackoffMs)*time.Millisecond)
		if err != nil {
			t.Fatal(err)
		}
		policy = usecase.WithConflictPolicy(p)
	} else {
		policy = usecase.WithConflictPolicy(usecase.Fail())
	}
	var d *usecase.Defined[scnCommand, scnEvent]
	switch {
	case c.UseCase.Load == "none":
		d, err = usecase.Define(e.runner, usecase.UseCase[scnCommand, scnEvent](base), policy)
	case c.UseCase.AuthorizeLoaded == "none":
		d, err = usecase.DefineLoaded(e.runner, usecase.LoadedUseCase[scnCommand, scnEvent, scnRecord](scnLoaded{base}), policy)
	default:
		d, err = usecase.DefineLoaded(e.runner, usecase.LoadedUseCase[scnCommand, scnEvent, scnRecord](scnChecked{scnLoaded{base}}), policy)
	}
	if err != nil {
		t.Fatal(err)
	}
	cx := fw.ContextFor(fw.MustClientID(c.Client)).WithPrincipal(fw.MustPrincipalID("usr_1"))
	cmd := c.Command
	cmd.st = st
	if _, ok := st.faults["begin"]; ok {
		e.db.Close() // the database cannot give a connection
	}
	if c.Join != nil {
		runJoined(t, c, st, d, cx, cmd, side)
		return
	}
	_, runErr := d.Run(ctx, cx, cmd)
	compareScenario(t, c, st, runErr, side, nil)
}

// compareScenario compares the result, the trace and the rows with the vector's expect block.
// inTx, when set, reads events and audits through the caller's transaction (a joined run).
func compareScenario(t *testing.T, c *scnCase, st *scnState, runErr error, side *database.DB, inTx pgx.Tx) {
	t.Helper()
	x := c.Expect
	if (runErr == nil) != (x.Result == "committed") {
		t.Fatalf("result: error %v, want %s", runErr, x.Result)
	}
	if runErr != nil {
		ue, ok := usecase.AsError(runErr)
		if !ok {
			t.Fatalf("error %v (%T) is not a *usecase.Error", runErr, runErr)
		}
		if ue.Kind().String() != x.Error.Kind || ue.Code() != x.Error.Code || ue.Status() != x.Error.HTTPStatus {
			t.Errorf("error %s %s (%d), want %s %s (%d)", ue.Kind(), ue.Code(), ue.Status(), x.Error.Kind, x.Error.Code, x.Error.HTTPStatus)
		}
		if got := ue.Details(); !maps.Equal(nilEmpty(got), nilEmpty(x.Error.Details)) {
			t.Errorf("error details %v, want %v", got, x.Error.Details)
		}
		for _, secret := range []string{"secret text", "injected"} {
			if strings.Contains(ue.Message(), secret) {
				t.Errorf("the cause leaked into the message: %q", ue.Message())
			}
		}
	}
	if !slices.Equal(st.trace, x.Trace) {
		t.Errorf("trace %v, want %v", st.trace, x.Trace)
	}
	if st.attempt != x.Attempts {
		t.Errorf("attempts %d, want %d", st.attempt, x.Attempts)
	}
	if st.waits != x.Waits {
		t.Errorf("waits %d, want %d", st.waits, x.Waits)
	}
	ctx := context.Background()
	var q interface {
		Query(context.Context, string, ...any) (pgx.Rows, error)
	} = side.Pool()
	if inTx != nil {
		q = inTx
	}
	if runErr == nil {
		checkOutbox(t, ctx, q, c)
	} else {
		var n int
		var row pgx.Row
		if inTx != nil {
			row = inTx.QueryRow(ctx, "SELECT count(*) FROM outbox_messages")
		} else {
			row = side.Pool().QueryRow(ctx, "SELECT count(*) FROM outbox_messages")
		}
		if err := row.Scan(&n); err != nil {
			t.Fatal(err)
		}
		// the vector lists the events the sink was handed before the failure; Go writes through the
		// transaction, so a failed attempt leaves no outbox row to list (see the guide)
		if n != 0 {
			t.Errorf("a failed run left %d outbox rows", n)
		}
	}
	if c.Join == nil {
		got, err := queryer(ctx, side.Pool())
		if err != nil {
			t.Fatal(err)
		}
		if !slices.Equal(got, x.Things) {
			t.Errorf("things %v, want %v", got, x.Things)
		}
	}
}

func nilEmpty(m map[string]string) map[string]string {
	if m == nil {
		return map[string]string{}
	}
	return m
}

func checkOutbox(t *testing.T, ctx context.Context, q interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}, c *scnCase,
) {
	t.Helper()
	rows, err := q.Query(ctx, "SELECT type, payload FROM outbox_messages ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var events, audits []string
	for rows.Next() {
		var typ, payload string
		if err := rows.Scan(&typ, &payload); err != nil {
			t.Fatal(err)
		}
		var m map[string]any
		if err := json.Unmarshal([]byte(payload), &m); err != nil {
			t.Fatal(err)
		}
		switch typ {
		case "EVENT":
			events = append(events, fmt.Sprint(m["type"], " ", m["subject"]))
		case "AUDIT_LOG":
			audits = append(audits, fmt.Sprint(m["entityType"], " ", m["entityId"], " ", m["operation"]))
		}
	}
	var wantE, wantA []string
	for _, e := range c.Expect.Events {
		wantE = append(wantE, e.Type+" "+e.Subject)
	}
	for _, a := range c.Expect.Audits {
		wantA = append(wantA, a.EntityType+" "+a.EntityID+" "+a.Operation)
	}
	if !slices.Equal(events, wantE) {
		t.Errorf("events %v, want %v", events, wantE)
	}
	if !slices.Equal(audits, wantA) {
		t.Errorf("audits %v, want %v", audits, wantA)
	}
}

// runJoined plays the caller who owns the transaction: it inserts its own row first, runs the use
// case on that transaction (and a follow-up after it), lets another connection look, then commits or
// rolls back.
func runJoined(t *testing.T, c *scnCase, st *scnState, d *usecase.Defined[scnCommand, scnEvent], cx fw.Context, cmd scnCommand, side *database.DB) {
	t.Helper()
	ctx := t.Context()
	if c.Join.AutoCommit {
		// Java: a connection in auto-commit is an IllegalArgumentException. Go cannot be handed one:
		// RunJoined takes the *database.Tx of a framework helper, which is always in a transaction.
		t.Skip("not applicable in Go: a joined handle is always in a transaction (documented difference)")
	}
	tx, err := usecasetest.Begin(ctx, side)
	if err != nil {
		t.Fatal(err)
	}
	ended := false
	t.Cleanup(func() {
		if !ended {
			_ = tx.Conn().Rollback(context.WithoutCancel(ctx))
		}
	})
	if c.Join.CallerInsert {
		if _, err := tx.Conn().Exec(ctx, "INSERT INTO thing (id, client_id, version, name, code) VALUES ('mine', 'acme', 1, 'callers', 'C-1')"); err != nil {
			t.Fatal(err)
		}
	}
	_, runErr := d.RunJoined(ctx, cx, cmd, tx)
	compareScenario(t, c, st, runErr, side, tx.Conn())
	if c.Expect.FollowUp != nil {
		before := countEvents(t, tx.Conn())
		follow := *c.Join.FollowUp
		follow.st = st
		_, ferr := d.RunJoined(ctx, cx, follow, tx)
		if (ferr == nil) != (c.Expect.FollowUp.Result == "committed") {
			t.Errorf("follow-up: error %v, want %s (the caller's transaction must stay usable)", ferr, c.Expect.FollowUp.Result)
		}
		if got := countEvents(t, tx.Conn()) - before; got != c.Expect.FollowUp.Events {
			t.Errorf("follow-up wrote %d events, want %d", got, c.Expect.FollowUp.Events)
		}
	}
	seen, err := queryer(ctx, side.Pool())
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(seen, c.Expect.SeenByOthersBeforeEnd) {
		t.Errorf("another connection sees %v before the caller ends, want %v", seen, c.Expect.SeenByOthersBeforeEnd)
	}
	switch c.Join.End {
	case "commit":
		err = tx.Conn().Commit(ctx)
	default:
		err = tx.Conn().Rollback(ctx)
	}
	ended = true
	if err != nil {
		t.Fatal(err)
	}
	got, err := queryer(ctx, side.Pool())
	if err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(got, c.Expect.Things) {
		t.Errorf("things after the caller ends %v, want %v", got, c.Expect.Things)
	}
}

func countEvents(t *testing.T, q pgx.Tx) int {
	t.Helper()
	var n int
	if err := q.QueryRow(context.Background(), "SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'").Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestUseCaseScenarioVectors(t *testing.T) {
	var v scnVector
	conformance.ReadJSON(t, "usecase/scenarios.json", &v)
	if len(v.Scenarios) == 0 {
		t.Fatal("no scenarios")
	}
	for i := range v.Scenarios {
		c := &v.Scenarios[i]
		t.Run(c.Name, func(t *testing.T) { t.Parallel(); runScenario(t, c) })
	}
}

type noMissing struct{ scnLoaded }

func (noMissing) WhenMissing() *usecase.Error { return nil }

func TestDefineLoadedNeedsAWhenMissingError(t *testing.T) {
	t.Parallel()
	e := newEnv(t)
	st := &scnState{faults: map[string]map[int]string{}}
	c := &scnCase{}
	uc := noMissing{scnLoaded{scnUseCase{c: c, st: st, repo: scnRepo{st: st}, events: e.events}}}
	if _, err := usecase.DefineLoaded(e.runner, usecase.LoadedUseCase[scnCommand, scnEvent, scnRecord](uc)); err == nil {
		t.Fatal("a loaded use case with no WhenMissing error was defined")
	}
}
