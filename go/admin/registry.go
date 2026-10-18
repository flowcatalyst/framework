package admin

import (
	"slices"
	"strings"
	"sync"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// Declared is the admin registry of one application, from [Declare]. Packages register their
// sections with [Declared.Section] at declaration time; [Declared.Build] checks the whole registry
// and binds the skeleton's handlers.
type Declared struct {
	web         *httpfw.Declared
	application fw.ApplicationCode
	ops         operations

	mu       sync.Mutex
	sections []Section
	config   *configOptions
}

// Declare creates the registry for an application and registers the framework's own section,
// about, first (so the registry's rules cover the framework's operations too): the navigation and
// the about operation, both behind {app}:admin:console:view. The operations are declared in the
// OpenAPI document of web.
func Declare(web *httpfw.Declared, application fw.ApplicationCode) *Declared {
	d := &Declared{web: web, application: application, ops: newOperations(application)}
	// the wire names of the document, which every implementation of the contract shares
	httpfw.NameType[SectionView](web, "AdminSectionView")
	httpfw.NameType[Navigation](web, "AdminNavigation")
	httpfw.NameType[Check](web, "AdminCheck")
	httpfw.NameType[About](web, "AdminAbout")
	d.Section(NewSection("about", "About", "/admin/about", ConsoleView(application)).
		WithIcon("i-lucide-info").
		WithDescription("What this application is and whether it is healthy.").
		Serving(d.ops.navigation, d.ops.about))
	return d
}

// ConsoleView is the permission that opens the admin area at all: {app}:admin:console:view.
func ConsoleView(application fw.ApplicationCode) string {
	return application.String() + ":admin:console:view"
}

// Application returns the application code the registry serves; every admin permission starts
// with it.
func (d *Declared) Application() fw.ApplicationCode { return d.application }

// Section registers sections, in navigation order, and declares their operations in the OpenAPI
// document. A section that breaks a rule is reported by [Declared.Build] and by the openapi
// command.
func (d *Declared) Section(sections ...Section) {
	d.mu.Lock()
	d.sections = append(d.sections, sections...)
	d.mu.Unlock()
	for _, s := range sections {
		for _, p := range s.Problems() {
			d.web.Problem("admin section '" + s.ID + "': " + p)
		}
		if len(s.Operations) > 0 {
			d.web.Operations(s.Operations...)
		}
	}
}

// Sections returns the registered sections in registration order.
func (d *Declared) Sections() []Section {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.sections)
}

// VisibleTo returns the sections a caller may use: those whose permission the context holds (the
// platform's wildcard rule). This is layer 2 of the permission model (admin contract section 3).
func VisibleTo(sections []Section, cx fw.Context) []Section {
	var out []Section
	for _, s := range sections {
		if cx.HasPermission(s.permission()) {
			out = append(out, s)
		}
	}
	return out
}

// Permissions returns every permission the sections and their operations require, sorted and
// without duplicates: what the application declares to the platform and grants through a role.
func Permissions(sections []Section) []fw.Permission {
	seen := map[string]bool{}
	var out []string
	add := func(p string) {
		if p != "" && !seen[p] {
			seen[p] = true
			out = append(out, p)
		}
	}
	for _, s := range sections {
		add(s.Permission)
		for _, op := range s.Operations {
			add(op.Info().Permission.String())
		}
	}
	slices.Sort(out)
	perms := make([]fw.Permission, 0, len(out))
	for _, p := range out {
		if parsed, err := fw.ParsePermission(p); err == nil {
			perms = append(perms, parsed)
		}
	}
	return perms
}

// Problems lists what needs the whole registry (admin contract section 2, rules 2 and 3): section
// ids and paths are unique, and every permission starts with the application's code.
func Problems(sections []Section, application fw.ApplicationCode) []string {
	var out []string
	ids, paths := map[string]bool{}, map[string]bool{}
	for _, s := range sections {
		if ids[s.ID] {
			out = append(out, "admin section id '"+s.ID+"' is registered twice")
		}
		ids[s.ID] = true
		if paths[s.Path] {
			out = append(out, "admin path '"+s.Path+"' is used by two sections")
		}
		paths[s.Path] = true
	}
	for _, p := range Permissions(sections) {
		if !strings.HasPrefix(p.String(), application.String()+":") {
			out = append(out, "admin permission '"+p.String()+"' does not belong to application '"+application.String()+"'")
		}
	}
	return out
}
