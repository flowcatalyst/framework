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
