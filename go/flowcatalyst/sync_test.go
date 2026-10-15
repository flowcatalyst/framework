package flowcatalyst_test

import (
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
)

var allResources = []string{"roles", "event-types", "connections", "dispatch-pools", "subscriptions", "processes", "scheduled-jobs", "docs"}

func ordersDefinitions(d *flowcatalyst.Declared) {
	d.Definitions("orders").
		EventType("orders:sales:order:placed", "Order placed", "A customer placed an order.").
		Permission("orders:sales:order:read", "Read orders").
		Permission("orders:sales:order:write", "Write orders").
		Permission("orders:sales:order:orphan", "Granted by no role").
		Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read", "orders:sales:order:write")).
		Connection("main", "Main").
		DispatchPool(flowcatalyst.DispatchPool{Code: "fast", Name: "Fast", RateLimit: 300, Concurrency: 5}).
		Subscription(flowcatalyst.NewSubscription("on-placed", "On placed", "https://orders.example.com/hooks/placed", "orders:sales:order:placed").
			ViaConnection("main").InPool("fast").WithMode(flowcatalyst.DeliverBlockOnError)).
		Process(flowcatalyst.Process{Code: "orders:sales:checkout", Name: "Checkout", Body: "graph TD; A-->B", Tags: []string{"sales"}}).
		ScheduledJob(flowcatalyst.NewScheduledJob("nightly", "Nightly", "0 0 2 * * *").Tracking(true)).
		Docs("guide", "# Guide\ntext")
}

func syncRig(t *testing.T, o rigOptions) *rig {
	t.Helper()
	o.define = ordersDefinitions
	return startRig(t, o)
}

func TestApplySendsEveryCategoryInDependencyOrderWithRemoveUnlistedOff(t *testing.T) {
	r := syncRig(t, rigOptions{})
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	if err != nil {
		t.Fatal(err)
	}
	if got := r.platform.syncedResources(); !slices.Equal(got, allResources) {
		t.Fatalf("synced %v, want %v", got, allResources)
	}
	for _, req := range r.platform.syncs() {
		if !strings.HasPrefix(req.path, "/api/applications/orders/") || !strings.HasPrefix(req.header.Get("Authorization"), "Bearer ") {
			t.Errorf("request %s %v", req.path, req.header)
		}
		if strings.Contains(req.query, "removeUnlisted=true") {
			t.Errorf("%s: removeUnlisted was set: %q", req.path, req.query)
		}
	}
	jobs, _ := r.platform.syncOf("scheduled-jobs")
	if jobs.json(t)["archiveUnlisted"] == true {
		t.Error("archiveUnlisted was set")
	}
	if report.Mode != flowcatalyst.SyncApply || report.HasFailures() || len(report.Categories) != 8 {
		t.Fatalf("report %+v", report)
	}
	order := []flowcatalyst.Category{
		flowcatalyst.CategoryRoles, flowcatalyst.CategoryEventTypes, flowcatalyst.CategoryConnections, flowcatalyst.CategoryDispatchPools,
		flowcatalyst.CategorySubscriptions, flowcatalyst.CategoryProcesses, flowcatalyst.CategoryScheduledJobs, flowcatalyst.CategoryDocs,
	}
	for i, c := range report.Categories {
		if c.Category != order[i] || c.Outcome != flowcatalyst.OutcomeSynced {
			t.Errorf("category %d: %+v", i, c)
		}
	}
	roles, _ := report.Of(flowcatalyst.CategoryRoles)
	if roles.Created != 1 || len(roles.Codes) != 1 || roles.Codes[0] != "clerk" {
		t.Errorf("roles %+v", roles)
	}
}

func TestTheBodiesCarryTheDeclaredDefinitions(t *testing.T) {
	r := syncRig(t, rigOptions{})
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
		t.Fatal(err)
	}
	body := func(resource, list string) map[string]any {
		req, ok := r.platform.syncOf(resource)
		if !ok {
			t.Fatalf("no sync of %s", resource)
		}
		return req.json(t)[list].([]any)[0].(map[string]any)
	}
	role := body("roles", "roles")
	perms := role["permissions"].([]any)
	if role["name"] != "clerk" || len(perms) != 2 || perms[0] != "orders:sales:order:read" || perms[1] != "orders:sales:order:write" {
		t.Errorf("role %v: the orphan permission is not sent at all", role)
	}
	if et := body("event-types", "eventTypes"); et["code"] != "orders:sales:order:placed" || et["name"] != "Order placed" || et["description"] != "A customer placed an order." {
		t.Errorf("event type %v", et)
	}
	if c := body("connections", "connections"); c["code"] != "main" {
		t.Errorf("connection %v", c)
	}
	pool := body("dispatch-pools", "pools")
	if pool["code"] != "fast" || pool["rateLimit"] != float64(300) || pool["concurrency"] != float64(5) {
		t.Errorf("pool %v", pool)
	}
	sub := body("subscriptions", "subscriptions")
	if sub["code"] != "on-placed" || sub["target"] != "https://orders.example.com/hooks/placed" || sub["connectionCode"] != "main" ||
		sub["dispatchPoolCode"] != "fast" || sub["mode"] != "BLOCK_ON_ERROR" {
		t.Errorf("subscription %v", sub)
	}
	if _, has := sub["sharedConnection"]; has {
		t.Errorf("sharedConnection was sent for an application connection: %v", sub)
	}
	bindings := sub["eventTypes"].([]any)
	if len(bindings) != 1 || bindings[0].(map[string]any)["eventTypeCode"] != "orders:sales:order:placed" {
		t.Errorf("bindings %v", bindings)
	}
	if p := body("processes", "processes"); p["code"] != "orders:sales:checkout" || p["body"] != "graph TD; A-->B" {
		t.Errorf("process %v", p)
	}
	job := body("scheduled-jobs", "jobs")
	crons := job["crons"].([]any)
	if job["code"] != "nightly" || crons[0] != "0 0 2 * * *" || job["tracksCompletion"] != true {
		t.Errorf("job %v", job)
	}
	if _, has := job["clientId"]; has {
		t.Errorf("clientId was sent: %v", job)
	}
	if d := body("docs", "docs"); d["slug"] != "guide" || d["content"] != "# Guide\ntext" {
		t.Errorf("doc %v", d)
	}
	if !r.logs.has("permission 'orders:sales:order:orphan' is declared but no role grants it") {
		t.Errorf("no warning about the orphan permission:\n%s", r.logs.all())
	}
}

func TestASharedConnectionAndTheOtherFieldsOfASubscriptionAreSent(t *testing.T) {
	r := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		s := flowcatalyst.NewSubscription("s", "S", "https://x.example/h", "orders:a:b:c").ViaSharedConnection("hub").WithDescription("desc")
		s.MaxRetries, s.TimeoutSeconds, s.DataOnly = 5, 20, true
		d.Definitions("m").Subscription(s)
	}})
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
		t.Fatal(err)
	}
	req, _ := r.platform.syncOf("subscriptions")
	sub := req.json(t)["subscriptions"].([]any)[0].(map[string]any)
	if sub["connectionCode"] != "hub" || sub["sharedConnection"] != true || sub["maxRetries"] != float64(5) || sub["timeoutSeconds"] != float64(20) ||
		sub["dataOnly"] != true || sub["description"] != "desc" {
		t.Fatalf("subscription %v", sub)
	}
}

func TestAJobsPayloadAndOptionsAreSent(t *testing.T) {
	r := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) {
		j := flowcatalyst.NewScheduledJob("j", "J", "0 0 2 * * *").InZone("Africa/Johannesburg").AllowingConcurrent(true)
		j.Payload = map[string]any{"full": true}
		j.TimeoutSeconds, j.DeliveryMaxAttempts, j.TargetURL = 60, 4, "https://orders.example.com/jobs"
		d.Definitions("m").ScheduledJob(j)
	}})
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
		t.Fatal(err)
	}
	req, _ := r.platform.syncOf("scheduled-jobs")
	job := req.json(t)["jobs"].([]any)[0].(map[string]any)
	payload, _ := job["payload"].(map[string]any)
	if job["timezone"] != "Africa/Johannesburg" || job["concurrent"] != true || payload["full"] != true || job["timeoutSeconds"] != float64(60) ||
		job["deliveryMaxAttempts"] != float64(4) || job["targetUrl"] != "https://orders.example.com/jobs" {
		t.Fatalf("job %v", job)
	}
}

func TestADryRunWritesNothingAndDoesNotEvenCallTheSyncEndpoints(t *testing.T) {
	r := syncRig(t, rigOptions{})
	r.platform.clear()
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncDryRun)
	if err != nil {
		t.Fatal(err)
	}
	if n := len(r.platform.requests()); n != 0 {
		t.Fatalf("%d requests in a dry run", n)
	}
	roles, _ := report.Of(flowcatalyst.CategoryRoles)
	subs, _ := report.Of(flowcatalyst.CategorySubscriptions)
	ets, _ := report.Of(flowcatalyst.CategoryEventTypes)
	if report.Mode != flowcatalyst.SyncDryRun || roles.Outcome != flowcatalyst.OutcomeWouldSync || len(roles.Codes) != 1 || roles.Codes[0] != "clerk" ||
		subs.Codes[0] != "on-placed" || ets.Sent != 1 {
		t.Fatalf("report %+v", report)
	}
	found := false
	for _, l := range report.Describe() {
		found = found || (strings.Contains(l, "WOULD_SYNC") && strings.Contains(l, "clerk"))
	}
	if !found {
		t.Fatalf("describe %v", report.Describe())
	}
}

func TestAFailingCategoryDoesNotStopTheOthersAndEveryFailureIsReported(t *testing.T) {
	p := newFakePlatform(t)
	// not 5xx: fcsdk retries those inside the call, where the Java SDK retries 408, 429, 502, 503 and 504
	p.respond("event-types", 422, `{"error":"boom"}`)
	p.respond("connections", 400, `{"error":"bad connection"}`)
	p.respond("scheduled-jobs", 409, `{"error":"scheduler down"}`)
	r := syncRig(t, rigOptions{platform: p})
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	var se *flowcatalyst.SyncError
	if !asSyncError(err, &se) {
		t.Fatalf("err = %v", err)
	}
	if got := p.syncedResources(); !slices.Equal(got, allResources) {
		t.Fatalf("every category is attempted, in order: %v", got)
	}
	var failed []flowcatalyst.Category
	for _, c := range report.Categories {
		if c.Outcome == flowcatalyst.OutcomeFailed {
			failed = append(failed, c.Category)
			if c.Message == "" {
				t.Errorf("%v failed without a message", c.Category)
			}
		}
	}
	if !slices.Equal(failed, []flowcatalyst.Category{flowcatalyst.CategoryEventTypes, flowcatalyst.CategoryConnections, flowcatalyst.CategoryScheduledJobs}) {
		t.Fatalf("failed %v", failed)
	}
	for _, c := range []flowcatalyst.Category{flowcatalyst.CategoryRoles, flowcatalyst.CategoryDispatchPools, flowcatalyst.CategorySubscriptions, flowcatalyst.CategoryProcesses} {
		if got, _ := report.Of(c); got.Outcome != flowcatalyst.OutcomeSynced {
			t.Errorf("%v: %v", c, got.Outcome)
		}
	}
	for _, want := range []string{"EVENT_TYPES", "CONNECTIONS", "SCHEDULED_JOBS"} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("error %q lacks %s", err, want)
		}
	}
	if se == nil || !se.Report.HasFailures() {
		t.Error("the error's report")
	}
}

func TestRefusedCredentialsFailEveryCategoryWithoutLosingTheReport(t *testing.T) {
	p := newFakePlatform(t)
	r := syncRig(t, rigOptions{platform: p})
	p.failTokens(401)
	r.fc.Client().ClearToken()
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	if err == nil {
		t.Fatal("no error")
	}
	n := 0
	for _, c := range report.Categories {
		if c.Outcome == flowcatalyst.OutcomeFailed {
			n++
		}
	}
	if n != 8 {
		t.Fatalf("%d failed categories, want 8", n)
	}
	if got, _ := report.Of(flowcatalyst.CategoryRoles); !strings.Contains(got.Message, "token") {
		t.Errorf("message %q", got.Message)
	}
	if len(p.syncs()) != 0 {
		t.Error("sync endpoints were called without a token")
	}
}

func TestNothingDeclaredMeansNothingIsSent(t *testing.T) {
	r := startRig(t, rigOptions{})
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	if err != nil || len(r.platform.syncs()) != 0 {
		t.Fatalf("%v, %d syncs", err, len(r.platform.syncs()))
	}
	for _, c := range report.Categories {
		if c.Outcome != flowcatalyst.OutcomeSkipped {
			t.Errorf("%v: %v", c.Category, c.Outcome)
		}
	}
}

func TestPermissionsAloneAreNotSentBecauseOnlyRolesCarryThem(t *testing.T) {
	r := startRig(t, rigOptions{define: func(d *flowcatalyst.Declared) { d.Definitions("orders").Permission("orders:sales:order:read", "Read") }})
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil || len(r.platform.syncs()) != 0 {
		t.Fatalf("%v, %d syncs", err, len(r.platform.syncs()))
	}
}

func TestSyncingIsRepeatable(t *testing.T) {
	r := syncRig(t, rigOptions{})
	for range 2 {
		if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
			t.Fatal(err)
		}
	}
	if n := len(r.platform.syncs()); n != 16 {
		t.Fatalf("%d sync calls, want 16", n)
	}
}

func TestSyncWhileDisabledIsRefused(t *testing.T) {
	r := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_ENABLED": "false"}})
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err == nil || !strings.Contains(err.Error(), "disabled") {
		t.Fatalf("%v", err)
	}
}

func TestADocumentationPageIsSyncedWhereJavaSkipsItsDocs(t *testing.T) {
	// divergence from Java, recorded in the guide: fcsdk has the docs category, the Java SDK has not
	r := syncRig(t, rigOptions{})
	report, _ := r.fc.Sync(t.Context(), flowcatalyst.SyncApply)
	if docs, _ := report.Of(flowcatalyst.CategoryDocs); docs.Outcome != flowcatalyst.OutcomeSynced {
		t.Fatalf("docs %+v", docs)
	}
}

func TestDescribeFormatsEachOutcome(t *testing.T) {
	for _, c := range []struct {
		r    flowcatalyst.CategoryResult
		want string
	}{
		{flowcatalyst.CategoryResult{Category: flowcatalyst.CategoryRoles, Outcome: flowcatalyst.OutcomeSynced, Sent: 2, Created: 1, Updated: 1}, "ROLES           SYNCED     sent=2 created=1 updated=1 deleted=0"},
		{flowcatalyst.CategoryResult{Category: flowcatalyst.CategoryEventTypes, Outcome: flowcatalyst.OutcomeWouldSync, Sent: 2, Codes: []string{"a", "b"}}, "EVENT_TYPES     WOULD_SYNC would send 2: a, b"},
		{flowcatalyst.CategoryResult{Category: flowcatalyst.CategoryDocs, Outcome: flowcatalyst.OutcomeSkipped}, "DOCS            SKIPPED"},
		{flowcatalyst.CategoryResult{Category: flowcatalyst.CategoryConnections, Outcome: flowcatalyst.OutcomeFailed, Message: "boom"}, "CONNECTIONS     FAILED     - boom"},
	} {
		if got := c.r.Describe(); got != c.want {
			t.Errorf("got %q, want %q", got, c.want)
		}
	}
}

// --- the sync command -----------------------------------------------------------------------

func TestTheSyncCommandWithoutArgumentsApplies(t *testing.T) {
	r := startRig(t, rigOptions{args: []string{"sync"}, define: ordersDefinitions})
	if r.exit != 0 || !slices.Equal(r.platform.syncedResources(), allResources) {
		t.Fatalf("exit %d, synced %v", r.exit, r.platform.syncedResources())
	}
	if out := r.out.String(); !strings.Contains(out, "SYNCED") || !strings.Contains(out, "sync complete") {
		t.Fatalf("output:\n%s", out)
	}
}

func TestTheSyncCommandDryRunReportsAndSendsNothingNotEvenAToken(t *testing.T) {
	r := startRig(t, rigOptions{args: []string{"sync", "--dry-run"}, define: ordersDefinitions})
	out := r.out.String()
	if r.exit != 0 || !strings.Contains(out, "WOULD_SYNC") || !strings.Contains(out, "clerk") || !strings.Contains(out, "dry run: nothing was sent") {
		t.Fatalf("exit %d:\n%s", r.exit, out)
	}
	// the command runs without starting anything: not even the first-token probe ran
	if n := len(r.platform.requests()); n != 0 {
		t.Fatalf("the platform saw %d requests", n)
	}
}

func TestAnUnknownSyncArgumentIsRefusedWithUsage(t *testing.T) {
	r := startRig(t, rigOptions{args: []string{"sync", "--dry-run", "--force"}, define: ordersDefinitions})
	out := r.out.String()
	if r.exit != 2 || !strings.Contains(out, "unknown argument: --force") || !strings.Contains(out, "usage: sync [--dry-run]") || len(r.platform.syncs()) != 0 {
		t.Fatalf("exit %d:\n%s", r.exit, out)
	}
}

func TestAFailedCategoryExitsWithOneAndStillPrintsTheReport(t *testing.T) {
	p := newFakePlatform(t)
	p.respond("event-types", 422, `{"error":"boom"}`)
	r := startRig(t, rigOptions{platform: p, args: []string{"sync"}, define: ordersDefinitions})
	out := r.out.String()
	if r.exit != 1 || !strings.Contains(out, "EVENT_TYPES") || !strings.Contains(out, "FAILED") || !strings.Contains(out, "ROLES") || !strings.Contains(out, "sync failed") {
		t.Fatalf("exit %d:\n%s", r.exit, out)
	}
	if len(p.syncs()) != 8 {
		t.Fatalf("%d sync calls: the other categories were not attempted", len(p.syncs()))
	}
}

func TestTheSyncCommandOfADisabledModuleExitsWithTwo(t *testing.T) {
	r := startRig(t, rigOptions{args: []string{"sync"}, env: map[string]string{"FLOWCATALYST_ENABLED": "false"}})
	if r.exit != 2 || !strings.Contains(r.out.String(), "disabled") {
		t.Fatalf("exit %d:\n%s", r.exit, r.out.String())
	}
}

func TestAnyOtherArgumentStartsTheApplicationNormally(t *testing.T) {
	// a command the module does not claim is not run: Launch would start and serve
	r := startRig(t, rigOptions{define: ordersDefinitions})
	if len(r.platform.syncs()) != 0 {
		t.Fatal("a start synced definitions")
	}
}

func TestATransientFailureOfASyncCallIsRetriedBySDKTransport(t *testing.T) {
	p := newFakePlatform(t)
	p.respond("roles", 503, `{"error":"UNAVAILABLE"}`)
	r := startRig(t, rigOptions{platform: p, define: func(d *flowcatalyst.Declared) {
		d.Definitions("m").Role(flowcatalyst.NewRole("clerk"))
	}})
	if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err == nil {
		t.Fatal("a persistent 503 succeeded")
	}
	if n := len(p.syncs()); n != 3 {
		t.Fatalf("%d calls, want the SDK's 3 attempts", n)
	}
}
