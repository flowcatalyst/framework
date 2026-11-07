package flowcatalyst_test

import (
	"context"
	"strconv"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

// The signed-delivery helpers of package fakeplatform (fulfil-go port, item 19), and a dispatch job
// that keeps its client from the use case that wrote it to the use case that receives it (item 16).

const receivers = "http://orders.test/fc"

var signing = fakeplatform.Signing{Secret: testSecret, At: testNow}

func TestTheSignatureIsTheReceiversHMAC(t *testing.T) {
	t.Parallel()
	if got, want := fakeplatform.Signature("s3cret", "1760011200", `{"a":1}`), signature("s3cret", "1760011200", `{"a":1}`); got != want {
		t.Fatalf("signature %s, want %s", got, want)
	}
}

func TestADeliveredJobRunsItsHandlerForItsClient(t *testing.T) {
	type data struct {
		ID string `json:"id"`
	}
	var got recorderOf[string]
	r := startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		return flowcatalyst.OnDispatch(fc.Receivers(), "orders:things:notify",
			func(_ context.Context, cx fw.Context, d flowcatalyst.Delivery[data]) (flowcatalyst.DispatchResult, error) {
				got.add(strings.Join([]string{cx.Client().String(), d.Meta.Client.ID, d.Meta.JobID, d.Meta.CorrelationID.String(), d.Data.ID, strconv.FormatBool(d.Meta.DataOnly)}, "|"))
				return flowcatalyst.Ack{}, nil
			})
	}})
	req, err := fakeplatform.Deliver(t.Context(), receivers, signing, fakeplatform.Job{
		ID: "dj_1", Code: "orders:things:notify", Client: "acme", CorrelationID: "corr-1", Data: `{"id":"thg_1"}`,
	})
	if err != nil {
		t.Fatal(err)
	}
	if res := r.postRequest(req); res.status != 200 || res.body != `{"ack":true}` {
		t.Fatalf("delivery: %d %s", res.status, res.body)
	}
	// a platform-scoped job runs for the platform client; a data-only one still names its client
	for _, j := range []fakeplatform.Job{
		{ID: "dj_2", Code: "orders:things:notify", Data: `{"id":"thg_2"}`},
		{ID: "dj_3", Code: "orders:things:notify", Client: "globex", ClientID: "clt_0GLOBEX", Data: `{"id":"thg_3"}`, DataOnly: true},
	} {
		req, err := fakeplatform.Deliver(t.Context(), receivers, signing, j)
		if err != nil {
			t.Fatal(err)
		}
		if res := r.postRequest(req); res.status != 200 {
			t.Fatalf("%s: %d %s", j.ID, res.status, res.body)
		}
	}
	all := got.get()
	if len(all) != 3 || all[0] != "acme|clt_acme|dj_1|corr-1|thg_1|false" || !strings.HasPrefix(all[1], "_platform||dj_2|") ||
		!strings.HasPrefix(all[2], "globex|clt_0GLOBEX|dj_3|") || !strings.HasSuffix(all[2], "|thg_3|true") {
		t.Fatalf("handled %q", all)
	}

	// signed with another secret, the delivery is refused before any handler runs
	bad, err := fakeplatform.Deliver(t.Context(), receivers, fakeplatform.Signing{Secret: "another", At: testNow}, fakeplatform.Job{ID: "dj_4", Code: "orders:things:notify"})
	if err != nil {
		t.Fatal(err)
	}
	if res := r.postRequest(bad); res.status != 401 || !strings.Contains(res.body, "INVALID_SIGNATURE") {
		t.Fatalf("another secret: %d %s", res.status, res.body)
	}
	if len(got.get()) != 3 {
		t.Fatal("a refused delivery ran its handler")
	}
}

func TestAFiringRunsItsJob(t *testing.T) {
	var got recorderOf[string]
	r := startRig(t, rigOptions{register: func(fc *flowcatalyst.Platform) error {
		return fc.Receivers().OnScheduledJob("orders-sweep", flowcatalyst.JobOptions{AlwaysConcurrent: true},
			func(_ context.Context, cx fw.Context, f *flowcatalyst.Firing) (flowcatalyst.JobResult, error) {
				got.add(strings.Join([]string{cx.Client().String(), f.InstanceID, f.CorrelationID.String(), string(f.Payload)}, "|"))
				return flowcatalyst.Success{}, nil
			})
	}})
	req, err := fakeplatform.Fire(t.Context(), receivers, signing, fakeplatform.Firing{
		JobCode: "orders-sweep", InstanceID: "si_1", Concurrent: true, TracksCompletion: true, Payload: `{"n":1}`,
	})
	if err != nil {
		t.Fatal(err)
	}
	if res := r.postRequest(req); res.status != 202 {
		t.Fatalf("firing: %d %s", res.status, res.body)
	}
	waitFor(t, "the job", func() bool { return len(got.get()) == 1 })
	if all := got.get(); all[0] != `_platform|si_1|corr-si_1|{"n":1}` {
		t.Fatalf("ran %q", all)
	}
	waitFor(t, "the completion", func() bool { return len(r.platform.calls("complete")) == 1 })
}

// ---- a job keeps its client: written in client C's context, received in C's -----------------------

type notifyThing struct {
	events *event.Factory
	seen   *recorderOf[string] // the client of each run's Context
	job    bool                // plan the dispatch job
}

func (notifyThing) Name() usecase.Name { return usecase.MustName("notify-thing") }

func (notifyThing) Validate(context.Context, fw.Context, paidCmd) error { return nil }

func (notifyThing) Authorize(context.Context, fw.Context, usecase.Valid[paidCmd]) (usecase.Grant, error) {
	return usecase.Public(), nil
}

func (u notifyThing) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[paidCmd], _ *database.DB) (*usecase.Plan[paidEvent], error) {
	u.seen.add(cx.Client().String())
	p := paid{id: a.Command().ID}
	plan := usecase.NewPlan(paidEvent{Meta: u.events.Metadata(cx, event.MustType("orders:things:thing:noted"), p.id), ID: p.id},
		usecase.Insert(usecase.NewFresh(cx, p), paidRepo{}))
	if u.job {
		job, err := usecase.NewDispatchJob("orders:things:notify", "https://orders.test/fc/dispatch", usecase.JobID("dj_"+p.id),
			usecase.JSONPayload(map[string]string{"id": "recv-" + p.id}))
		if err != nil {
			return nil, err
		}
		plan.Dispatch(job)
	}
	return plan, nil
}

func TestAJobWrittenInAClientsContextIsReceivedInThatClientsContext(t *testing.T) {
	var outbox *usecase.Declared
	var boot *fw.Boot
	var receive *usecase.Defined[paidCmd, paidEvent]
	created, received := &recorderOf[string]{}, &recorderOf[string]{}
	r := startRig(t, rigOptions{
		dbURL: fwtest.NewDatabase(t).URL, migrate: true,
		declare:   func(app *fw.App) { outbox = usecase.Declare(app) },
		buildOpts: func(b *fw.Boot) []flowcatalyst.BuildOption { boot = b; return nil },
		register: func(fc *flowcatalyst.Platform) error {
			return flowcatalyst.OnDispatch(fc.Receivers(), "orders:things:notify",
				func(ctx context.Context, cx fw.Context, d flowcatalyst.Delivery[paidCmd]) (flowcatalyst.DispatchResult, error) {
					_, err := receive.Run(ctx, cx, d.Data)
					return flowcatalyst.Ack{}, err
				})
		},
	})
	db := r.db()
	if _, err := db.Pool().Exec(t.Context(), "CREATE TABLE paid (id text PRIMARY KEY, client_id text NOT NULL, version bigint NOT NULL)"); err != nil {
		t.Fatal(err)
	}
	runner, err := usecase.NewRunner(boot, outbox, db)
	if err != nil {
		t.Fatal(err)
	}
	events := event.NewFactory(boot.Clock(), boot.Ids())
	create, err := usecase.Define(runner, notifyThing{events: events, seen: created, job: true})
	if err != nil {
		t.Fatal(err)
	}
	if receive, err = usecase.Define(runner, notifyThing{events: events, seen: received}); err != nil {
		t.Fatal(err)
	}

	// a person of client acme creates the thing; the plan writes the dispatch job
	if _, err := create.Run(t.Context(), fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("usr_1")), paidCmd{ID: "thg_1"}); err != nil {
		t.Fatal(err)
	}
	var payload string
	if err := db.Pool().QueryRow(t.Context(), "SELECT payload FROM outbox_messages WHERE type = 'DISPATCH_JOB'").Scan(&payload); err != nil {
		t.Fatal(err)
	}

	// the platform delivers the job it was sent
	job, err := fakeplatform.JobFromOutbox(payload)
	if err != nil {
		t.Fatal(err)
	}
	req, err := fakeplatform.Deliver(t.Context(), receivers, signing, job)
	if err != nil {
		t.Fatal(err)
	}
	if res := r.postRequest(req); res.status != 200 {
		t.Fatalf("delivery: %d %s", res.status, res.body)
	}

	if c, rcv := created.get(), received.get(); len(c) != 1 || c[0] != "acme" || len(rcv) != 1 || rcv[0] != "acme" {
		t.Fatalf("created in %v, received in %v: the receiving use case must run for acme", c, rcv)
	}
	var client string
	if err := db.Pool().QueryRow(t.Context(), "SELECT client_id FROM paid WHERE id = 'recv-thg_1'").Scan(&client); err != nil {
		t.Fatal(err)
	}
	if client != "acme" {
		t.Errorf("the received row is %s's, want acme's", client)
	}
}

func TestTheJobsOfOutboxRowsNameTheirClient(t *testing.T) {
	t.Parallel()
	for name, c := range map[string]struct {
		build            func() (fakeplatform.Job, error)
		client, clientID string
	}{
		"an event of a client": {func() (fakeplatform.Job, error) {
			return fakeplatform.JobFromEvent("dj_1", `{"type":"orders:sales:order:placed","data":"{}","clientCode":"acme"}`)
		}, "acme", ""},
		"an event of the platform client": {func() (fakeplatform.Job, error) {
			return fakeplatform.JobFromEvent("dj_2", `{"type":"orders:sales:order:placed","data":"{}","clientCode":"_platform"}`)
		}, "", ""},
		"a job naming its client by code": {func() (fakeplatform.Job, error) {
			return fakeplatform.JobFromOutbox(`{"id":"dj_3","code":"orders:x","payload":"{}","clientCode":"acme"}`)
		}, "acme", ""},
		"a job naming its client by id only": {func() (fakeplatform.Job, error) {
			return fakeplatform.JobFromOutbox(`{"id":"dj_4","code":"orders:x","payload":"{}","clientId":"clt_0ACME"}`)
		}, "", "clt_0ACME"},
	} {
		j, err := c.build()
		if err != nil || j.Client != c.client || j.ClientID != c.clientID {
			t.Errorf("%s: %+v %v", name, j, err)
		}
	}
}
