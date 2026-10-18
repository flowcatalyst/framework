package flowcatalyst

import (
	"strings"

	"github.com/flowcatalyst/framework/go/admin"
)

// adminModule is the module name problems and duplicates of the admin definitions are attributed to.
const adminModule = "admin"

// AdminDefinitions adds the definitions the admin sections need on the platform (admin contract
// section 11): every permission the registered sections require, and one suggested role, admin (on the
// platform {app}:admin), that grants all of them. The permissions are listed one by one: the sync
// accepts no wildcard in a role. Permissions reach the platform only through roles, so the role is
// what makes them exist.
//
// Call it at declaration time, after every admin section is registered. An application that
// declares a role named admin itself keeps it as declared and gets nothing added: it owns the
// decision. With no admin sections registered nothing is added.
func (d *Declared) AdminDefinitions(adm *admin.Declared) {
	sections := adm.Sections()
	if len(sections) == 0 {
		return
	}
	declaredPermissions := map[string]bool{}
	for _, c := range d.reg.all() {
		switch def := c.def.(type) {
		case Role:
			if def.Name == "admin" {
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
	defs.Role(NewRole("admin", codes...).
		WithDisplayName("Administrator").
		WithDescription("Opens the application's admin area and every section of it."))
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
