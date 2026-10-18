package fw_test

import (
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
)

func TestIDRules(t *testing.T) {
	t.Parallel()
	for _, ok := range []string{"acme", "client.with-dots_1", "A", string(make100('x'))} {
		if _, err := fw.ParseClientID(ok); err != nil {
			t.Errorf("ClientID %q refused: %v", ok, err)
		}
	}
	for _, bad := range []string{"", "a:b", "a b", "é", string(make100('x')) + "x"} {
		if _, err := fw.ParseClientID(bad); err == nil {
			t.Errorf("ClientID %q accepted", bad)
		}
	}
	for _, bad := range []string{"", "Shop", "1shop", "sh_op"} {
		if _, err := fw.ParseApplicationCode(bad); err == nil {
			t.Errorf("ApplicationCode %q accepted", bad)
		}
	}
	for _, bad := range []string{"", "  "} {
		if _, err := fw.ParsePrincipalID(bad); err == nil {
			t.Errorf("PrincipalID %q accepted", bad)
		}
		if _, err := fw.ParseCorrelationID(bad); err == nil {
			t.Errorf("CorrelationID %q accepted", bad)
		}
	}
}

func make100(c byte) []byte {
	b := make([]byte, 100)
	for i := range b {
		b[i] = c
	}
	return b
}

func TestContextNeedsClientAndCorrelation(t *testing.T) {
	t.Parallel()
	if _, err := fw.NewContext(fw.ClientID{}, fw.NewCorrelationID()); err == nil {
		t.Error("a Context without a client was made")
	}
	if _, err := fw.NewContext(fw.MustClientID("acme"), fw.CorrelationID{}); err == nil {
		t.Error("a Context without a correlation id was made")
	}
	if !(fw.Context{}).IsZero() {
		t.Error("the zero Context is not zero")
	}
	cx := fw.ContextFor(fw.MustClientID("acme"))
	if cx.IsZero() || cx.Correlation().IsZero() {
		t.Error("ContextFor: no client or correlation")
	}
	if _, ok := cx.Principal(); ok {
		t.Error("principal present on a new Context")
	}
	cx2 := cx.WithPrincipal(fw.MustPrincipalID("u1")).WithExecution(fw.MustExecutionID("run-7"))
	if p, ok := cx2.Principal(); !ok || p.String() != "u1" {
		t.Errorf("principal: %v %v", p, ok)
	}
	if _, ok := cx.Principal(); ok {
		t.Error("With... changed the original Context")
	}
}

func TestPermissionMatching(t *testing.T) {
	t.Parallel()
	cx := fw.ContextFor(fw.MustClientID("acme")).WithPermissions(
		fw.MustPermission("orders:orders:read"), fw.MustPermission("orders:*:write"))
	cases := map[string]bool{
		"orders:orders:read":   true,
		"orders:orders:write":  true,
		"orders:invoice:write": true,
		"orders:orders:delete": false,
		"orders:orders":        false,
		"orders:orders:read:x": false,
		"billing:orders:read":  false,
	}
	for p, want := range cases {
		if got := cx.HasPermission(fw.MustPermission(p)); got != want {
			t.Errorf("HasPermission(%s) = %v, want %v", p, got, want)
		}
	}
	if cx.HasPermission(fw.Permission{}) {
		t.Error("the zero permission matched")
	}
	if _, err := fw.ParsePermission("a::b"); err == nil {
		t.Error("an empty segment was accepted")
	}
}

func TestPrincipalKindGoesWithItsPrincipal(t *testing.T) {
	t.Parallel()
	cx := fw.ContextFor(fw.MustClientID("acme"))
	if _, ok := cx.PrincipalKind(); ok {
		t.Error("a kind without a principal")
	}
	svc := cx.WithPrincipalOfKind(fw.MustPrincipalID("sa-1"), fw.PrincipalService)
	if k, ok := svc.PrincipalKind(); !ok || k != fw.PrincipalService || k.String() != "SERVICE" {
		t.Errorf("kind %v %v", k, ok)
	}
	if _, ok := svc.WithPrincipal(fw.MustPrincipalID("u-2")).PrincipalKind(); ok {
		t.Error("WithPrincipal kept the previous principal's kind")
	}
	defer func() {
		if recover() == nil {
			t.Error("an unknown kind was accepted")
		}
	}()
	cx.WithPrincipalOfKind(fw.MustPrincipalID("x"), fw.PrincipalKind(9))
}

func TestForClientChangesOnlyTheClient(t *testing.T) {
	t.Parallel()
	base := fw.ContextFor(fw.MustClientID("_platform")).
		WithPrincipalOfKind(fw.MustPrincipalID("prn_svc"), fw.PrincipalService).
		WithApplication(fw.MustApplicationCode("tickets")).
		WithCorrelation(fw.MustCorrelationID("corr-1")).
		WithCausation(fw.MustCausationID("job-9")).
		WithExecution(fw.MustExecutionID("run-3")).
		WithPermissions(fw.MustPermission("tickets:ticket:flag"))

	acme := base.ForClient(fw.MustClientID("acme"))

	if acme.Client() != fw.MustClientID("acme") {
		t.Errorf("client = %v", acme.Client())
	}
	if p, _ := acme.Principal(); p != fw.MustPrincipalID("prn_svc") {
		t.Errorf("principal = %v", p)
	}
	if k, ok := acme.PrincipalKind(); !ok || k != fw.PrincipalService {
		t.Errorf("kind = %v, %v", k, ok)
	}
	if a, _ := acme.Application(); a != fw.MustApplicationCode("tickets") {
		t.Errorf("application = %v", a)
	}
	if acme.Correlation() != fw.MustCorrelationID("corr-1") {
		t.Errorf("correlation = %v", acme.Correlation())
	}
	if c, _ := acme.Causation(); c != fw.MustCausationID("job-9") {
		t.Errorf("causation = %v", c)
	}
	if e, _ := acme.Execution(); e != fw.MustExecutionID("run-3") {
		t.Errorf("execution = %v", e)
	}
	if !acme.HasPermission(fw.MustPermission("tickets:ticket:flag")) {
		t.Error("permissions not kept")
	}
	if base.Client() != fw.MustClientID("_platform") {
		t.Error("the original changed")
	}
}

func TestForClientRefusesZeroValues(t *testing.T) {
	t.Parallel()
	mustPanic := func(name string, f func()) {
		t.Helper()
		defer func() {
			if recover() == nil {
				t.Errorf("%s did not panic", name)
			}
		}()
		f()
	}
	mustPanic("zero client", func() { fw.ContextFor(fw.MustClientID("a")).ForClient(fw.ClientID{}) })
	mustPanic("zero context", func() { fw.Context{}.ForClient(fw.MustClientID("a")) })
}
