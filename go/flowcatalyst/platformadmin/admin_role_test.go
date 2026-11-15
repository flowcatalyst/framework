package platformadmin_test

import (
	"log/slog"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
)

func roleNames(c *flowcatalyst.Catalog) []string {
	var out []string
	for _, r := range c.Roles() {
		out = append(out, r.Name)
	}
	return out
}

func defineAdmin(opts ...flowcatalyst.AdminDefinitionsOption) func(fc *flowcatalyst.Declared, adm *admin.Declared) {
	return func(fc *flowcatalyst.Declared, adm *admin.Declared) { fc.AdminDefinitions(adm, opts...) }
}

func TestTheSuggestedRoleCanBeNamed(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: defineAdmin(flowcatalyst.AdminRoleName("platform-operator"))})
	catalog := r.fc.Catalog()
	roles := catalog.Roles()
	if len(roles) != 1 || roles[0].Name != "platform-operator" || roles[0].DisplayName != "Platform operator" {
		t.Fatalf("roles %+v", roles)
	}
	if !slices.Equal(roles[0].Permissions, adminPermissions) {
		t.Errorf("the role grants %v, want every section permission %v", roles[0].Permissions, adminPermissions)
	}
	if roles[0].Description == "" || roles[0].ClientManaged {
		t.Errorf("description %q, client-managed %v", roles[0].Description, roles[0].ClientManaged)
	}
	if slices.Contains(roleNames(catalog), "admin") {
		t.Error("a role named admin was declared as well")
	}
	if got := permissionCodes(catalog); !slices.Equal(got, adminPermissions) {
		t.Errorf("permissions %v", got)
	}
}

func TestTheDisplayNameCanBeSet(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name string
		opts []flowcatalyst.AdminDefinitionsOption
		want string
	}{
		{"on the default name", []flowcatalyst.AdminDefinitionsOption{flowcatalyst.AdminRoleDisplayName("Console owner")}, "Console owner"},
		{"on a chosen name", []flowcatalyst.AdminDefinitionsOption{flowcatalyst.AdminRoleName("platform-operator"), flowcatalyst.AdminRoleDisplayName("Operator")}, "Operator"},
		{"the default name is unchanged", nil, "Administrator"},
		{"the derived name of a plain word", []flowcatalyst.AdminDefinitionsOption{flowcatalyst.AdminRoleName("support")}, "Support"},
		{"a chosen name admin keeps Administrator", []flowcatalyst.AdminDefinitionsOption{flowcatalyst.AdminRoleName("admin")}, "Administrator"},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			r := startRig(t, rigOptions{definitions: defineAdmin(c.opts...)})
			if roles := r.fc.Catalog().Roles(); len(roles) != 1 || roles[0].DisplayName != c.want {
				t.Fatalf("roles %+v, want display name %q", roles, c.want)
			}
		})
	}
}

func TestAnApplicationRoleOfTheChosenNameIsKeptAndNothingIsAdded(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		fc.Definitions("own").
			Permission("orders:admin:console:view", "Open the admin").
			Role(flowcatalyst.NewRole("platform-operator", "orders:admin:console:view"))
		fc.AdminDefinitions(adm, flowcatalyst.AdminRoleName("platform-operator"))
	}})
	catalog := r.fc.Catalog()
	if len(catalog.Roles()) != 1 || !slices.Equal(catalog.Roles()[0].Permissions, []string{"orders:admin:console:view"}) || len(catalog.Permissions()) != 1 {
		t.Fatalf("roles %+v permissions %v", catalog.Roles(), permissionCodes(catalog))
	}
	// a role named admin, which is not the chosen name, does not stop the suggested role
	r = startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		fc.Definitions("own").
			Permission("orders:admin:console:view", "Open the admin").
			Role(flowcatalyst.NewRole("admin", "orders:admin:console:view"))
		fc.AdminDefinitions(adm, flowcatalyst.AdminRoleName("platform-operator"))
	}})
	if got := roleNames(r.fc.Catalog()); !slices.Equal(got, []string{"admin", "platform-operator"}) {
		t.Errorf("roles %v", got)
	}
}

func TestAnEmptyOrMalformedRoleNameIsADeclarationProblem(t *testing.T) {
	t.Parallel()
	for _, c := range []struct{ name, role string }{
		{"empty", ""},
		{"with the application prefix", "orders:admin"},
		{"with a space", "platform operator"},
		{"upper case", "Operator"},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			_, err := tryRig(t, rigOptions{definitions: defineAdmin(flowcatalyst.AdminRoleName(c.role))})
			if err == nil {
				t.Fatalf("start-up accepted the role name %q", c.role)
			}
			if !strings.Contains(err.Error(), "role name must") {
				t.Errorf("the error does not come from the role-name check: %v", err)
			}
		})
	}
}

func TestWithoutAdminRoleAddsThePermissionsAndNoRole(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{definitions: defineAdmin(flowcatalyst.WithoutAdminRole())})
	catalog := r.fc.Catalog()
	if len(catalog.Roles()) != 0 {
		t.Fatalf("roles %+v", catalog.Roles())
	}
	if got := permissionCodes(catalog); !slices.Equal(got, adminPermissions) {
		t.Fatalf("permissions %v", got)
	}
	for _, p := range catalog.Permissions() {
		if p.Description == "" {
			t.Errorf("%s has no description", p.Code)
		}
	}
	// a permission the application declared itself is not declared twice, and its own admin role is kept
	r = startRig(t, rigOptions{definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
		fc.Definitions("own").
			Permission("orders:admin:console:view", "Open the admin").
			Role(flowcatalyst.NewRole("admin", "orders:admin:console:view"))
		fc.AdminDefinitions(adm, flowcatalyst.WithoutAdminRole())
	}})
	if got := permissionCodes(r.fc.Catalog()); !slices.Equal(got, adminPermissions) {
		t.Errorf("permissions %v", got)
	}
	if got := roleNames(r.fc.Catalog()); !slices.Equal(got, []string{"admin"}) {
		t.Errorf("roles %v", got)
	}
}

func TestWithoutAdminRoleAndANameIsADeclarationProblem(t *testing.T) {
	t.Parallel()
	for _, c := range []struct {
		name string
		opts []flowcatalyst.AdminDefinitionsOption
	}{
		{"a name", []flowcatalyst.AdminDefinitionsOption{flowcatalyst.WithoutAdminRole(), flowcatalyst.AdminRoleName("platform-operator")}},
		{"a display name", []flowcatalyst.AdminDefinitionsOption{flowcatalyst.AdminRoleDisplayName("Operator"), flowcatalyst.WithoutAdminRole()}},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			_, err := tryRig(t, rigOptions{definitions: defineAdmin(c.opts...)})
			if err == nil || !strings.Contains(err.Error(), "have no effect") {
				t.Fatalf("want a problem that says the name has no effect, got %v", err)
			}
		})
	}
}

func TestTheSuggestedRoleMayHoldOperatorsOnlyPermissionsUnderAnyName(t *testing.T) {
	t.Parallel()
	for _, name := range []string{"admin", "platform-operator"} {
		t.Run(name, func(t *testing.T) {
			t.Parallel()
			r, err := tryRig(t, rigOptions{
				adminOpts:   []admin.Option{admin.OperatorsOnly()},
				declare:     operatorsOnlyAreaOnly,
				definitions: defineAdmin(flowcatalyst.AdminRoleName(name)),
				build:       []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(map[string][]string{"orders:" + name: {"orders:admin:*:*"}}))},
			})
			if err != nil {
				t.Fatalf("the guard refused the suggested role: %v", err)
			}
			roles := r.fc.Catalog().Roles()
			if len(roles) != 1 || roles[0].ClientManaged || !slices.Contains(roles[0].Permissions, "orders:admin:console:view") {
				t.Errorf("roles %+v", roles)
			}
		})
	}
}

// ---- the warning of WithoutAdminRole ----

type logBuffer struct {
	mu sync.Mutex
	b  strings.Builder
}

func (l *logBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *logBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

const noConsoleViewWarning = "no declared role grants 'orders:admin:console:view'"

func TestWithoutAdminRoleWarnsWhenNoDeclaredRoleGrantsTheConsole(t *testing.T) {
	t.Parallel()
	mapping := func(m map[string][]string) []flowcatalyst.BuildOption {
		return []flowcatalyst.BuildOption{flowcatalyst.WithPermissionMapping(auth.ByRole(m))}
	}
	for _, c := range []struct {
		name   string
		define func(fc *flowcatalyst.Declared)
		build  []flowcatalyst.BuildOption
		warn   bool
	}{
		{"no role at all", func(*flowcatalyst.Declared) {}, nil, true},
		{"a role without it", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Permission("orders:sales:order:read", "Read orders").Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read"))
		}, nil, true},
		{"a mapping that gives it to nobody declared", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Permission("orders:sales:order:read", "Read orders").Role(flowcatalyst.NewRole("clerk", "orders:sales:order:read"))
		}, mapping(map[string][]string{"orders:other": {"orders:admin:console:view"}}), true},
		{"a role that grants it by its definition", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Permission("orders:admin:console:view", "Open").Role(flowcatalyst.NewRole("operator", "orders:admin:console:view"))
		}, nil, false},
		{"a role that gets it from the mapping", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Permission("orders:sales:order:read", "Read orders").Role(flowcatalyst.NewRole("operator", "orders:sales:order:read"))
		}, mapping(map[string][]string{"orders:operator": {"orders:admin:console:view"}}), false},
		{"a role that gets it through a wildcard in the mapping", func(fc *flowcatalyst.Declared) {
			fc.Definitions("own").Permission("orders:sales:order:read", "Read orders").Role(flowcatalyst.NewRole("operator", "orders:sales:order:read"))
		}, mapping(map[string][]string{"orders:operator": {"orders:admin:*:*"}}), false},
	} {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			logs := &logBuffer{}
			startRig(t, rigOptions{
				logger: slog.New(slog.NewTextHandler(logs, nil)),
				definitions: func(fc *flowcatalyst.Declared, adm *admin.Declared) {
					c.define(fc)
					fc.AdminDefinitions(adm, flowcatalyst.WithoutAdminRole())
				},
				build: c.build,
			})
			text := logs.String()
			if got := strings.Count(text, noConsoleViewWarning); (got == 1) != c.warn || got > 1 {
				t.Errorf("warning logged %d times, want warn=%v:\n%s", got, c.warn, text)
			}
			if c.warn && !strings.Contains(text, "level=WARN") {
				t.Errorf("not logged at WARN:\n%s", text)
			}
		})
	}
}

func TestTheSuggestedRoleOrNoOptionNeverWarns(t *testing.T) {
	t.Parallel()
	for _, opts := range [][]flowcatalyst.AdminDefinitionsOption{nil, {flowcatalyst.AdminRoleName("platform-operator")}} {
		logs := &logBuffer{}
		startRig(t, rigOptions{logger: slog.New(slog.NewTextHandler(logs, nil)), definitions: defineAdmin(opts...)})
		if strings.Contains(logs.String(), noConsoleViewWarning) {
			t.Errorf("warned although the suggested role is added:\n%s", logs.String())
		}
	}
}
