package flowcatalyst_test

import (
	"encoding/json/v2"
	"errors"
	"maps"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/push"
)

// With push.driver=platform the module also syncs the gateway's topic rules and the public ticket
// keys: POST /api/applications/{app}/push/sync, category PUSH after the definitions (push contract
// amendment 17.17).

// A TEST key (conformance/push/tickets.json): never use it for anything real.
const pushTestKey = "test-2026-a:5BgJptHbkz4uIeRqgLz3Ws_38Ovwi1uCpSdyW45yMdc"

func pushRig(t *testing.T, o rigOptions) *rig {
	t.Helper()
	var pd *push.Declared
	o.declare = func(app *fw.App) {
		pd = push.Declare(app)
		pd.Topic("orders:order:{id}", push.Requires("orders:order:view"))
		pd.Topic("orders:user:{principal}")
		pd.PublicTopic("orders:public")
	}
	env := map[string]string{"PUSH_DRIVER": "platform", "PUSH_TICKET_KEY": pushTestKey}
	maps.Copy(env, o.env)
	o.env = env
	o.buildOpts = func(b *fw.Boot) []flowcatalyst.BuildOption {
		db, err := database.Open(t.Context(), "main", fwtest.NewDatabase(t).URL)
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(db.Close)
		p, err := pd.Build(t.Context(), b, db, nil)
		if err != nil {
			t.Fatal(err)
		}
		return []flowcatalyst.BuildOption{flowcatalyst.WithPushes(p)}
	}
	return startRig(t, o)
}

func TestApplySendsThePushGatewayItsRulesAndKeys(t *testing.T) {
	r := pushRig(t, rigOptions{})
	r.platform.respond("push", 200, `{"applicationCode":"orders","rules":{"created":3,"updated":0,"deleted":0},"keys":{"created":1,"updated":0,"deleted":0},`+
		`"syncedPatterns":["orders:order:{id}","orders:user:{principal}","orders:public"],"syncedKids":["test-2026-a"]}`)
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	if err != nil {
		t.Fatal(err)
	}
	req, ok := r.platform.syncOf("push")
	if !ok || req.method != "POST" || req.path != "/api/applications/orders/push/sync" || !strings.HasPrefix(req.header.Get("Authorization"), "Bearer ") {
		t.Fatalf("push sync request %+v", req)
	}
	var body struct {
		Rules []struct {
			Pattern  string   `json:"pattern"`
			Requires []string `json:"requires"`
			Public   bool     `json:"public"`
		} `json:"rules"`
		Keys struct {
			Keys []map[string]string `json:"keys"`
		} `json:"keys"`
	}
	if err := json.Unmarshal([]byte(req.body), &body); err != nil {
		t.Fatalf("%v: %s", err, req.body)
	}
	if len(body.Rules) != 3 || body.Rules[0].Pattern != "orders:order:{id}" || len(body.Rules[0].Requires) != 1 || body.Rules[0].Requires[0] != "orders:order:view" ||
		body.Rules[1].Pattern != "orders:user:{principal}" || !body.Rules[2].Public {
		t.Errorf("rules %+v", body.Rules)
	}
	if len(body.Keys.Keys) != 1 || body.Keys.Keys[0]["kid"] != "test-2026-a" || body.Keys.Keys[0]["kty"] != "OKP" || body.Keys.Keys[0]["x"] == "" {
		t.Errorf("keys %+v", body.Keys.Keys)
	}
	if strings.Contains(req.body, `"d"`) || strings.Contains(req.body, "5BgJptHbkz4uIeRqgLz3Ws") {
		t.Error("the sync body carries the private key")
	}
	res, ok := report.Of(flowcatalyst.CategoryPush)
	if !ok || res.Outcome != flowcatalyst.OutcomeSynced || res.Sent != 3 || res.Created != 3 {
		t.Fatalf("push result %+v", res)
	}
	if got := report.Categories[len(report.Categories)-1].Category; got != flowcatalyst.CategoryPush {
		t.Errorf("PUSH is not last: %v", got)
	}
	if line := res.Describe(); !strings.HasPrefix(line, "PUSH") {
		t.Errorf("line %q", line)
	}
}

func TestDryRunReportsThePushPatternsAndSendsNothing(t *testing.T) {
	r := pushRig(t, rigOptions{})
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncDryRun)
	if err != nil {
		t.Fatal(err)
	}
	res, ok := report.Of(flowcatalyst.CategoryPush)
	if !ok || res.Outcome != flowcatalyst.OutcomeWouldSync || res.Sent != 3 || res.Codes[2] != "orders:public" {
		t.Fatalf("push result %+v", res)
	}
	if n := len(r.platform.syncs()); n != 0 {
		t.Errorf("a dry run made %d sync requests", n)
	}
}

func TestPushSyncFailureIsReportedAndFailsTheSync(t *testing.T) {
	r := pushRig(t, rigOptions{})
	r.platform.respond("push", 400, `{"error":"RULE_INVALID","message":"bad"}`)
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	if _, ok := errors.AsType[*flowcatalyst.SyncError](err); !ok {
		t.Fatalf("error %v", err)
	}
	res, _ := report.Of(flowcatalyst.CategoryPush)
	if res.Outcome != flowcatalyst.OutcomeFailed || !strings.Contains(res.Message, "400") {
		t.Errorf("push result %+v", res)
	}
}

func TestSyncOnBootAlsoSyncsThePushGateway(t *testing.T) {
	r := pushRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_SYNC_ON_BOOT": "true"}})
	if got := r.platform.syncedResources(); len(got) != 1 || got[0] != "push" {
		t.Fatalf("synced %v, want only push (no definitions declared)", got)
	}
}
