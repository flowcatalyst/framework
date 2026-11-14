package flowcatalyst

import (
	"errors"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

func guardRig(t *testing.T) *admin.Declared {
	t.Helper()
	app := fw.New("orders", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	adm := admin.Declare(httpfw.Declare(app), fw.MustApplicationCode("orders"))
	adm.Section(admin.NewSection("partitions", "Partitions", "/admin/partitions", "orders:admin:partitions:view").OperatorsOnly())
	return adm
}

// The sync is held to the same refusal as start-up: it sends nothing while a client-managed role holds
// an operators-only permission.
func TestTheSyncRefusesAClientManagedRoleWithAnOperatorsOnlyPermission(t *testing.T) {
	adm := guardRig(t)
	mapping := auth.ByRole(map[string][]string{"orders:clerk": {"orders:admin:*:*"}})
	for _, c := range []struct {
		name  string
		roles []Role
		want  string // "" for no refusal
	}{
		{"by definition", []Role{{Name: "operator", Permissions: []string{"orders:admin:partitions:view"}, ClientManaged: true}}, "role 'orders:operator'"},
		{"by the mapping's wildcard", []Role{{Name: "clerk", Permissions: []string{"orders:sales:order:read"}, ClientManaged: true}}, "(through 'orders:admin:*:*')"},
		{"not client-managed", []Role{{Name: "clerk", Permissions: []string{"orders:admin:partitions:view"}}}, ""},
		{"another permission", []Role{{Name: "operator", Permissions: []string{"orders:sales:order:read"}, ClientManaged: true}}, ""},
	} {
		p := &Platform{catalog: &Catalog{roles: c.roles}, client: disabledClient(), guard: operatorsOnlyGuard{adm: adm, mapping: mapping}}
		_, err := p.Sync(t.Context(), SyncApply)
		if c.want == "" {
			if !errors.Is(err, ErrDisabled) { // past the guard, the disabled client is what stops it
				t.Errorf("%s: %v", c.name, err)
			}
			continue
		}
		if err == nil || errors.Is(err, ErrDisabled) || !strings.Contains(err.Error(), c.want) || !strings.Contains(err.Error(), "operators-only") {
			t.Errorf("%s: %v, want a refusal containing %q", c.name, err, c.want)
		}
	}
}
