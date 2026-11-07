package usecase_test

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// usecase.Append (fulfil-go port, item 8): an append-only row (an activity entry, a live-update log
// line, a projection) written by the plan in its transaction, in plan order, never the audit row's
// subject and never version-checked.

const journalSQL = `CREATE TABLE journal (
    seq       bigserial PRIMARY KEY,
    client_id text NOT NULL,
    note      text NOT NULL,
    things    int NOT NULL
)`

// entry is a journal line; things is filled in by the appender at the moment it writes.
type entry struct {
	client fw.ClientID
	thing  string
	note   string
}

// journal appends entries; with fail set it fails.
type journal struct{ fail error }

func (j journal) Append(ctx context.Context, tx *database.Tx, e entry) error {
	if j.fail != nil {
		return j.fail
	}
	// how many rows of the thing exist at this point of the plan: 0 before its Insert, 1 after
	_, err := tx.Conn().Exec(ctx, `INSERT INTO journal (client_id, note, things)
		VALUES ($1, $2, (SELECT count(*) FROM things WHERE id = $3))`, e.client.String(), e.note, e.thing)
	return err
}

func newJournalEnv(t *testing.T) *env {
	t.Helper()
	e := newEnv(t)
	if _, err := e.db.Pool().Exec(t.Context(), journalSQL); err != nil {
		t.Fatal(err)
	}
	return e
}

func (e *env) journal(t *testing.T) []string {
	t.Helper()
	rows, err := e.db.Pool().Query(t.Context(), "SELECT note || '@' || things::text || ':' || client_id FROM journal ORDER BY seq")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		if err := rows.Scan(&s); err != nil {
			t.Fatal(err)
		}
		out = append(out, s)
	}
	return out
}

func TestAppendedRowsAreWrittenInPlanOrderAndTheAggregateIsTheAuditSubject(t *testing.T) {
	t.Parallel()
	e := newJournalEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_journal", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "before"}, journal{}), // first in the plan, yet not the audit subject
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
			usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "after"}, journal{}),
		), nil
	}})
	if _, err := d.Run(t.Context(), e.cx, cmd{Name: "journaled"}); err != nil {
		t.Fatal(err)
	}
	if got := strings.Join(e.journal(t), ","); got != "before@0:acme,after@1:acme" {
		t.Errorf("journal %s: the appends run in plan order, around the insert", got)
	}
	var audit string
	if err := e.db.Pool().QueryRow(t.Context(), "SELECT payload FROM outbox_messages WHERE type = 'AUDIT_LOG'").Scan(&audit); err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(audit, `"entityType":"Thing","entityId":"thg_journal"`) {
		t.Errorf("the audit row must name the aggregate, not the append: %s", audit)
	}
}

func TestAnAppendIsRolledBackWithTheRestOfThePlan(t *testing.T) {
	t.Parallel()
	e := newJournalEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_rolled", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "lost"}, journal{}),
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{insertErr: errors.New("disk full")}),
		), nil
	}})
	_, err := d.Run(t.Context(), e.cx, cmd{Name: "rolled"})
	wantError(t, err, usecase.KindInternal, "PERSIST")
	if got := e.journal(t); len(got) != 0 {
		t.Errorf("the append survived the failed attempt: %v", got)
	}
}

func TestAFailingAppendFailsThePersistStep(t *testing.T) {
	t.Parallel()
	e := newJournalEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_failing", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
			usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "x"}, journal{fail: errors.New("journal full")}),
		), nil
	}})
	_, err := d.Run(t.Context(), e.cx, cmd{Name: "failing"})
	wantError(t, err, usecase.KindInternal, "PERSIST")
	if n := e.count(t, "SELECT count(*) FROM things"); n != 0 {
		t.Errorf("the insert survived the failed append: %d", n)
	}
}

func TestAConflictedAttemptLeavesNoAppendAndTheRetryAppendsOnce(t *testing.T) {
	t.Parallel()
	e := newJournalEnv(t)
	if _, err := e.define(t, testCase{execute: e.insertPlan(thingRepo{})}).Run(t.Context(), e.cx, cmd{Name: "v1"}); err != nil {
		t.Fatal(err)
	}
	var id string
	if err := e.db.Pool().QueryRow(t.Context(), "SELECT id FROM things").Scan(&id); err != nil {
		t.Fatal(err)
	}
	var attempts atomic.Int32
	retry, err := usecase.Retry(3, time.Millisecond)
	if err != nil {
		t.Fatal(err)
	}
	d := e.define(t, testCase{attempts: &attempts, execute: func(ctx context.Context, cx fw.Context, c cmd, db *database.DB) (*usecase.Plan[thingEvent], error) {
		loaded, found, err := thingRepo{}.Find(ctx, db, cx.Client(), id)
		if err != nil || !found {
			return nil, errors.New("not found")
		}
		if attempts.Load() == 1 { // someone else commits between the load and the write
			if _, err := db.Pool().Exec(ctx, "UPDATE things SET version = version + 1 WHERE id = $1", id); err != nil {
				return nil, err
			}
		}
		changed := loaded.Get()
		changed.name = c.Name
		return usecase.NewPlan(e.created(cx, changed),
			usecase.Append(entry{client: cx.Client(), thing: id, note: "renamed"}, journal{}),
			usecase.Update(loaded.With(changed), thingRepo{}),
		), nil
	}}, usecase.WithConflictPolicy(retry))
	if _, err := d.Run(t.Context(), e.cx, cmd{Name: "v2"}); err != nil {
		t.Fatal(err)
	}
	if attempts.Load() != 2 {
		t.Errorf("%d attempts, want 2", attempts.Load())
	}
	if got := strings.Join(e.journal(t), ","); got != "renamed@1:acme" {
		t.Errorf("journal %s: one append, from the attempt that committed", got)
	}
}

func TestAPlanOfAppendsOnlyIsRefused(t *testing.T) {
	t.Parallel()
	e := newJournalEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_none"}
		return usecase.NewPlan(e.created(cx, t1), usecase.Append(entry{client: cx.Client(), thing: t1.id, note: "alone"}, journal{})), nil
	}})
	_, err := d.Run(t.Context(), e.cx, cmd{})
	wantError(t, err, usecase.KindInternal, "APPEND_ONLY_PLAN")
	if !strings.Contains(e.logs.String(), "a plan of appends only") {
		t.Errorf("the refusal must say why in the log:\n%s", e.logs.String())
	}
	if got := e.journal(t); len(got) != 0 {
		t.Errorf("an append-only plan wrote %v", got)
	}
	if n := e.count(t, "SELECT count(*) FROM outbox_messages"); n != 0 {
		t.Errorf("an append-only plan wrote %d outbox rows", n)
	}
}
