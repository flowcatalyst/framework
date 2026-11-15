package platformadmin_test

import (
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/platformadmin"
)

// operatorsOnlyAreaOnly declares no platform section: the platform sections are not operators-only,
// so they cannot be part of an operators-only area.
func operatorsOnlyAreaOnly(*admin.Declared) []platformadmin.Part { return nil }

// In an operators-only area the about section is operators-only, so {app}:admin:console:view is an
// operators-only permission and no client-managed role may carry it.
func TestInAnOperatorsOnlyAreaAClientManagedRoleMayNotOpenTheConsole(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name   string
		define func(fc *flowcatalyst.Declared)
		build  []flowcatalyst.BuildOption
		want   []string
	}{
		{"in the role's definition", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").
				Permission("orders:admin:console:view", "Open the admin area").
				Role(flowcatalyst.NewRole("clerk", "orders:admin:console:view").WithClientManaged())
		}, nil, []string{"role 'orders:clerk'", "client-managed", "the role's definition", "'orders:admin:console:view'"}},
		{"through the permission mapping", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read").WithClientManaged())
		}, []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{
			"orders:clerk": {"orders:sales:order:read", "orders:admin:console:view"},
		}))}, []string{"role 'orders:clerk'", "the application's permission mapping", "'orders:admin:console:view'"}},
		{"through the wildcard in the mapping", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read").WithClientManaged())
		}, []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{
			"orders:clerk": {"orders:admin:*:*"},
		}))}, []string{"role 'orders:clerk'", "'orders:admin:console:view'", "(through 'orders:admin:*:*')"}},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			define := func(fc *flowcatalyst.Declared, adm *admin.Declared) { c.define(fc); fc.AdminDefinitions(adm) }
			_, err := tryRig(t, rigOptions{adminOpts: []admin.Option{admin.OperatorsOnly()}, declare: operatorsOnlyAreaOnly, definitions: define, build: c.build})
			if err == nil {
				t.Fatal("start-up accepted a client-managed role that opens an operators-only area")
			}
			for _, want := range c.want {
				if !strings.Contains(err.Error(), want) {
					t.Errorf("the start-up error lacks %q:\n%v", want, err)
				}
			}
			// the same role is accepted when the area is not operators-only (today's behaviour)
			if _, err := tryRig(t, rigOptions{declare: operatorsOnlyAreaOnly, definitions: define, build: c.build}); err != nil {
				t.Errorf("refused in an area that is not operators-only: %v", err)
			}
		})
	}
}
