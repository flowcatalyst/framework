package views

import (
	"maps"
	"net/http"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/kit"
)

// theme reads the theme cookies (contract section 10): fc_theme is the explicit choice (light, dark
// or system, anything else is system), fc_scheme the system scheme the page last observed. The page
// renders dark for an explicit dark, and in system mode when the browser last reported dark, so a
// dark page does not flash light before Datastar runs.
func theme(r *http.Request) (choice kit.ThemeChoice, dark bool) {
	choice = kit.ThemeSystem
	switch kit.ThemeChoice(cookieValue(r, kit.ThemeCookie)) {
	case kit.ThemeLight:
		choice = kit.ThemeLight
	case kit.ThemeDark:
		choice = kit.ThemeDark
	case kit.ThemeSystem:
	}
	switch choice {
	case kit.ThemeDark:
		dark = true
	case kit.ThemeSystem:
		dark = cookieValue(r, kit.SchemeCookie) == "dark"
	case kit.ThemeLight:
	}
	return choice, dark
}

// sidebarCollapsed reads fc_sidebar.
func sidebarCollapsed(r *http.Request) bool { return cookieValue(r, kit.SidebarCookie) == "collapsed" }

// cookieValue is the value of the named request cookie, "" when there is none.
func cookieValue(r *http.Request, name string) string {
	if c, err := r.Cookie(name); err == nil && c != nil {
		return c.Value
	}
	return ""
}

// htmlAttrs are the attributes of <html>: the theme's, and with a shell the shell's.
func htmlAttrs(r *http.Request, withShell bool) (templ.Attributes, kit.ThemeChoice) {
	choice, dark := theme(r)
	attrs := kit.ThemeHTMLAttrs(choice, dark)
	if withShell {
		maps.Copy(attrs, kit.ShellHTMLAttrs(sidebarCollapsed(r)))
	}
	return attrs, choice
}
