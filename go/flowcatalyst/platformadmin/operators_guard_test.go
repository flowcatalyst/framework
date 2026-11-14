package platformadmin_test

import (
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/partitions/partitionsadmin"
)

// The partitions section is operators-only, so a role that a client's own administrators may assign
// (client-managed) must not carry its permissions: not in its definition, and not through the
// application's permission mapping, wildcards included.

func withPartitions(define func(fc *flowcatalyst.Declared)) func(fc *flowcatalyst.Declared, adm *admin.Declared) {
	return func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		partitionsadmin.Declare(adm)
		define(fc)
		fc.AdminDefinitions(adm)
	}
}

// withOperatorsSection registers an operators-only section with the partitions section's view
// permission and no operations, so the rig has nothing to bind.
func withOperatorsSection(define func(fc *flowcatalyst.Declared)) func(fc *flowcatalyst.Declared, adm *admin.Declared) {
	return func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		adm.Section(admin.NewSection("partitions", "Partitions", "/admin/partitions", partitionsadmin.View(adm.Application())).OperatorsOnly())
		define(fc)
		fc.AdminDefinitions(adm)
	}
}

func TestAClientManagedRoleWithAnOperatorsOnlyPermissionFailsStartUp(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name   string
		define func(fc *flowcatalyst.Declared)
		build  []flowcatalyst.BuildOption
		want   []string
	}{
		{"the exact permission in the role's definition", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").
				Permission("orders:admin:partitions:view", "View partitions").
				Role(flowcatalyst.NewRole("operator", "orders:admin:partitions:view").WithClientManaged())
		}, nil, []string{"role 'orders:operator'", "client-managed", "the role's definition", "'orders:admin:partitions:view'"}},
		{"the manage permission in the definition among others", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").
				Permission("orders:sales:order:read", "Read orders").
				Permission("orders:admin:partitions:manage", "Manage partitions").
				Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read", "orders:admin:partitions:manage").WithClientManaged())
		}, nil, []string{"role 'orders:clerk'", "'orders:admin:partitions:manage'"}},
		{"the exact permission through the permission mapping", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("operator", "orders:sales:order:read").WithClientManaged())
		}, []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{
			"orders:operator": {"orders:sales:order:read", "orders:admin:partitions:view"},
		}))}, []string{"role 'orders:operator'", "the application's permission mapping", "'orders:admin:partitions:view'"}},
		{"a wildcard through the permission mapping", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("operator", "orders:sales:order:read").WithClientManaged())
		}, []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{
			"orders:operator": {"orders:admin:*:*"},
		}))}, []string{"role 'orders:operator'", "'orders:admin:partitions:view'", "'orders:admin:partitions:manage'", "(through 'orders:admin:*:*')"}},
		{"a wildcard of one segment", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("operator", "orders:sales:order:read").WithClientManaged())
		}, []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{
			"orders:operator": {"orders:admin:partitions:*"},
		}))}, []string{"'orders:admin:partitions:view'", "(through 'orders:admin:partitions:*')"}},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			_, err := tryRig(t, rigOptions{definitions: withPartitions(c.define), build: c.build})
			if err == nil {
				t.Fatal("start-up accepted a client-managed role that opens an operators-only section")
			}
			for _, want := range c.want {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("the start-up error lacks %q:\n%v", want, err)
				}
			}
		})
	}
}

func TestRolesThatMayCarryThePermissionsAreAccepted(t *testing.T) {
	t.Parallel()
	wildcard := flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{
		"orders:admin":    {"orders:admin:*:*"},
		"orders:operator": {"orders:sales:order:read", "orders:admin:config:view", "orders:admin:console:view"},
	}))
	for _, c := range []struct {
		name   string
		define func(fc *flowcatalyst.Declared)
	}{
		{"a role that is not client-managed holds the wildcard and the permissions", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").
				Permission("orders:admin:partitions:view", "View partitions").
				Role(flowcatalyst.NewRole("admin", "orders:admin:partitions:view", "orders:admin:partitions:manage"))
		}},
		{"a client-managed role with other permissions, including another admin section's", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").
				Permission("orders:sales:order:read", "Read orders").
				Permission("orders:admin:config:view", "View config").
				Role(flowcatalyst.NewRole("operator", "orders:sales:order:read", "orders:admin:config:view").WithClientManaged())
		}},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			r, err := tryRig(t, rigOptions{definitions: withOperatorsSection(c.define), build: []flowcatalyst.BuildOption{wildcard}})
			if err != nil {
				t.Fatalf("refused: %v", err)
			}
			if len(r.fc.Catalog().Roles()) == 0 {
				t.Error("no roles in the catalog")
			}
		})
	}
	// the mapping gives a client-managed role the wildcard only for another role's name: not that role
	_, err := tryRig(t, rigOptions{
		definitions: withOperatorsSection(func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("operator", "orders:sales:order:read").WithClientManaged())
		}),
		build: []flowcatalyst.BuildOption{wildcard},
	})
	if err != nil {
		t.Errorf("the wildcard belongs to orders:admin, not to the client-managed orders:operator: %v", err)
	}
}

func TestWithoutAnOperatorsOnlySectionTheGuardChecksNothing(t *testing.T) {
	t.Parallel()
	_, err := tryRig(t, rigOptions{
		definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
			fc.Definitions("own").
				Permission("orders:admin:partitions:view", "View partitions").
				Role(flowcatalyst.NewRole("operator", "orders:admin:partitions:view").WithClientManaged())
			fc.AdminDefinitions(adm) // no partitions section registered
		},
		build: []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{"orders:operator": {"orders:admin:*:*"}}))},
	})
	if err != nil {
		t.Fatalf("a client-managed role was refused although no section is operators-only: %v", err)
	}
}

func TestADisabledModuleRefusesToo(t *testing.T) {
	t.Parallel()
	_, err := tryRig(t, rigOptions{
		env: map[string]string{"FLOWCATALYST_ENABLED": "false"},
		definitions: withPartitions(func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("operator", "orders:admin:partitions:view").WithClientManaged())
		}),
	})
	if err == nil || !strings.Contains(err.Error(), "'orders:admin:partitions:view'") {
		t.Fatalf("a disabled module accepted it: %v", err)
	}
}
