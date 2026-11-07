package usecase_test

import (
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// RequireService (fulfil-go port, item 18): a use case only the platform's deliveries, a scheduled
// job or a provider's webhook may run grants a service principal and nobody else.
func TestRequireServiceGrantsOnlyAServicePrincipal(t *testing.T) {
	t.Parallel()
	acme := fw.MustClientID("acme")
	if g, err := usecase.RequireService(fw.ServiceContextFor(acme, fw.MustPrincipalID("prn_svc"))); err != nil || g == (usecase.Grant{}) {
		t.Errorf("a service principal was refused: %v", err)
	}
	for name, cx := range map[string]fw.Context{
		"no principal":           fw.ContextFor(acme),
		"a principal of no kind": fw.ContextFor(acme).WithPrincipal(fw.MustPrincipalID("prn_svc")),
		"a user":                 fw.ContextFor(acme).WithPrincipalOfKind(fw.MustPrincipalID("prn_u"), fw.PrincipalUser),
		"a field worker":         fw.ContextFor(acme).WithPrincipalOfKind(fw.MustPrincipalID("fwu_1"), fw.PrincipalFieldWorker),
		"a user with every permission": fw.ContextFor(acme).WithPrincipalOfKind(fw.MustPrincipalID("prn_u"), fw.PrincipalUser).
			WithPermissions(fw.MustPermission("*:*:*:*")),
	} {
		g, err := usecase.RequireService(cx)
		if g != (usecase.Grant{}) {
			t.Errorf("%s: granted", name)
		}
		wantError(t, err, usecase.KindAuthorization, "SERVICE_REQUIRED")
	}
}
