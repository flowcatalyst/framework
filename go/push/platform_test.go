package push

import (
	"context"
	"crypto/ed25519"
	"encoding/json/v2"
	"errors"
	"log/slog"
	"maps"
	"strings"
	"testing"
	"time"

	"github.com/jackc/pgx/v5"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/usecase"
)

// push.driver=platform (push contract amendment 17.17): pushes are PUSH rows of the application's
// outbox, the module serves no routes and runs no loops, tickets are minted as before.

var platformEnv = map[string]string{"PUSH_DRIVER": "platform", "PUSH_STREAM_SECRET": ""}

// platformDatabase is a database with the application's outbox table.
func platformDatabase(t *testing.T) string {
	t.Helper()
	url := fwtest.NewDatabase(t).URL
	db, err := database.Open(t.Context(), "main", url)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err := database.ApplyMigrations(t.Context(), db, []fw.MigrationSet{{Module: usecase.OutboxModuleID, Files: usecase.OutboxMigrations()}},
		slog.New(slog.DiscardHandler)); err != nil {
		t.Fatal(err)
	}
	return url
}

func platformNode(t *testing.T, extra ...map[string]string) (*node, string) {
	t.Helper()
	url := platformDatabase(t)
	env := map[string]string{}
	maps.Copy(env, platformEnv)
	for _, m := range extra {
		maps.Copy(env, m)
	}
	return startNode(t, url, nodeConfig{env: env}), url
}

type outboxRow struct {
	id, group, client, payload string
	hasGroup                   bool
}

func outboxPushes(t *testing.T, n *node) []outboxRow {
	t.Helper()
	rows, err := n.db.Pool().Query(t.Context(), "SELECT id, message_group, client_id, payload FROM outbox_messages WHERE type = 'PUSH' ORDER BY ctid")
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []outboxRow
	for rows.Next() {
		var r outboxRow
		var g *string
		if err := rows.Scan(&r.id, &g, &r.client, &r.payload); err != nil {
			t.Fatal(err)
		}
		if g != nil {
			r.group, r.hasGroup = *g, true
		}
		out = append(out, r)
	}
	return out
}

func countRows(t *testing.T, n *node, table string) int {
	t.Helper()
	var c int
	if err := n.db.Pool().QueryRow(t.Context(), "SELECT count(*) FROM "+table).Scan(&c); err != nil {
		t.Fatal(err)
	}
	return c
}

func TestPlatformDriverPublishWritesAnOutboxRowNotTheLocalOutbox(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	if !n.p.UsesPlatform() {
		t.Fatal("UsesPlatform is false")
	}
	p, err := New("test:order:ord_1", "order.updated", map[string]any{"status": "paid", "lines": 2})
	if err != nil {
		t.Fatal(err)
	}
	if err := n.p.PublishAlone(t.Context(), fw.ContextFor(fw.MustClientID("acme")), p); err != nil {
		t.Fatal(err)
	}
	got := outboxPushes(t, n)
	if len(got) != 1 {
		t.Fatalf("rows %+v", got)
	}
	r := got[0]
	want := `{"topic":"test:order:ord_1","type":"order.updated","data":{"lines":2,"status":"paid"},"clientCode":"acme","pushId":"` + r.id + `"}`
	if r.payload != want || !r.hasGroup || r.group != "test:order:ord_1" || r.client != "acme" || !fw.CanonicalTSID(r.id) {
		t.Errorf("row %+v\nwant payload %s", r, want)
	}
	if c := countRows(t, n, "fw_push_outbox"); c != 0 {
		t.Errorf("fw_push_outbox has %d rows, want 0", c)
	}
}

func TestPlatformDriverPublishIsInTheCallersTransaction(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	cx := fw.ContextFor(fw.MustClientID("acme"))
	p, _ := New("test:board", "refresh", map[string]any{})
	rollback := errors.New("rolled back")
	err := pgx.BeginFunc(t.Context(), n.db.Pool(), func(tx pgx.Tx) error {
		if err := n.p.Publish(t.Context(), tx, cx, p); err != nil {
			return err
		}
		return rollback
	})
	if !errors.Is(err, rollback) {
		t.Fatal(err)
	}
	if got := outboxPushes(t, n); len(got) != 0 {
		t.Fatalf("a rolled-back transaction left rows: %+v", got)
	}
	if err := pgx.BeginFunc(t.Context(), n.db.Pool(), func(tx pgx.Tx) error { return n.p.Publish(t.Context(), tx, cx, p) }); err != nil {
		t.Fatal(err)
	}
	if got := outboxPushes(t, n); len(got) != 1 {
		t.Fatalf("a committed transaction wrote %d rows, want 1", len(got))
	}
}

func TestPlatformDriverNotifiesTheOutboxProcessorOnCommitOnly(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	conn, err := n.db.Pool().Acquire(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Release()
	if _, err := conn.Exec(t.Context(), "LISTEN fc_outbox_push"); err != nil {
		t.Fatal(err)
	}
	cx := fw.ContextFor(fw.MustClientID("acme"))
	p, _ := New("test:board", "refresh", map[string]any{})
	_ = pgx.BeginFunc(t.Context(), n.db.Pool(), func(tx pgx.Tx) error {
		if err := n.p.Publish(t.Context(), tx, cx, p); err != nil {
			t.Fatal(err)
		}
		return errors.New("rollback")
	})
	short, cancel := context.WithTimeout(t.Context(), 300*time.Millisecond)
	defer cancel()
	if _, err := conn.Conn().WaitForNotification(short); err == nil {
		t.Fatal("a rolled-back publish notified")
	}
	if err := n.p.PublishAlone(t.Context(), cx, p); err != nil {
		t.Fatal(err)
	}
	wait, stop := context.WithTimeout(t.Context(), 5*time.Second)
	defer stop()
	note, err := conn.Conn().WaitForNotification(wait)
	if err != nil || note.Channel != "fc_outbox_push" {
		t.Fatalf("notification %+v, %v", note, err)
	}
}

func TestPlatformDriverPublishBatchKeepsOrderAndEachItemsClient(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	mk := func(topic string) Push {
		p, err := New(topic, "refresh", map[string]any{})
		if err != nil {
			t.Fatal(err)
		}
		return p
	}
	err := n.p.PublishBatchAlone(t.Context(),
		Publication{Context: fw.ContextFor(fw.MustClientID("acme")), Push: mk("test:a")},
		Publication{Context: fw.ContextFor(fw.PlatformClient), Push: mk("test:b")},
		Publication{Context: fw.ContextFor(fw.MustClientID("globex")), Push: mk("test:c")})
	if err != nil {
		t.Fatal(err)
	}
	got := outboxPushes(t, n)
	if len(got) != 3 {
		t.Fatalf("rows %+v", got)
	}
	for i, w := range []struct{ topic, client string }{{"test:a", "acme"}, {"test:b", "_platform"}, {"test:c", "globex"}} {
		if got[i].group != w.topic || got[i].client != w.client {
			t.Errorf("row %d: %+v, want topic %s client %s", i, got[i], w.topic, w.client)
		}
		if strings.Contains(got[i].payload, "clientCode") != (w.client != "_platform") {
			t.Errorf("row %d payload %s: clientCode must be present unless the platform client", i, got[i].payload)
		}
	}
}

func TestPlatformDriverRefusesDataOverTheLimitAndWritesNothing(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t, map[string]string{"PUSH_MAX_DATA_BYTES": "20"})
	p, err := New("test:board", "refresh", map[string]any{"text": strings.Repeat("x", 30)})
	if err != nil {
		t.Fatal(err)
	}
	err = n.p.PublishAlone(t.Context(), fw.ContextFor(fw.MustClientID("acme")), p)
	var ip *InvalidPushError
	if !errors.As(err, &ip) || ip.Reason != "DATA_TOO_LARGE" {
		t.Fatalf("error %v", err)
	}
	if got := outboxPushes(t, n); len(got) != 0 {
		t.Fatalf("rows %+v", got)
	}
}

func TestPlatformDriverServesNoRoutesAndHasNoCursor(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	for _, c := range []struct{ method, path string }{
		{"POST", "/push/subscriptions"}, {"GET", "/push/stream"}, {"GET", "/push/keys"},
	} {
		if a := n.call(c.method, c.path, ""); a.status != 404 {
			t.Errorf("%s %s: %d, want 404 (no routes)", c.method, c.path, a.status)
		}
	}
	if n.p.HasCursor() || n.p.Cursor() != "" {
		t.Errorf("Cursor %q, HasCursor %v", n.p.Cursor(), n.p.HasCursor())
	}
	if err := n.p.EndPrincipal(t.Context(), "prn_1"); !errors.Is(err, ErrPlatformDriver) {
		t.Errorf("EndPrincipal: %v, want ErrPlatformDriver", err)
	}
	n.p.Drain() // nothing to end, and no panic
	if h, err := n.p.Handler(); err != nil || h == nil {
		t.Errorf("Handler: %v, %v", h, err)
	}
}

func TestPlatformDriverRunsNoRelayOrRetentionLoop(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	// a row in the local outbox is never moved: there is no relay
	if _, err := n.db.Pool().Exec(t.Context(), "INSERT INTO fw_push_outbox (topic, type, client, data, created_at) VALUES ('test:x', 'x', NULL, '{}', now())"); err != nil {
		t.Fatal(err)
	}
	time.Sleep(400 * time.Millisecond) // eight relay intervals (PUSH_RELAY_INTERVAL is 50ms)
	if c := countRows(t, n, "fw_push_log"); c != 0 {
		t.Errorf("fw_push_log has %d rows: a relay ran", c)
	}
}

func TestPlatformDriverMintsTheSameTicketsAsAHostedOne(t *testing.T) {
	t.Parallel()
	platform, _ := platformNode(t)
	hosted := startNode(t, fwtest.NewDatabase(t).URL, nodeConfig{})
	cx := fw.ContextFor(fw.MustClientID("acme")).WithPrincipal(fw.MustPrincipalID("prn_1"))
	a, err := platform.p.Ticket(cx, "test:order:{id}")
	if err != nil {
		t.Fatal(err)
	}
	b, err := hosted.p.Ticket(cx, "test:order:{id}")
	if err != nil {
		t.Fatal(err)
	}
	if a != b { // Ed25519 is deterministic and both clocks start at begin
		t.Errorf("tickets differ:\n%s\n%s", a, b)
	}
	if _, err := platform.p.TicketWith(cx, TicketOptions{ProofKey: "jkt"}, "test:order:{id}"); err != nil {
		t.Errorf("TicketWith: %v", err)
	}
}

func TestPlatformDriverGatewaySyncBody(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	body, err := json.Marshal(n.p.GatewaySync())
	if err != nil {
		t.Fatal(err)
	}
	var got struct {
		Rules []map[string]any `json:"rules"`
		Keys  struct {
			Keys []map[string]string `json:"keys"`
		} `json:"keys"`
	}
	if err := json.Unmarshal(body, &got); err != nil {
		t.Fatal(err)
	}
	wantRules := `[{"pattern":"test:order:{id}","requires":["test:order:view"]},{"pattern":"test:user:{principal}"},` +
		`{"pattern":"test:client:{client}:news","requires":["test:news:view"]},{"pattern":"test:public","public":true}]`
	rules, _ := json.Marshal(got.Rules, json.Deterministic(true))
	var wantList []map[string]any
	if err := json.Unmarshal([]byte(wantRules), &wantList); err != nil {
		t.Fatal(err)
	}
	wantJSON, _ := json.Marshal(wantList, json.Deterministic(true))
	if string(rules) != string(wantJSON) {
		t.Errorf("rules\n got %s\nwant %s", rules, wantJSON)
	}
	if len(got.Keys.Keys) != 2 || got.Keys.Keys[0]["kid"] != "test-2026-a" || got.Keys.Keys[1]["kid"] != "test-2025-z" ||
		got.Keys.Keys[0]["kty"] != "OKP" || got.Keys.Keys[0]["crv"] != "Ed25519" || got.Keys.Keys[0]["x"] == "" {
		t.Errorf("keys %+v", got.Keys.Keys)
	}
	if strings.Contains(string(body), "\"d\"") {
		t.Error("the sync body holds a private key member")
	}
}

func TestPlatformDriverConfiguration(t *testing.T) {
	t.Parallel()
	url := platformDatabase(t)
	run := func(env map[string]string, opts ...DeclareOption) error {
		_, err := tryNode(t, url, nodeConfig{env: env, declareOpts: opts})
		return err
	}
	// the stream secret is not needed ... and harmless when set (a deployment that moves between hosting and the gateway)
	if err := run(map[string]string{"PUSH_DRIVER": "platform", "PUSH_STREAM_SECRET": ""}); err != nil {
		t.Errorf("platform without a stream secret: %v", err)
	}
	if err := run(map[string]string{"PUSH_DRIVER": "platform"}); err != nil {
		t.Errorf("platform with a stream secret: %v", err)
	}
	// ... but the ticket key is
	err := run(map[string]string{"PUSH_DRIVER": "platform", "PUSH_TICKET_KEY": ""}, OptionalTicketKey())
	if err == nil || !strings.Contains(err.Error(), "push.ticket.key") {
		t.Errorf("platform without a ticket key: %v", err)
	}
	err = run(map[string]string{"PUSH_DRIVER": "platform", "PUSH_TICKET_KEY": "nonsense"})
	if err == nil || !strings.Contains(err.Error(), "push.ticket.key") {
		t.Errorf("platform with a bad ticket key: %v", err)
	}
	// a hosted driver still needs the secret
	err = run(map[string]string{"PUSH_STREAM_SECRET": ""})
	if err == nil || !strings.Contains(err.Error(), "push.stream.secret") {
		t.Errorf("postgres without a stream secret: %v", err)
	}
	err = run(map[string]string{"PUSH_STREAM_SECRET": "short"})
	if err == nil || !strings.Contains(err.Error(), "push.stream.secret") {
		t.Errorf("postgres with a short stream secret: %v", err)
	}
	// an unknown driver is still refused
	if err := run(map[string]string{"PUSH_DRIVER": "kafka"}); err == nil {
		t.Error("an unknown driver started")
	}
}

func TestPlatformDriverIsNotForAMultiApplicationHost(t *testing.T) {
	t.Parallel()
	url := platformDatabase(t)
	_, err := tryNode(t, url, nodeConfig{
		env:         map[string]string{"PUSH_DRIVER": "platform"},
		declareOpts: []DeclareOption{OptionalTicketKey()},
		declare:     func(*Declared) {},
		opts:        []Option{MultiApplication(RuleProviderFunc(func(string) []Rule { return nil }), TicketKeyResolverFunc(func(string, string) (ed25519.PublicKey, bool) { return nil, false }))},
	})
	if err == nil || !strings.Contains(err.Error(), "push.driver") {
		t.Fatalf("a multi-application host with the platform driver: %v", err)
	}
}

func TestRunnerOptionFollowsTheDriver(t *testing.T) {
	t.Parallel()
	n, _ := platformNode(t)
	if n.p.RunnerOption() == nil {
		t.Fatal("no runner option")
	}
}
