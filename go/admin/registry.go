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

	operatorsOnly bool

	mu       sync.Mutex
	sections []Section
	config   *configOptions
}

// Declare creates the registry for an application and registers the framework's own section,
// about, first (so the registry's rules cover the framework's operations too): the navigation and
// the about operation, both behind {app}:admin:console:view. The operations are declared in the
// OpenAPI document of web.
//
// With the option [OperatorsOnly] the whole area is for platform operators only (admin contract
// section 3a, rule 6): see there.
func Declare(web *httpfw.Declared, application fw.ApplicationCode, opts ...Option) *Declared {
	var cfg options
	for _, o := range opts {
		o(&cfg)
	}
	d := &Declared{web: web, application: application, operatorsOnly: cfg.operatorsOnly, ops: newOperations(application, cfg.operatorsOnly)}
	// the wire names of the document, which every implementation of the contract shares
	httpfw.NameType[SectionView](web, "AdminSectionView")
	httpfw.NameType[Navigation](web, "AdminNavigation")
	httpfw.NameType[Check](web, "AdminCheck")
	httpfw.NameType[About](web, "AdminAbout")
	about := NewSection("about", "About", "/admin/about", ConsoleView(application)).
		WithIcon("i-lucide-info").
		WithDescription("What this application is and whether it is healthy.").
		Serving(d.ops.navigation, d.ops.about)
	if d.operatorsOnly {
		about = about.OperatorsOnly()
	}
	d.Section(about)
	return d
}

type options struct{ operatorsOnly bool }

// Option changes how [Declare] declares the admin area.
type Option func(*options)

// OperatorsOnly declares the whole admin area for platform operators only (admin contract section
// 3a, rule 6; Go, Java to follow): the framework's own about section, with the navigation and the
// about operation, is operators-only (a caller whose tier is not ANCHOR gets 403 FORBIDDEN after the
// permission check), so {app}:admin:console:view becomes an operators-only permission that no
// client-managed role may carry; and every section registered with [Declared.Section] that is not
// itself operators-only is a start-up problem. The framework's config section and the platform
// sections of the flowcatalyst module are not operators-only, so an application that wants them
// cannot declare an operators-only area.
func OperatorsOnly() Option { return func(o *options) { o.operatorsOnly = true } }

// OperatorsOnly reports whether the area was declared with the option [OperatorsOnly], so a section
// package can mark its own section accordingly.
func (d *Declared) OperatorsOnly() bool { return d.operatorsOnly }

// ConsoleView is the permission that opens the admin area at all: {app}:admin:console:view.
func ConsoleView(application fw.ApplicationCode) string {
	return application.String() + ":admin:console:view"
}

// Web returns the HTTP declarations the registry declares its operations in, for packages that
// register wire-type names in the same document.
func (d *Declared) Web() *httpfw.Declared { return d.web }

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
		if d.operatorsOnly && !s.ForOperators {
			d.web.Problem("admin section '" + s.ID + "' is not operators-only, but the admin area is declared operators-only (admin.OperatorsOnly()): every section of it must be")
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
// platform's wildcard rule), and, for an operators-only section, only to a platform operator (tier
// ANCHOR). This is layer 2 of the permission model (admin contract section 3).
func VisibleTo(sections []Section, cx fw.Context) []Section {
	var out []Section
	for _, s := range sections {
		if s.ForOperators && !cx.IsPlatformOperator() {
			continue
		}
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

// OperatorsOnlyPermissions returns every permission of the operators-only sections (the section's
// and each operation's), sorted and without duplicates.
func OperatorsOnlyPermissions(sections []Section) []fw.Permission {
	var only []Section
	for _, s := range sections {
		if s.ForOperators {
			only = append(only, s)
		}
	}
	return Permissions(only)
}

// OperatorsOnlyConflicts returns the permissions of the operators-only sections that the held
// permission patterns grant, under the platform's wildcard rule (the one [fw.Context.HasPermission]
// applies: same number of segments, '*' in a held segment matches). A role that client
// administrators may assign must not grant any of them: a partition holds every client's rows. Held
// entries that are not permissions are ignored.
func OperatorsOnlyConflicts(sections []Section, held []string) []fw.Permission {
	var perms []fw.Permission
	for _, h := range held {
		if p, err := fw.ParsePermission(h); err == nil {
			perms = append(perms, p)
		}
	}
	if len(perms) == 0 {
		return nil
	}
	cx := fw.ContextFor(fw.PlatformClient).WithPermissions(perms...)
	var out []fw.Permission
	for _, p := range OperatorsOnlyPermissions(sections) {
		if cx.HasPermission(p) {
			out = append(out, p)
		}
	}
	return out
}
