package flowcatalyst

import (
	"fmt"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
)

// WithPermissionMapping gives the module the application's permission mapping, the same value the
// auth module is given (auth.ByRole and the like). With it, and with admin sections registered through
// [Declared.AdminDefinitions], start-up refuses a client-managed role that the mapping gives the
// permission of an operators-only admin section: a role whose permissions client administrators may
// assign must not open a section that shows every client's data (admin contract section 3a). The
// mapping is run for a principal that holds only the role, so a mapping that looks at anything else
// about the principal cannot be checked; wildcards are matched by the platform's rule.
func WithPermissionMapping(m auth.PermissionMapper) BuildOption {
	return func(c *buildConfig) { c.mapping = m }
}

// operatorsOnlyGuard is what Build and Sync check about client-managed roles.
type operatorsOnlyGuard struct {
	adm     *admin.Declared
	mapping auth.PermissionMapper
}

type contributedRole struct {
	module string
	role   Role
}

// problems lists, for every client-managed role, each permission of an operators-only section that
// its definitions or the permission mapping give it, with the role and the permission named.
func (g operatorsOnlyGuard) problems(roles []contributedRole) []fw.Problem {
	if g.adm == nil {
		return nil
	}
	sections := g.adm.Sections()
	if len(admin.OperatorsOnlyPermissions(sections)) == 0 {
		return nil
	}
	app := g.adm.Application().String()
	var out []fw.Problem
	for _, c := range roles {
		if !c.role.ClientManaged {
			continue
		}
		full := app + ":" + c.role.Name
		sources := []struct {
			via  string
			held []string
		}{{"the role's definition", c.role.Permissions}}
		if g.mapping != nil {
			sources = append(sources, struct {
				via  string
				held []string
			}{"the application's permission mapping", g.mapping(auth.Principal{Roles: []string{full}})})
		}
		reported := map[string]bool{}
		for _, src := range sources {
			for _, perm := range admin.OperatorsOnlyConflicts(sections, src.held) {
				if reported[perm.String()] {
					continue
				}
				reported[perm.String()] = true
				out = append(out, fw.Problem{
					Module: c.module, Key: "role " + c.role.Name,
					Message: fmt.Sprintf("role '%s' is client-managed (a client's own administrators may assign it) but %s grants the operators-only admin permission '%s'%s; "+
						"a partition holds the rows of every client. Give the permission to a role that is not client-managed",
						full, src.via, perm, matchingNote(sections, src.held, perm)),
				})
			}
		}
	}
	return out
}

// matchingNote says which held pattern grants the permission when it is not the permission itself
// ("(through 'orders:admin:*:*')").
func matchingNote(sections []admin.Section, held []string, perm fw.Permission) string {
	for _, h := range held {
		if h == perm.String() {
			return ""
		}
	}
	for _, h := range held {
		if slices.ContainsFunc(admin.OperatorsOnlyConflicts(sections, []string{h}), func(p fw.Permission) bool { return p == perm }) {
			return " (through '" + h + "')"
		}
	}
	return ""
}

// rolesOf lists the roles of the contributions, with the module that contributed each.
func rolesOf(list []contribution) []contributedRole {
	var out []contributedRole
	for _, c := range list {
		if r, ok := c.def.(Role); ok {
			out = append(out, contributedRole{module: c.module, role: r})
		}
	}
	return out
}

// refuse is the error of a sync the guard refuses.
func refuse(problems []fw.Problem) error {
	var lines []string
	for _, p := range problems {
		lines = append(lines, p.Message)
	}
	return fmt.Errorf("flowcatalyst: the definitions are not sent: %s", strings.Join(lines, "; "))
}
