package usecase_test

import (
	"context"
	"errors"
	"io/fs"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/push"
	"github.com/flowcatalyst/framework/go/usecase"
)

// usecase.Push (push contract section 2): a row of fw_push_outbox written in the attempt's
// transaction, in plan order, for the Context's client; not an event; nothing from a failed attempt.

func newPushEnv(t *testing.T, opts ...usecase.RunnerOption) *env {
	t.Helper()
	e := newEnv(t, opts...)
	sql, err := fs.ReadFile(push.Migrations(), "V0001__create_push_tables.sql")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := e.db.Pool().Exec(t.Context(), string(sql)); err != nil {
		t.Fatal(err)
	}
	return e
}

type pushRow struct {
	topic, typ, client, data string
	at                       time.Time
}

func (e *env) pushes(t *testing.T) []pushRow {
	t.Helper()
	rows, err := e.db.Pool().Query(t.Context(), "SELECT topic, type, coalesce(client, '<null>'), data, created_at FROM fw_push_outbox ORDER BY id")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []pushRow
	for rows.Next() {
		var r pushRow
		if err := rows.Scan(&r.topic, &r.typ, &r.client, &r.data, &r.at); err != nil {
			t.Fatal(err)
		}
		out = append(out, r)
	}
	return out
}

func TestPushIsWrittenInPlanOrderForTheContextsClient(t *testing.T) {
	t.Parallel()
	e := newPushEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_push", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Push("test:thing:thg_push", "thing.creating", map[string]any{"z": 1, "a": "x"}),
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
			usecase.Push("test:thing:thg_push", "thing.created", map[string]any{"id": t1.id})), nil
	}})
	if _, err := d.Run(t.Context(), e.cx, cmd{Name: "one"}); err != nil {
		t.Fatal(err)
	}
	got := e.pushes(t)
	if len(got) != 2 || got[0].typ != "thing.creating" || got[1].typ != "thing.created" {
		t.Fatalf("pushes %+v", got)
	}
	if got[0].client != "acme" || got[0].data != `{"a":"x","z":1}` || !got[0].at.Equal(e.clock.Now()) {
		t.Errorf("row %+v", got[0])
	}
	if n := e.count(t, "SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'"); n != 1 {
		t.Errorf("a push is not an event: %d EVENT rows, want 1", n)
	}
}

func TestPushOfTheRolledBackAttemptIsNeverWritten(t *testing.T) {
	t.Parallel()
	e := newPushEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_fail", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Push("test:thing:thg_fail", "thing.created", map[string]any{}), // written first, then rolled back
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{insertErr: errors.New("disk full")})), nil
	}})
	_, err := d.Run(t.Context(), e.cx, cmd{Name: "one"})
	wantError(t, err, usecase.KindInternal, "PERSIST")
	if got := e.pushes(t); len(got) != 0 {
		t.Fatalf("a failed attempt wrote pushes: %+v", got)
	}
}

func TestPushForThePlatformClientIsNull(t *testing.T) {
	t.Parallel()
	e := newPushEnv(t)
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		return usecase.NewPlan(e.created(cx, thing{id: "thg_p"}),
			usecase.Push("test:announcements", "news", map[string]any{"n": 1})), nil
	}})
	if _, err := d.Run(t.Context(), fw.ContextFor(fw.PlatformClient), cmd{}); err != nil {
		t.Fatal(err)
	}
	if got := e.pushes(t); len(got) != 1 || got[0].client != "<null>" {
		t.Fatalf("pushes %+v, want one with a NULL client", got)
	}
}

func TestInvalidPushFailsTheRunAsInvalidPush(t *testing.T) {
	t.Parallel()
	e := newPushEnv(t, usecase.WithPushMaxDataBytes(12))
	for name, step := range map[string]usecase.Step{
		"topic":     usecase.Push("Test:thing", "thing.created", map[string]any{}),
		"type":      usecase.Push("test:thing", "reset", map[string]any{}),
		"not json":  usecase.Push("test:thing", "thing.created", []byte("{")),
		"not obj":   usecase.Push("test:thing", "thing.created", []int{1}),
		"too large": usecase.Push("test:thing", "thing.created", map[string]any{"name": "thirteen"}),
	} {
		d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
			t1 := thing{id: "thg_" + c.Name, name: c.Name}
			return usecase.NewPlan(e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}), step), nil
		}})
		_, err := d.Run(t.Context(), e.cx, cmd{Name: "x"})
		if ue, ok := usecase.AsError(err); !ok || ue.Kind() != usecase.KindInternal || ue.Code() != "INVALID_PUSH" {
			t.Errorf("%s: %v, want INTERNAL / INVALID_PUSH", name, err)
		}
	}
	if n := e.count(t, "SELECT count(*) FROM things"); n != 0 {
		t.Errorf("an invalid push is refused before the transaction: %d things", n)
	}
}

func TestPushFailureHasTheStepCodePush(t *testing.T) {
	t.Parallel()
	e := newEnv(t) // no fw_push_outbox
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_np", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
			usecase.Push("test:thing:thg_np", "thing.created", map[string]any{})), nil
	}})
	_, err := d.Run(t.Context(), e.cx, cmd{Name: "x"})
	wantError(t, err, usecase.KindInternal, "PUSH")
}

// push.driver=platform (push contract amendment 17.17): the runner writes a push as a PUSH row of
// the application's outbox, in the attempt's transaction, and notifies fc_outbox_push.

type outboxPush struct {
	id, typ, client, payload string
	group                    *string
}

func (e *env) outboxPushes(t *testing.T) []outboxPush {
	t.Helper()
	rows, err := e.db.Pool().Query(t.Context(), "SELECT id, type, client_id, message_group, payload FROM outbox_messages WHERE type = 'PUSH' ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []outboxPush
	for rows.Next() {
		var r outboxPush
		if err := rows.Scan(&r.id, &r.typ, &r.client, &r.group, &r.payload); err != nil {
			t.Fatal(err)
		}
		out = append(out, r)
	}
	return out
}

func TestPlatformPushIsAnOutboxRowInPlanOrder(t *testing.T) {
	t.Parallel()
	e := newEnv(t, usecase.WithPlatformPushes())
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_pp", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Push("test:thing:thg_pp", "thing.creating", map[string]any{"z": 1, "a": "x"}),
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}),
			usecase.Push("test:board", "refresh", map[string]any{}, usecase.PushUngrouped(), usecase.PushID("pid_1"), usecase.PushClientCode("globex"))), nil
	}})
	if _, err := d.Run(t.Context(), e.cx, cmd{Name: "one"}); err != nil {
		t.Fatal(err)
	}
	got := e.outboxPushes(t)
	if len(got) != 2 {
		t.Fatalf("pushes %+v", got)
	}
	first := got[0]
	if first.group == nil || *first.group != "test:thing:thg_pp" || first.client != "acme" || !fw.CanonicalTSID(first.id) {
		t.Errorf("first row %+v: want group = topic, client acme, a TSID id", first)
	}
	if want := `{"topic":"test:thing:thg_pp","type":"thing.creating","data":{"a":"x","z":1},"clientCode":"acme","pushId":"` + first.id + `"}`; first.payload != want {
		t.Errorf("first payload\n got %s\nwant %s", first.payload, want)
	}
	second := got[1]
	if second.group != nil || second.id != "pid_1" || second.client != "acme" ||
		second.payload != `{"topic":"test:board","type":"refresh","data":{},"clientCode":"globex","pushId":"pid_1"}` {
		t.Errorf("second row %+v", second)
	}
	if n := e.count(t, "SELECT count(*) FROM outbox_messages WHERE type = 'EVENT'"); n != 1 {
		t.Errorf("a push is not an event: %d EVENT rows, want 1", n)
	}
}

func TestPlatformPushOfTheRolledBackAttemptIsNeverWritten(t *testing.T) {
	t.Parallel()
	e := newEnv(t, usecase.WithPlatformPushes())
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		t1 := thing{id: "thg_pf", name: c.Name}
		return usecase.NewPlan(e.created(cx, t1),
			usecase.Push("test:thing:thg_pf", "thing.created", map[string]any{}), // written first, then rolled back
			usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{insertErr: errors.New("disk full")})), nil
	}})
	_, err := d.Run(t.Context(), e.cx, cmd{Name: "one"})
	wantError(t, err, usecase.KindInternal, "PERSIST")
	if got := e.outboxPushes(t); len(got) != 0 {
		t.Fatalf("a failed attempt wrote pushes: %+v", got)
	}
}

func TestPlatformPushInThePlatformContextNamesNoClient(t *testing.T) {
	t.Parallel()
	e := newEnv(t, usecase.WithPlatformPushes())
	d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, _ cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
		return usecase.NewPlan(e.created(cx, thing{id: "thg_pl"}),
			usecase.Push("test:announcements", "news", map[string]any{"n": 1})), nil
	}})
	if _, err := d.Run(t.Context(), fw.ContextFor(fw.PlatformClient), cmd{}); err != nil {
		t.Fatal(err)
	}
	got := e.outboxPushes(t)
	if len(got) != 1 || got[0].client != "_platform" || strings.Contains(got[0].payload, "clientCode") {
		t.Fatalf("pushes %+v, want one with no clientCode", got)
	}
}

func TestPushIDAndClientCodeNeedThePlatformDriver(t *testing.T) {
	t.Parallel()
	e := newPushEnv(t) // the hosted driver
	for name, step := range map[string]usecase.Step{
		"id":     usecase.Push("test:thing", "thing.created", map[string]any{}, usecase.PushID("a")),
		"client": usecase.Push("test:thing", "thing.created", map[string]any{}, usecase.PushClientCode("globex")),
	} {
		d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
			t1 := thing{id: "thg_" + c.Name, name: c.Name}
			return usecase.NewPlan(e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}), step), nil
		}})
		_, err := d.Run(t.Context(), e.cx, cmd{Name: "x"})
		if ue, ok := usecase.AsError(err); !ok || ue.Kind() != usecase.KindInternal || ue.Code() != "INVALID_PUSH" {
			t.Errorf("%s: %v, want INTERNAL / INVALID_PUSH", name, err)
		}
	}
	if got := e.pushes(t); len(got) != 0 {
		t.Errorf("refused pushes were written: %+v", got)
	}
}

func TestPlatformPushOptionsAreChecked(t *testing.T) {
	t.Parallel()
	e := newEnv(t, usecase.WithPlatformPushes())
	for name, step := range map[string]usecase.Step{
		"id too long": usecase.Push("test:thing", "thing.created", map[string]any{}, usecase.PushID("0123456789012345678901234567")),
		"id bad char": usecase.Push("test:thing", "thing.created", map[string]any{}, usecase.PushID("a b")),
		"blank code":  usecase.Push("test:thing", "thing.created", map[string]any{}, usecase.PushClientCode("  ")),
		"long group":  usecase.Push("test:thing", "thing.created", map[string]any{}, usecase.PushMessageGroup(strings.Repeat("g", 256))),
	} {
		d := e.define(t, testCase{execute: func(_ context.Context, cx fw.Context, c cmd, _ *database.DB) (*usecase.Plan[thingEvent], error) {
			t1 := thing{id: "thg_" + c.Name, name: c.Name}
			return usecase.NewPlan(e.created(cx, t1), usecase.Insert(usecase.NewFresh(cx, t1), thingRepo{}), step), nil
		}})
		_, err := d.Run(t.Context(), e.cx, cmd{Name: "x"})
		if ue, ok := usecase.AsError(err); !ok || ue.Code() != "INVALID_PUSH" {
			t.Errorf("%s: %v, want INVALID_PUSH", name, err)
		}
	}
}
