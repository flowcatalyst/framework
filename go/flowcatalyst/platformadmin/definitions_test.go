package platformadmin_test

import (
	"slices"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
)

var adminPermissions = []string{"orders:admin:console:view", "orders:admin:event-types:view", "orders:admin:roles:view"}

func permissionCodes(c *flowcatalyst.Catalog) []string {
	var out []string
	for _, p := range c.Permissions() {
		out = append(out, p.Code)
	}
	return out
}

func TestTheCatalogHoldsTheAdminPermissionsAndOneAdminRoleGrantingThemExplicitly(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) { fc.AdminDefinitions(adm) }})
	catalog := r.fc.Catalog()
	if got := permissionCodes(catalog); !slices.Equal(got, adminPermissions) {
		t.Fatalf("permissions %v", got)
	}
	roles := catalog.Roles()
	if len(roles) != 1 || roles[0].Name != "admin" || roles[0].DisplayName != "Administrator" {
		t.Fatalf("roles %+v", roles)
	}
	// explicit: the definition sync does not accept a wildcard in a role's permissions
	if !slices.Equal(roles[0].Permissions, adminPermissions) {
		t.Fatalf("the role grants %v", roles[0].Permissions)
	}
	for _, p := range catalog.Permissions() {
		if p.Description == "" {
			t.Errorf("%s has no description", p.Code)
		}
	}
}

func TestAnApplicationThatDeclaresItsOwnAdminRoleKeepsIt(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		fc.Definitions("own").
			Permission("orders:admin:console:view", "Open the admin").
			Role(flowcatalyst.NewRole("admin", "orders:admin:console:view"))
		fc.AdminDefinitions(adm)
	}})
	catalog := r.fc.Catalog()
	if len(catalog.Roles()) != 1 || !slices.Equal(catalog.Roles()[0].Permissions, []string{"orders:admin:console:view"}) || len(catalog.Permissions()) != 1 {
		t.Fatalf("roles %+v permissions %v", catalog.Roles(), permissionCodes(catalog))
	}
}

func TestADeclaredPermissionIsNotDeclaredTwice(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		fc.Definitions("own").Permission("orders:admin:console:view", "Open the admin")
		fc.AdminDefinitions(adm)
	}})
	if got := permissionCodes(r.fc.Catalog()); len(got) != 3 || got[0] != "orders:admin:console:view" {
		t.Fatalf("permissions %v", got)
	}
}

func TestADryRunReportsTheAdminRoleAndCallsNothing(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) { fc.AdminDefinitions(adm) }})
	report, err := r.fc.Sync(t.Context(), flowcatalyst.SyncDryRun)
	if err != nil {
		t.Fatal(err)
	}
	for _, q := range r.platform.Requests() {
		if q.Path != "/oauth/token" {
			t.Errorf("the dry run called %s", q.Path)
		}
	}
	roles, ok := report.Of(flowcatalyst.CategoryRoles)
	if !ok || roles.Outcome != flowcatalyst.OutcomeWouldSync || !slices.Equal(roles.Codes, []string{"admin"}) {
		t.Fatalf("roles %+v", roles)
	}
}
