package flowcatalyst_test

import (
	"context"
	"maps"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
)

// both registers a dispatch handler and an always-concurrent job handler.
func both(fc *flowcatalyst.Platform) error {
	if err := flowcatalyst.OnDispatch(fc.Receivers(), evt, ack); err != nil {
		return err
	}
	return fc.Receivers().OnScheduledJob(jobCode, flowcatalyst.JobOptions{AlwaysConcurrent: true}, okJob)
}

// status of a delivery signed with secret to each receiver.
func (r *rig) dispatchWith(secret string, extra map[string]string) int {
	h := map[string]string{
		"X-Dispatch-Job-Id": "dj_1", "X-Event-Type": evt,
		"X-FlowCatalyst-Signature": signature(secret, testTS, envelope), "X-FlowCatalyst-Timestamp": testTS,
	}
	maps.Copy(h, extra)
	return r.post("/fc/dispatch", envelope, h).status
}

func (r *rig) jobWith(secret string, extra map[string]string) int {
	body := firingJSON(map[string]string{"concurrent": "true"})
	h := map[string]string{"X-FlowCatalyst-Signature": signature(secret, testTS, body), "X-FlowCatalyst-Timestamp": testTS}
	maps.Copy(h, extra)
	return r.post("/fc/scheduled-jobs", body, h).status
}

func TestOnlyTheSharedSecretServesBothReceivers(t *testing.T) {
	r := startRig(t, rigOptions{register: both})
	if r.dispatchWith(testSecret, nil) != 200 || r.jobWith(testSecret, nil) != 202 {
		t.Fatal("the shared secret does not serve both")
	}
	if r.dispatchWith("other", nil) != 401 || r.jobWith("other", nil) != 401 {
		t.Fatal("another secret is accepted")
	}
}

func TestADispatchSecretOverridesTheSharedOneForDispatchOnly(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_DISPATCH_SIGNING_SECRET": "dispatch-secret"}})
	if r.dispatchWith("dispatch-secret", nil) != 200 || r.dispatchWith(testSecret, nil) != 401 {
		t.Fatal("dispatch does not use its own secret")
	}
	if r.jobWith(testSecret, nil) != 202 || r.jobWith("dispatch-secret", nil) != 401 {
		t.Fatal("the job receiver does not keep the shared secret")
	}
}

func TestAJobsSecretOverridesTheSharedOneForScheduledJobsOnly(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_SCHEDULED_JOBS_SIGNING_SECRET": "jobs-secret"}})
	if r.jobWith("jobs-secret", nil) != 202 || r.jobWith(testSecret, nil) != 401 {
		t.Fatal("jobs do not use their own secret")
	}
	if r.dispatchWith(testSecret, nil) != 200 || r.dispatchWith("jobs-secret", nil) != 401 {
		t.Fatal("dispatch does not keep the shared secret")
	}
}

func TestBothOwnSecretsNeedNoSharedSecret(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{
		"FLOWCATALYST_RECEIVERS_SIGNING_SECRET":                "",
		"FLOWCATALYST_RECEIVERS_DISPATCH_SIGNING_SECRET":       "d",
		"FLOWCATALYST_RECEIVERS_SCHEDULED_JOBS_SIGNING_SECRET": "j",
	}})
	if r.dispatchWith("d", nil) != 200 || r.jobWith("j", nil) != 202 {
		t.Fatal("own secrets")
	}
}

func TestABlankOwnSecretFallsBackToTheSharedOne(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_DISPATCH_SIGNING_SECRET": "   "}})
	if r.dispatchWith(testSecret, nil) != 200 {
		t.Fatal("a blank own secret did not fall back")
	}
	// a blank shared secret counts as none
	_, err := tryRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_SIGNING_SECRET": "  "}})
	mustFail(t, err, "flowcatalyst.receivers.dispatch.signing-secret", "flowcatalyst.receivers.scheduled-jobs.signing-secret")
}

func TestBearerTokensHaveTheSameFallbackRules(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_BEARER_TOKEN": "shared-gate"}})
	if r.dispatchWith(testSecret, map[string]string{"Authorization": "Bearer shared-gate"}) != 200 ||
		r.jobWith(testSecret, map[string]string{"Authorization": "Bearer shared-gate"}) != 202 ||
		r.dispatchWith(testSecret, nil) != 401 || r.jobWith(testSecret, nil) != 401 {
		t.Fatal("the shared bearer does not gate both receivers")
	}
	r2 := startRig(t, rigOptions{register: both, env: map[string]string{
		"FLOWCATALYST_RECEIVERS_BEARER_TOKEN": "shared-gate", "FLOWCATALYST_RECEIVERS_DISPATCH_BEARER_TOKEN": "dispatch-gate",
	}})
	if r2.dispatchWith(testSecret, map[string]string{"Authorization": "Bearer dispatch-gate"}) != 200 ||
		r2.dispatchWith(testSecret, map[string]string{"Authorization": "Bearer shared-gate"}) != 401 ||
		r2.jobWith(testSecret, map[string]string{"Authorization": "Bearer shared-gate"}) != 202 {
		t.Fatal("an own bearer does not replace the shared one for its receiver only")
	}
	r3 := startRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_SCHEDULED_JOBS_BEARER_TOKEN": "jobs-gate"}})
	if r3.jobWith(testSecret, nil) != 401 || r3.jobWith(testSecret, map[string]string{"Authorization": "Bearer jobs-gate"}) != 202 || r3.dispatchWith(testSecret, nil) != 200 {
		t.Fatal("a jobs bearer without a shared one")
	}
}

func TestAReceiverWithHandlersNeedsAnEffectiveSecret(t *testing.T) {
	noSecret := map[string]string{"FLOWCATALYST_RECEIVERS_SIGNING_SECRET": ""}
	_, err := tryRig(t, rigOptions{env: noSecret, register: func(fc *flowcatalyst.Platform) error {
		return flowcatalyst.OnDispatch(fc.Receivers(), evt, ack)
	}})
	mustFail(t, err, "flowcatalyst.receivers.dispatch.signing-secret", "required while dispatch handlers are registered", "flowcatalyst.receivers.signing-secret")
	_, err = tryRig(t, rigOptions{env: noSecret, register: func(fc *flowcatalyst.Platform) error {
		return fc.Receivers().OnScheduledJob(jobCode, flowcatalyst.JobOptions{AlwaysConcurrent: true}, okJob)
	}})
	mustFail(t, err, "flowcatalyst.receivers.scheduled-jobs.signing-secret", "scheduled-job handlers")
	_, err = tryRig(t, rigOptions{env: noSecret, register: both})
	mustFail(t, err, "flowcatalyst.receivers.dispatch.signing-secret", "flowcatalyst.receivers.scheduled-jobs.signing-secret")
}

func TestAReceiverWithoutHandlersNeedsNoSecretAndIsNotFound(t *testing.T) {
	noSecret := map[string]string{"FLOWCATALYST_RECEIVERS_SIGNING_SECRET": ""}
	// only dispatch has handlers: the job receiver needs nothing, and does not exist
	r := startRig(t, rigOptions{
		env: map[string]string{
			"FLOWCATALYST_RECEIVERS_SIGNING_SECRET": "", "FLOWCATALYST_RECEIVERS_DISPATCH_SIGNING_SECRET": "d",
		},
		register: func(fc *flowcatalyst.Platform) error { return flowcatalyst.OnDispatch(fc.Receivers(), evt, ack) },
	})
	got := r.post("/fc/scheduled-jobs", "{}", nil) // an unsigned probe: not 401, before any signature work
	if got.status != 404 || !strings.Contains(got.body, `"NOT_FOUND"`) {
		t.Fatalf("%d %s", got.status, got.body)
	}
	// an application with no handlers at all
	r2 := startRig(t, rigOptions{env: noSecret})
	for _, path := range []string{"/fc/dispatch", "/fc/scheduled-jobs"} {
		if got := r2.post(path, "{}", nil); got.status != 404 {
			t.Errorf("%s: %d", path, got.status)
		}
	}
	// every method, not only POST (Java answers 405 to a GET here; Go routes nothing)
	r3 := startRig(t, rigOptions{env: noSecret})
	for _, m := range []string{"GET", "PUT", "DELETE", "OPTIONS"} {
		rr := r3.postRequest(newRequest(t, m, "/fc/dispatch"))
		if rr.status != 404 {
			t.Errorf("%s /fc/dispatch with no handler: %d, want 404", m, rr.status)
		}
	}
}

func TestWithRequireSignatureFalseNoSecretIsNeeded(t *testing.T) {
	startRig(t, rigOptions{register: both, env: map[string]string{
		"FLOWCATALYST_RECEIVERS_REQUIRE_SIGNATURE": "false", "FLOWCATALYST_RECEIVERS_SIGNING_SECRET": "",
	}})
}

func TestHandlersWithoutRoutesMountedFailStartUp(t *testing.T) {
	_, err := tryRig(t, rigOptions{noRoutes: true, register: both})
	mustFail(t, err, "receivers are registered but their routes were never mounted", "Platform.Routes()")
}

func TestDisabledReceiversMountNothingNeedNoSecretAndWarn(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{
		"FLOWCATALYST_RECEIVERS_ENABLED": "false", "FLOWCATALYST_RECEIVERS_SIGNING_SECRET": "",
	}})
	for _, path := range []string{"/fc/dispatch", "/fc/scheduled-jobs"} {
		if got := r.post(path, "{}", signedHeaders("{}", nil)); got.status != 404 {
			t.Errorf("%s: %d, want 404 (nothing is mounted)", path, got.status)
		}
	}
	if !r.logs.has("WARN flowcatalyst: handlers are registered") || !r.logs.has("flowcatalyst.receivers.enabled is false") {
		t.Fatalf("no warning naming the handlers:\n%s", r.logs.all())
	}
	// without handlers: no warning
	r2 := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_RECEIVERS_ENABLED": "false", "FLOWCATALYST_RECEIVERS_SIGNING_SECRET": ""}})
	if r2.logs.has("handlers are registered") {
		t.Fatal("a warning without handlers")
	}
}

func TestThePrefixIsASetting(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{"FLOWCATALYST_RECEIVERS_PATH": "/hooks/fc"}})
	h := dispatchHeaders(envelope, evt, nil)
	if got := r.post("/hooks/fc/dispatch", envelope, h); got.status != 200 {
		t.Fatalf("%d %s", got.status, got.body)
	}
	if got := r.post("/fc/dispatch", envelope, h); got.status != 404 {
		t.Fatalf("the old prefix: %d", got.status)
	}
}

func TestADisabledModuleMountsNothingAndNeedsNoSettings(t *testing.T) {
	r := startRig(t, rigOptions{register: both, env: map[string]string{
		"FLOWCATALYST_ENABLED": "false", "FLOWCATALYST_BASE_URL": "", "FLOWCATALYST_APPLICATION": "", "FLOWCATALYST_CLIENT_ID": "",
		"FLOWCATALYST_CLIENT_SECRET": "", "FLOWCATALYST_RECEIVERS_SIGNING_SECRET": "",
	}})
	if r.fc.Client().Enabled() {
		t.Fatal("the client is enabled")
	}
	if _, err := r.fc.Client().AccessToken(context.Background()); err == nil {
		t.Fatal("a disabled client gave a token")
	}
	if got := r.post("/fc/dispatch", "{}", nil); got.status != 404 {
		t.Fatalf("%d", got.status)
	}
	if !r.logs.has("flowcatalyst is disabled") || !r.logs.has("receivers are registered but the module is disabled") {
		t.Fatalf("logs:\n%s", r.logs.all())
	}
	if len(r.fc.Catalog().EventTypes()) != 0 {
		t.Fatal("a disabled module has a catalog")
	}
}
