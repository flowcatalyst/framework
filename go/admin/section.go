package admin

import (
	"regexp"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// APIPrefix is the prefix every admin operation lives under.
const APIPrefix = "/api/admin/"

// PathPrefix is the prefix of every section's SPA route.
const PathPrefix = "/admin"

var (
	idPattern         = regexp.MustCompile(`^[a-z][a-z0-9-]*$`)
	permissionPattern = regexp.MustCompile(`^[^:\s]+:[^:\s]+:[^:\s]+:[^:\s]+$`)
)

// Section is one section of the application's admin area (admin contract section 2): a navigation
// entry, the permission that shows it, and the typed operations it serves. Build it with
// [NewSection] and the With methods; the start-up rules that need no application code are
// reported by [Section.Problems] when the section is registered, so a mistake fails start-up and
// the openapi command instead of panicking while package variables initialise.
type Section struct {
	// ID is [a-z][a-z0-9-]*, unique; the UI's page registry uses the same id.
	ID string
	// Title is the navigation label.
	Title string
	// Path is the SPA route, /admin or under it.
	Path string
	// Permission shows the section and is required by every operation of it; four ':'-separated
	// segments.
	Permission string
	// Icon is a Lucide icon name for the navigation ("i-lucide-users"); empty for none.
	Icon string
	// Description is one sentence; empty for none.
	Description string
	// Operations are the typed operations the section serves; each requires a permission and lives
	// under /api/admin/.
	Operations []httpfw.Described
}

// NewSection returns a section without an icon, a description or operations.
func NewSection(id, title, path, permission string) Section {
	return Section{ID: id, Title: title, Path: path, Permission: permission}
}

// WithIcon returns a copy with a Lucide icon name for the navigation.
func (s Section) WithIcon(icon string) Section { s.Icon = icon; return s }

// WithDescription returns a copy with a description.
func (s Section) WithDescription(text string) Section { s.Description = text; return s }

// Serving returns a copy serving these operations (replacing any given before).
func (s Section) Serving(ops ...httpfw.Described) Section {
	s.Operations = slices.Clone(ops)
	return s
}

// Problems lists what breaks the rules a section can be checked for alone (admin contract
// section 2, rules 1, 2 without the application check, and 4, and the id and path formats). The
// registry-wide rules are checked by [Problems].
func (s Section) Problems() []string {
	var out []string
	if !idPattern.MatchString(s.ID) {
		out = append(out, "the id must match [a-z][a-z0-9-]*")
	}
	if strings.TrimSpace(s.Title) == "" {
		out = append(out, "the title is blank")
	}
	if s.Path != PathPrefix && !strings.HasPrefix(s.Path, PathPrefix+"/") {
		out = append(out, "the path must be "+PathPrefix+" or under it")
	}
	if !permissionPattern.MatchString(s.Permission) {
		out = append(out, "the permission must have four ':'-separated segments")
	}
	for _, op := range s.Operations {
		info := op.Info()
		label := info.ID
		if label == "" {
			label = info.Method + " " + info.Pattern
		}
		switch {
		case info.Permission.IsZero():
			out = append(out, "operation "+label+" has no required permission")
		case !permissionPattern.MatchString(info.Permission.String()):
			out = append(out, "operation "+label+": the permission must have four ':'-separated segments")
		}
		if !strings.HasPrefix(info.Pattern, APIPrefix) {
			out = append(out, "operation "+label+" must be under "+APIPrefix)
		}
	}
	return out
}

// permission returns the section's permission as a value; the zero Permission when it is not one.
func (s Section) permission() fw.Permission {
	p, err := fw.ParsePermission(s.Permission)
	if err != nil {
		return fw.Permission{}
	}
	return p
}

// permissionOf parses a permission built from an application code, which cannot be malformed.
func permissionOf(text string) fw.Permission {
	p, err := fw.ParsePermission(text)
	if err != nil {
		panic("admin: " + err.Error()) // an application code is never blank and has no ':'
	}
	return p
}
