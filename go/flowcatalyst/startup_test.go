package flowcatalyst_test

import (
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fwtest"
)

func TestMain(m *testing.M) { fwtest.Main(m) }

func TestTheRequiredSettingsAreReportedTogetherWhileEnabled(t *testing.T) {
	_, err := tryRig(t, rigOptions{env: map[string]string{
		"FLOWCATALYST_BASE_URL": "", "FLOWCATALYST_APPLICATION": "", "FLOWCATALYST_CLIENT_ID": "", "FLOWCATALYST_CLIENT_SECRET": "",
	}})
	mustFail(t, err, "flowcatalyst.base-url", "flowcatalyst.application", "flowcatalyst.client-id", "flowcatalyst.client-secret", "required while flowcatalyst.enabled is true")
	if n := len(problemTexts(err)); n != 4 {
		t.Fatalf("%d problems, want the four at once: %v", n, problemTexts(err))
	}
}

func TestMalformedBaseURLAndApplicationAreStartUpProblems(t *testing.T) {
	_, err := tryRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_BASE_URL": "ftp://platform.example"}})
	mustFail(t, err, "flowcatalyst.base-url", "must be an absolute http or https URL")
	_, err = tryRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_BASE_URL": "https:///nohost"}})
	mustFail(t, err, "flowcatalyst.base-url")
	for _, bad := range []string{"Orders", "1orders", "or ders", "orders_x"} {
		_, err = tryRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_APPLICATION": bad}})
		mustFail(t, err, "flowcatalyst.application", "[a-z][a-z0-9-]*")
	}
}

func TestEverySettingHasItsRangeAndItsEnvironmentVariable(t *testing.T) {
	type c struct {
		key, env string
		ok, bad  []string
	}
	for _, x := range []c{
		{"flowcatalyst.timeout", "FLOWCATALYST_TIMEOUT", []string{"1ms", "30s", "2h"}, []string{"0s", "-1s"}},
		{"flowcatalyst.outbox.stale-after", "FLOWCATALYST_OUTBOX_STALE_AFTER", []string{"1ms", "5m"}, []string{"0s"}},
		{"flowcatalyst.receivers.dispatch-timeout", "FLOWCATALYST_RECEIVERS_DISPATCH_TIMEOUT", []string{"1ms", "25s"}, []string{"0s"}},
		{"flowcatalyst.receivers.job-timeout", "FLOWCATALYST_RECEIVERS_JOB_TIMEOUT", []string{"1ms", "10m"}, []string{"0s"}},
		{"flowcatalyst.receivers.job-lock-lease", "FLOWCATALYST_RECEIVERS_JOB_LOCK_LEASE", []string{"1s", "10m", "24h"}, []string{"999ms", "24h1s", "0s"}},
		{"flowcatalyst.receivers.completion-retry-delay", "FLOWCATALYST_RECEIVERS_COMPLETION_RETRY_DELAY", []string{"0s", "1s", "1m"}, []string{"61s", "-1s"}},
		{"flowcatalyst.receivers.once-retention", "FLOWCATALYST_RECEIVERS_ONCE_RETENTION", []string{"1ms", "30d"}, []string{"0s"}},
		{"flowcatalyst.receivers.once-purge-interval", "FLOWCATALYST_RECEIVERS_ONCE_PURGE_INTERVAL", []string{"1ms", "1h"}, []string{"0s"}},
		{"flowcatalyst.receivers.path", "FLOWCATALYST_RECEIVERS_PATH", []string{"/fc", "/a/b", "/a.b_c~d-e"}, []string{"fc", "/fc/", "/", "/a b", "//fc", "/a//b"}},
	} {
		for _, ok := range x.ok {
			if _, err := tryRig(t, rigOptions{env: map[string]string{x.env: ok}}); err != nil {
				t.Errorf("%s=%s refused: %v", x.key, ok, err)
			}
		}
		for _, bad := range x.bad {
			_, err := tryRig(t, rigOptions{env: map[string]string{x.env: bad}})
			if err == nil || !strings.Contains(strings.Join(problemTexts(err), "\n"), "key '"+x.key+"'") {
				t.Errorf("%s=%s: %v, want a problem naming the key", x.key, bad, err)
			}
		}
	}
	for _, env := range []string{"FLOWCATALYST_ENABLED", "FLOWCATALYST_SYNC_ON_BOOT", "FLOWCATALYST_RECEIVERS_ENABLED", "FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE"} {
		if _, err := tryRig(t, rigOptions{env: map[string]string{env: "maybe"}}); err == nil {
			t.Errorf("%s=maybe accepted", env)
		}
	}
}

func TestAMisspeltKeyUnderTheComponentFailsListingTheRealOnes(t *testing.T) {
	_, err := tryRig(t, rigOptions{yaml: "flowcatalyst:\n  receivrs:\n    path: /x\n"})
	mustFail(t, err, "flowcatalyst.receivrs.path", "flowcatalyst.receivers.path")
}

func TestTheSettingsAreReadFromAFile(t *testing.T) {
	r := startRig(t, rigOptions{register: both, yaml: "flowcatalyst:\n  receivers:\n    path: /from-file\n"})
	if got := r.post("/from-file/dispatch", envelope, dispatchHeaders(envelope, evt, nil)); got.status != 200 {
		t.Fatalf("%d", got.status)
	}
}

func TestStartUpSurvivesAnUnreachablePlatformAndTheHealthCheckReportsIt(t *testing.T) {
	p := newFakePlatform(t)
	p.failTokens(503)
	r := startRig(t, rigOptions{platform: p})
	waitFor(t, "the start-up warning", func() bool { return r.logs.has("platform not usable at start-up") })
	rep := r.health()
	res, ok := rep.Result("flowcatalyst")
	if !ok || res.Status.String() != "DOWN" || res.Criticality.String() != "INFORMATIONAL" {
		t.Fatalf("health %+v", res)
	}
	if strings.Contains(res.Reason, "server_error") || strings.Contains(res.Reason, fakeClientSecret) {
		t.Fatalf("the reason shows the platform's answer or a secret: %q", res.Reason)
	}
	if !rep.IsReady() {
		t.Fatal("an informational check made the application not ready")
	}
}

func TestTheFlowcatalystCheckIsUpWithGoodCredentials(t *testing.T) {
	r := startRig(t, rigOptions{})
	res, ok := r.health().Result("flowcatalyst")
	if !ok || res.Status.String() != "UP" {
		t.Fatalf("health %+v", res)
	}
}

func TestWithHandlersTheCheckReportsAMissingServicePrincipal(t *testing.T) {
	p := newFakePlatform(t)
	p.setToken("prn_0HUMAN", "USER")
	r := startRig(t, rigOptions{platform: p, register: both})
	res, _ := r.health().Result("flowcatalyst")
	if res.Status.String() != "DOWN" || !strings.Contains(res.Reason, "SERVICE") {
		t.Fatalf("health %+v", res)
	}
	waitFor(t, "the warning", func() bool { return r.logs.has("cannot run receiver handlers yet") })
	// without handlers the same token is fine: a token-only application
	p2 := newFakePlatform(t)
	p2.setToken("prn_0HUMAN", "USER")
	r2 := startRig(t, rigOptions{platform: p2})
	if res, _ := r2.health().Result("flowcatalyst"); res.Status.String() != "UP" {
		t.Fatalf("a token-only application: %+v", res)
	}
}

func TestWithHandlersAndAServiceTokenTheCheckIsUpAndStartUpNamesThePrincipal(t *testing.T) {
	r := startRig(t, rigOptions{register: both})
	waitFor(t, "the principal in the log", func() bool { return r.logs.has("receiver handlers run as service principal " + servicePrincipal) })
	if res, _ := r.health().Result("flowcatalyst"); res.Status.String() != "UP" {
		t.Fatalf("%+v", res)
	}
}

func TestACriticalOverrideMakesTheCheckDecideReadiness(t *testing.T) {
	p := newFakePlatform(t)
	p.failTokens(503)
	r := startRig(t, rigOptions{platform: p, env: map[string]string{"APP_HEALTH_CRITICAL": "flowcatalyst"}})
	if r.health().IsReady() {
		t.Fatal("app.health.critical did not make the platform check decide readiness")
	}
}

func TestSyncOnBootRunsInTheStartedPhaseAndNeverStopsStartUp(t *testing.T) {
	r := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_SYNC_ON_BOOT": "true"}, define: func(d *flowcatalyst.Declared) {
		d.Definitions("orders").EventType("orders:sales:order:placed", "Order placed", "")
	}})
	if got := r.platform.syncedResources(); len(got) != 1 || got[0] != "event-types" {
		t.Fatalf("synced %v", got)
	}
	p := newFakePlatform(t)
	p.respond("event-types", 422, `{"error":"INVALID"}`)
	r2 := startRig(t, rigOptions{platform: p, env: map[string]string{"FLOWCATALYST_SYNC_ON_BOOT": "true"}, define: func(d *flowcatalyst.Declared) {
		d.Definitions("orders").EventType("orders:sales:order:placed", "Order placed", "")
	}})
	if !r2.logs.has("sync on boot failed; the application continues") {
		t.Fatalf("logs:\n%s", r2.logs.all())
	}
	// off by default
	r3 := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		d.Definitions("orders").EventType("orders:sales:order:placed", "x", "")
	}})
	if n := len(r3.platform.syncs()); n != 0 {
		t.Fatalf("%d syncs without flowcatalyst.sync.on-boot", n)
	}
}

func TestTheDefaults(t *testing.T) {
	// the defaults are observable through behaviour that needs no waiting: the prefix and the
	// signature requirement
	r := startRig(t, rigOptions{register: both})
	if got := r.post("/fc/dispatch", envelope, map[string]string{"X-Dispatch-Job-Id": "dj_1", "X-Event-Type": evt}); got.status != 401 {
		t.Fatalf("unsigned by default: %d", got.status)
	}
	if got := r.post("/fc/dispatch", envelope, dispatchHeaders(envelope, evt, nil)); got.status != 200 {
		t.Fatalf("/fc by default: %d", got.status)
	}
}
