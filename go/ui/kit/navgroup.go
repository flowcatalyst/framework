package kit

import (
	"encoding/json"
	"strings"
)

// NavClosedStorageKey is the localStorage key holding the JSON array of the
// collapsed sidebar sections' keys (per viewer; the "fc:" prefix matches the
// SPA's own keys).
const NavClosedStorageKey = "fc:nav-closed"

// NavSignal is the Datastar signal holding a collapsible section's closed state,
// derived from its Key ("identity-access" -> "navClosedIdentityAccess").
func NavSignal(key string) string {
	var b strings.Builder
	b.WriteString("navClosed")
	for _, part := range strings.FieldsFunc(key, func(r rune) bool { return r < '0' || (r > '9' && r < 'A') || (r > 'Z' && r < 'a') || r > 'z' }) {
		b.WriteString(strings.ToUpper(part[:1]) + part[1:])
	}
	return b.String()
}

// NavSectionID is the id of a collapsible section's items container, the
// target of its header button's aria-controls.
func NavSectionID(key string) string { return "fc-nav-sec-" + key }

// NavGroupHasCurrent reports whether the group contains the page's own entry.
// Such a group is never collapsed: it opens on load whatever is stored, and its
// header does not toggle, so the active item can never be hidden.
func NavGroupHasCurrent(g NavGroup) bool {
	for _, it := range g.Items {
		if it.Current {
			return true
		}
		for _, c := range it.Children {
			if c.Current {
				return true
			}
		}
	}
	return false
}

func collapsibleGroups(groups []NavGroup) []NavGroup {
	var out []NavGroup
	for _, g := range groups {
		if g.Key != "" {
			out = append(out, g)
		}
	}
	return out
}

// NavSignalsJSON is the nav's data-signals value: every collapsible section
// starts open (the default without storage), so a page renders correctly with
// no stored state.
func NavSignalsJSON(groups []NavGroup) string {
	m := map[string]bool{}
	for _, g := range collapsibleGroups(groups) {
		m[NavSignal(g.Key)] = false
	}
	b, _ := json.Marshal(m)
	return string(b)
}

func jsString(s string) string {
	b, _ := json.Marshal(s)
	return strings.ReplaceAll(string(b), "'", `'`)
}

// NavInitExpr restores the stored collapsed sections on load. Sections holding
// the current page are skipped (they stay open). Every storage access sits in
// try/catch: without storage (private window, blocked) nothing happens and
// every section stays open.
func NavInitExpr(groups []NavGroup) string {
	var sets strings.Builder
	for _, g := range collapsibleGroups(groups) {
		if NavGroupHasCurrent(g) {
			continue
		}
		sets.WriteString("$" + NavSignal(g.Key) + " = c.includes(" + jsString(g.Key) + "); ")
	}
	if sets.Len() == 0 {
		return ""
	}
	return "try { const c = JSON.parse(localStorage.getItem(" + jsString(NavClosedStorageKey) + ") || '[]'); if (Array.isArray(c)) { " + sets.String() + "} } catch (e) {}"
}

// NavToggleExpr flips one section and persists the set of closed sections.
func NavToggleExpr(key string) string {
	sig := "$" + NavSignal(key)
	return sig + " = !" + sig + "; try { const c = new Set(JSON.parse(localStorage.getItem(" + jsString(NavClosedStorageKey) + ") || '[]')); " +
		sig + " ? c.add(" + jsString(key) + ") : c.delete(" + jsString(key) + "); " +
		"localStorage.setItem(" + jsString(NavClosedStorageKey) + ", JSON.stringify([...c])) } catch (e) {}"
}

// NavOpenExpr is the show condition of a section's items. The icon-only
// sidebar always shows every item (the section headers are hidden there, so a
// closed section would strand its entries).
func NavOpenExpr(key string) string { return "$sidebarCollapsed || !$" + NavSignal(key) }

// NavClosedExpr is the show condition of a section's closed chevron.
func NavClosedExpr(key string) string { return "!$sidebarCollapsed && $" + NavSignal(key) }
