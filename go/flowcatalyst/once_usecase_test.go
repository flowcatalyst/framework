package flowcatalyst_test

import (
	"context"
	"errors"
	"testing"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

// A use case run inside flowcatalyst.Once's transaction (write-path contract 4a, G7.4): the
// delivery's idempotency record, the use case's writes, the events and the audit row are one
// transaction, committed or rolled back together.

type paidCmd struct{ ID string }

type paid struct{ id string }

func (p paid) AuditEntity() usecase.Entity { return usecase.Entity{Type: "Paid", ID: p.id} }

type paidEvent struct {
	Meta event.Metadata `json:"-"`
	ID   string         `json:"id"`
}

func (e paidEvent) Metadata() event.Metadata { return e.Meta }

type paidRepo struct{}

func (paidRepo) Find(context.Context, *database.DB, fw.ClientID, string) (usecase.Loaded[paid], bool, error) {
	return usecase.Loaded[paid]{}, false, nil
}

func (paidRepo) Insert(ctx context.Context, tx *database.Tx, client fw.ClientID, p paid) error {
	_, err := tx.Conn().Exec(ctx, "INSERT INTO paid (id, client_id, version) VALUES ($1, $2, 1)", p.id, client.String())
	return err
}

func (paidRepo) Update(context.Context, *database.Tx, fw.ClientID, paid, usecase.VersionCheck) (int64, error) {
	return 0, nil
}

func (paidRepo) Delete(context.Context, *database.Tx, fw.ClientID, paid, usecase.VersionCheck) (int64, error) {
	return 0, nil
}

type markPaid struct{ events *event.Factory }

func (markPaid) Name() usecase.Name { return usecase.MustName("mark-paid") }

func (markPaid) Validate(context.Context, fw.Context, paidCmd) error { return nil }

func (markPaid) Authorize(context.Context, fw.Context, usecase.Valid[paidCmd]) (usecase.Grant, error) {
	return usecase.Public(), nil
}

func (u markPaid) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[paidCmd], _ *database.DB) (*usecase.Plan[paidEvent], error) {
	p := paid{id: a.Command().ID}
	ev := paidEvent{Meta: u.events.Metadata(cx, event.MustType("orders:billing:invoice:paid"), p.id), ID: p.id}
	return usecase.NewPlan(ev, usecase.Insert(usecase.NewFresh(cx, p), paidRepo{})), nil
}

type onceUseCaseRig struct {
	db *database.DB
	d  *usecase.Defined[paidCmd, paidEvent]
	cx fw.Context
}

func newOnceUseCaseRig(t *testing.T) onceUseCaseRig {
	t.Helper()
	var outbox *usecase.Declared
	var boot *fw.Boot
	r := startRig(t, rigOptions{
		dbURL: fwtest.NewDatabase(t).URL, migrate: true,
		declare:   func(app *fw.App) { outbox = usecase.Declare(app) },
		buildOpts: func(b *fw.Boot) []flowcatalyst.BuildOption { boot = b; return nil },
	})
	db := r.db()
	if _, err := db.Pool().Exec(t.Context(), "CREATE TABLE paid (id text PRIMARY KEY, client_id text NOT NULL, version bigint NOT NULL)"); err != nil {
		t.Fatal(err)
	}
	runner, err := usecase.NewRunner(boot, outbox, db)
	if err != nil {
		t.Fatal(err)
	}
	d, err := usecase.Define(runner, markPaid{events: event.NewFactory(boot.Clock(), boot.Ids())})
	if err != nil {
		t.Fatal(err)
	}
	cx := fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("usr_1"))
	return onceUseCaseRig{db: db, d: d, cx: cx}
}

func TestAUseCaseJoinsOnceAndCommitsWithTheRecord(t *testing.T) {
	r := newOnceUseCaseRig(t)
	res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db, "mark-paid", func(ctx context.Context, tx *database.Tx) (string, error) {
		done, err := r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_1"}, tx)
		if err != nil {
			return "", err
		}
		return done.Event().ID, nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if v, ran := res.Value(); !ran || v != "inv_1" {
		t.Fatalf("result %+v", res)
	}
	for table, want := range map[string]int{"paid": 1, "fw_dispatch_once": 1, "outbox_messages": 2} { // the event and the audit row
		if got := count(t, r.db, table); got != want {
			t.Errorf("%s: %d rows, want %d", table, got, want)
		}
	}
	// the redelivery is skipped: the use case does not run again
	again, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db, "mark-paid", func(ctx context.Context, tx *database.Tx) (string, error) {
		_, err := r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_1"}, tx)
		return "", err
	})
	if err != nil || again.Ran() {
		t.Fatalf("the repeat ran: %+v %v", again, err)
	}
	if got := count(t, r.db, "outbox_messages"); got != 2 {
		t.Errorf("outbox rows %d after the repeat, want 2", got)
	}
}

func TestAUseCaseJoinedToOnceIsRolledBackWithTheRecordWhenTheWorkFails(t *testing.T) {
	r := newOnceUseCaseRig(t)
	boom := errors.New("the work failed after the use case committed in the transaction")
	_, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db, "mark-paid", func(ctx context.Context, tx *database.Tx) (int, error) {
		if _, err := r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_1"}, tx); err != nil {
			return 0, err
		}
		return 0, boom
	})
	if !errors.Is(err, boom) {
		t.Fatalf("err %v", err)
	}
	for _, table := range []string{"paid", "fw_dispatch_once", "outbox_messages"} {
		if got := count(t, r.db, table); got != 0 {
			t.Errorf("%s: %d rows survived the rollback", table, got)
		}
	}
	// the redelivery runs it again
	res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db, "mark-paid", func(ctx context.Context, tx *database.Tx) (int, error) {
		_, err := r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_1"}, tx)
		return 1, err
	})
	if err != nil || !res.Ran() || count(t, r.db, "paid") != 1 {
		t.Fatalf("redelivery: %+v %v", res, err)
	}
}

func TestAFailedJoinedUseCaseLeavesTheOnceTransactionUsable(t *testing.T) {
	r := newOnceUseCaseRig(t)
	res, err := flowcatalyst.Once(t.Context(), deliveryMeta("dj_1"), r.db, "mark-paid", func(ctx context.Context, tx *database.Tx) (string, error) {
		if _, err := r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_1"}, tx); err != nil {
			return "", err
		}
		// the same id again: a unique violation, rolled back to the savepoint
		_, err := r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_1"}, tx)
		ue, ok := usecase.AsError(err)
		if !ok || ue.Code() != "UNIQUE_VIOLATION" {
			return "", errors.New("want UNIQUE_VIOLATION, got " + errText(err))
		}
		// the transaction is still usable
		_, err = r.d.RunJoined(ctx, r.cx, paidCmd{ID: "inv_2"}, tx)
		return "ok", err
	})
	if err != nil || !res.Ran() {
		t.Fatalf("%+v %v", res, err)
	}
	if got := count(t, r.db, "paid"); got != 2 {
		t.Errorf("paid rows %d, want 2 (inv_1 and inv_2)", got)
	}
	if got := count(t, r.db, "outbox_messages"); got != 4 {
		t.Errorf("outbox rows %d, want 4 (two events, two audit rows)", got)
	}
}

func errText(err error) string {
	if err == nil {
		return "no error"
	}
	return err.Error()
}
