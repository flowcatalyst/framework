package main

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/domain"
	"github.com/flowcatalyst/framework/go/examples/orders-demo/orders"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

// started runs the demo application against a fresh database and returns what build made.
type started struct {
	out    *bytes.Buffer
	boot   *fw.Boot
	runner *usecase.Runner
	db     *database.DB
	run    *fw.Running
}

func start(t *testing.T, url string, client fw.ClientID) *started {
	t.Helper()
	s := &started{out: &bytes.Buffer{}}
	env := map[string]string{
		"APP_ENV":                          "test",
		"DATABASE_INSTANCES_MAIN_URL":      url,
		"DATABASE_INSTANCES_MAIN_USERNAME": "postgres",
		"DATABASE_INSTANCES_MAIN_PASSWORD": "postgres",
	}
	app := newApp(fw.WithEnvironment(env), fw.WithConfigDir("config"), fw.WithOutput(&bytes.Buffer{}, &bytes.Buffer{}))
	d := declareScenario(app)
	r, err := app.Start(t.Context(), build(d, deps{out: s.out, client: client, built: func(b *fw.Boot, r *usecase.Runner, db *database.DB) {
		s.boot, s.runner, s.db = b, r, db
	}}))
	if err != nil {
		t.Fatal(err)
	}
	s.run = r
	select {
	case <-r.Done():
	case <-time.After(60 * time.Second):
		t.Fatal("the scenario did not finish")
	}
	t.Cleanup(func() { _ = r.Shutdown() })
	return s
}

var orderLine = regexp.MustCompile(`^order ord_[0-9A-Z]{13} CANCELLED v4: 2 lines, 121500 cents$`)

func TestTheJavaDemosScenario(t *testing.T) {
	t.Parallel()
	s := start(t, fwtest.NewDatabase(t).URL, fw.MustClientID("demo"))
	lines := strings.Split(strings.TrimSpace(s.out.String()), "\n")
	want := []string{
		"place: committed orders:sales:order:placed",
		"add: committed orders:sales:order:line-added",
		"add: committed orders:sales:order:line-added",
		"cancel: committed orders:sales:order:cancelled",
		"add after cancel: BUSINESS_RULE ORDER_NOT_OPEN",
	}
	if len(lines) != len(want)+1 {
		t.Fatalf("report:\n%s", s.out)
	}
	for i, w := range want {
		if lines[i] != w {
			t.Errorf("line %d: %q, want %q", i, lines[i], w)
		}
	}
	if !orderLine.MatchString(lines[len(want)]) {
		t.Errorf("summary %q", lines[len(want)])
	}

	// The outbox holds what the Java demo writes: 4 events, each with its audit row.
	rows, err := s.db.Pool().Query(t.Context(), "SELECT type, payload FROM outbox_messages ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var types []string
	var placedPayload, placeAudit string
	for rows.Next() {
		var typ, payload string
		if err := rows.Scan(&typ, &payload); err != nil {
			t.Fatal(err)
		}
		types = append(types, typ)
		if len(types) == 1 {
			placedPayload = payload
		}
		if len(types) == 2 {
			placeAudit = payload
		}
	}
	if strings.Join(types, ",") != "EVENT,AUDIT_LOG,EVENT,AUDIT_LOG,EVENT,AUDIT_LOG,EVENT,AUDIT_LOG" {
		t.Errorf("outbox rows %v", types)
	}
	var p struct {
		Type, Source, Subject, Data, MessageGroup, ClientCode string
		ContextData                                           []struct{ Key, Value string }
	}
	if err := json.Unmarshal([]byte(placedPayload), &p, json.MatchCaseInsensitiveNames(true)); err != nil {
		t.Fatal(err)
	}
	if p.Type != "orders:sales:order:placed" || p.Source != "orders:sales" || !strings.HasPrefix(p.Subject, "sales.order.ord_") ||
		!strings.HasPrefix(p.MessageGroup, "sales:order:ord_") || p.ClientCode != "demo" ||
		!regexp.MustCompile(`^\{"orderId":"ord_[0-9A-Z]{13}","customer":"Ada Lovelace"\}$`).MatchString(p.Data) ||
		len(p.ContextData) != 1 || p.ContextData[0].Value != "usr_demo" {
		t.Errorf("placed payload %s", placedPayload)
	}
	if !strings.Contains(placeAudit, `"entityType":"Order"`) || !strings.Contains(placeAudit, `"operation":"place-order"`) ||
		!strings.Contains(placeAudit, `"operationData":"{\"customer\":\"Ada Lovelace\"}"`) {
		t.Errorf("place-order audit %s", placeAudit)
	}
}

// gated pauses the first Find until the test lets it go, so another use case can commit in between
// (the Java demo's GatedRepository).
type gated struct {
	orders.Repository
	once    sync.Once
	loaded  chan struct{}
	proceed chan struct{}
}

func newGated() *gated { return &gated{loaded: make(chan struct{}), proceed: make(chan struct{})} }

func (g *gated) Find(ctx context.Context, db *database.DB, client fw.ClientID, orderID domain.OrderID) (usecase.Loaded[domain.Order], bool, error) {
	l, ok, err := g.Repository.Find(ctx, db, client, orderID)
	first := false
	g.once.Do(func() { first = true })
	if first {
		close(g.loaded)
		select {
		case <-g.proceed:
		case <-time.After(20 * time.Second):
			return l, ok, errors.New("the test never released the first attempt")
		}
	}
	return l, ok, err
}

func TestConcurrencyUnderTheConflictPolicies(t *testing.T) {
	t.Parallel()
	s := start(t, fwtest.NewDatabase(t).URL, fw.MustClientID("demo"))
	events := event.NewFactory(s.boot.Clock(), s.boot.Ids())
	normal, err := orders.NewService(s.runner, s.boot.Ids(), events)
	if err != nil {
		t.Fatal(err)
	}
	acme := fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("u"))
	place := func() domain.OrderID {
		c, _ := domain.ParseCustomerName("Acme")
		done, err := normal.PlaceOrder.Run(t.Context(), acme, orders.PlaceOrder{Customer: c})
		if err != nil {
			t.Fatal(err)
		}
		return done.Event().OrderID
	}
	add := func(svc *orders.Service, o domain.OrderID, sku string) (usecase.Committed[orders.OrderLineAdded], error) {
		cmd, err := orders.ParseAddLine(o, sku, 1, 10)
		if err != nil {
			t.Fatal(err)
		}
		return svc.AddLine.Run(t.Context(), acme, cmd)
	}
	state := func(o domain.OrderID) string {
		var v int64
		var status, skus string
		err := s.db.Pool().QueryRow(t.Context(), `SELECT version, status, coalesce((SELECT string_agg(sku, ',' ORDER BY position)
			FROM order_lines WHERE order_id = $1), '') FROM orders WHERE id = $1`, o).Scan(&v, &status, &skus)
		if err != nil {
			t.Fatal(err)
		}
		return fmt.Sprintf("%d:%s:%s", v, status, skus)
	}
	retry, _ := usecase.Retry(3, 10*time.Millisecond)

	t.Run("two concurrent line additions both land under retry", func(t *testing.T) {
		o := place()
		if _, err := add(normal, o, "FIRST"); err != nil {
			t.Fatal(err)
		}
		g := newGated()
		slow, err := orders.NewServiceWith(s.runner, s.boot.Ids(), events, g, orders.Policies{AddLine: retry, Cancel: usecase.Fail()})
		if err != nil {
			t.Fatal(err)
		}
		type result struct {
			c   usecase.Committed[orders.OrderLineAdded]
			err error
		}
		done := make(chan result, 1)
		go func() {
			c, err := add(slow, o, "FROM-A")
			done <- result{c, err}
		}()
		<-g.loaded
		if _, err := add(normal, o, "FROM-B"); err != nil {
			t.Fatal(err)
		}
		close(g.proceed)
		r := <-done
		if r.err != nil || r.c.Event().LineCount != 3 {
			t.Fatalf("slow add: %v, line count %d", r.err, r.c.Event().LineCount)
		}
		if got := state(o); got != "4:OPEN:FIRST,FROM-B,FROM-A" {
			t.Errorf("order %s", got)
		}
	})
	t.Run("without retry the stale attempt fails and writes nothing", func(t *testing.T) {
		o := place()
		g := newGated()
		slow, err := orders.NewServiceWith(s.runner, s.boot.Ids(), events, g, orders.Policies{AddLine: usecase.Fail(), Cancel: usecase.Fail()})
		if err != nil {
			t.Fatal(err)
		}
		done := make(chan error, 1)
		go func() {
			_, err := add(slow, o, "FROM-A")
			done <- err
		}()
		<-g.loaded
		if _, err := add(normal, o, "FROM-B"); err != nil {
			t.Fatal(err)
		}
		var outbox int
		_ = s.db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM outbox_messages").Scan(&outbox)
		close(g.proceed)
		err = <-done
		if ue, ok := usecase.AsError(err); !ok || ue.Code() != "STALE_AGGREGATE" {
			t.Fatalf("err %v, want STALE_AGGREGATE", err)
		}
		var after int
		_ = s.db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM outbox_messages").Scan(&after)
		if got := state(o); got != "2:OPEN:FROM-B" || after != outbox {
			t.Errorf("order %s, outbox rows %d -> %d", got, outbox, after)
		}
	})
}
