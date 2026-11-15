package flowcatalyst

import (
	"strings"
	"unicode"
	"unicode/utf8"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
)

// adminModule is the module name problems and duplicates of the admin definitions are attributed to.
const adminModule = "admin"

// AdminDefinitionsOption changes what [Declared.AdminDefinitions] adds.
type AdminDefinitionsOption func(*adminDefinitionsOptions)

type adminDefinitionsOptions struct {
	roleName       string
	roleNameSet    bool
	displayName    string
	displayNameSet bool
	withoutRole    bool
}

// AdminRoleName declares the suggested role under this name instead of admin (Go; Java to follow).
// The name goes through the same check as any role name, so an empty or malformed one is a
// start-up problem and never falls back to admin. Renaming the role creates a new role on the
// platform: people who held the old one must be given the new one. An application that declares a
// role of this name itself keeps it and gets nothing added.
func AdminRoleName(name string) AdminDefinitionsOption {
	return func(o *adminDefinitionsOptions) { o.roleName, o.roleNameSet = name, true }
}

// AdminRoleDisplayName sets the suggested role's display name. Without it the display name is
// Administrator for the role admin and otherwise derived from the name: hyphens become spaces and
// the first letter is upper-case (platform-operator gives Platform operator).
func AdminRoleDisplayName(text string) AdminDefinitionsOption {
	return func(o *adminDefinitionsOptions) { o.displayName, o.displayNameSet = text, true }
}

// WithoutAdminRole declines the suggested role (Go; Java to follow): the permissions the sections
// need are still defined, so the application's own role can grant them, but no role is added. With
// AdminRoleName or AdminRoleDisplayName it is a start-up problem (the name would have no effect).
// When no declared role grants {app}:admin:console:view, build logs a warning that nobody can open
// the admin area unless the permission mapping gives it to a role declared elsewhere.
func WithoutAdminRole() AdminDefinitionsOption {
	return func(o *adminDefinitionsOptions) { o.withoutRole = true }
}

// AdminDefinitions adds the definitions the admin sections need on the platform (admin contract
// section 11): every permission the registered sections require, and one suggested role, admin (on the
// platform {app}:admin), that grants all of them. The permissions are listed one by one: the sync
// accepts no wildcard in a role. Permissions reach the platform only through roles, so the role is
// what makes them exist. The options name the role ([AdminRoleName], [AdminRoleDisplayName]) or
// decline it ([WithoutAdminRole]); with none the behaviour is the one described here.
//
// The registry is also what start-up checks client-managed roles against: a client-managed role that
// the role's definition or the permission mapping ([WithPermissionMapping]) gives the permission of
// an operators-only section (admin contract section 3a) fails start-up, naming the role and the
// permission, and so does the sync. The suggested role is not client-managed, so it may hold them.
//
// Call it at declaration time, after every admin section is registered. An application that
// declares a role of the suggested name itself keeps it as declared and gets nothing added: it owns
// the decision. With no admin sections registered nothing is added.
func (d *Declared) AdminDefinitions(adm *admin.Declared, opts ...AdminDefinitionsOption) {
	d.adm = adm // also when nothing is added: the operators-only check needs the registry
	var o adminDefinitionsOptions
	for _, opt := range opts {
		opt(&o)
	}
	if o.withoutRole && (o.roleNameSet || o.displayNameSet) {
		d.adminProblems = append(d.adminProblems, fw.Problem{
			Module: adminModule, Key: "AdminDefinitions",
			Message: "WithoutAdminRole() adds no role, so AdminRoleName and AdminRoleDisplayName have no effect; use one or the other",
		})
	}
	name := "admin"
	if o.roleNameSet {
		name = o.roleName
	}
	sections := adm.Sections()
	if len(sections) == 0 {
		return
	}
	declaredPermissions := map[string]bool{}
	for _, c := range d.reg.all() {
		switch def := c.def.(type) {
		case Role:
			if !o.withoutRole && def.Name == name {
				return
			}
		case Permission:
			declaredPermissions[def.Code] = true
		}
	}
	titles := map[string]string{}
	for _, s := range sections {
		titles[s.ID] = s.Title
	}
	defs := d.Definitions(adminModule)
	var codes []string
	for _, p := range admin.Permissions(sections) {
		codes = append(codes, p.String())
		if !declaredPermissions[p.String()] {
			defs.Permission(p.String(), describePermission(p.String(), titles))
		}
	}
	if o.withoutRole {
		d.adminWithoutRole = true
		return
	}
	display := o.displayName
	if !o.displayNameSet {
		display = adminRoleDisplayName(name)
	}
	defs.Role(NewRole(name, codes...).
		WithDisplayName(display).
		WithDescription("Opens the application's admin area and every section of it."))
}

// adminRoleDisplayName is Administrator for admin and otherwise the name with hyphens as spaces
// and the first letter upper-case.
func adminRoleDisplayName(name string) string {
	if name == "admin" {
		return "Administrator"
	}
	text := strings.ReplaceAll(name, "-", " ")
	r, size := utf8.DecodeRuneInString(text)
	if size == 0 {
		return text
	}
	return string(unicode.ToUpper(r)) + text[size:]
}

// consoleViewWarning is the warning of a module built with [WithoutAdminRole] when no declared role
// grants the permission that opens the admin area, or "" when one does or the option was not used.
// A role grants it by its definition or, with [WithPermissionMapping], through the mapping run for a
// principal that holds only that role, under the platform's wildcard rule. It is a warning because
// the mapping may give the permission to a role this application does not declare, which the
// framework cannot list.
func (g operatorsOnlyGuard) consoleViewWarning(roles []contributedRole) string {
	if g.adm == nil {
		return ""
	}
	view := admin.ConsoleView(g.adm.Application())
	want, err := fw.ParsePermission(view)
	if err != nil {
		return ""
	}
	app := g.adm.Application().String()
	grants := func(held []string) bool {
		var perms []fw.Permission
		for _, h := range held {
			if p, err := fw.ParsePermission(h); err == nil {
				perms = append(perms, p)
			}
		}
		return fw.ContextFor(fw.PlatformClient).WithPermissions(perms...).HasPermission(want)
	}
	for _, c := range roles {
		if grants(c.role.Permissions) {
			return ""
		}
		if g.mapping != nil && grants(g.mapping(auth.Principal{Roles: []string{app + ":" + c.role.Name}})) {
			return ""
		}
	}
	return "flowcatalyst: WithoutAdminRole() is set and no declared role grants '" + view + "', so nobody can open the admin area " +
		"unless the application's permission mapping gives that permission to a role declared elsewhere (for example a platform role)"
}

func describePermission(code string, titles map[string]string) string {
	parts := strings.Split(code, ":")
	section, action := "", ""
	if len(parts) > 2 {
		section = parts[2]
	}
	if len(parts) > 3 {
		action = parts[3]
	}
	if section == "console" {
		return "Open the admin area"
	}
	title := titles[section]
	if title == "" {
		title = section
	}
	title = strings.ToLower(title)
	switch action {
	case "view":
		return "View " + title + " in the admin area"
	case "manage":
		return "Manage " + title + " in the admin area"
	}
	return action + " " + title + " in the admin area"
}
