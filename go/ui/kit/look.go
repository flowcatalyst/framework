package kit

import (
	"context"
	"maps"
	"net/http"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/assets"
	"github.com/flowcatalyst/framework/go/ui/look"
)

// Look is this module's kit as the component kit the views render documents with: the default of
// views.New. An application with its own copy of the kit (its own import path) declares the same type
// in its copy and passes it with views.WithLook. This file and look.templ are the whole seam: the
// stylesheet, the render context, the attributes of <html>, the bare <main> wrapper and the flash
// stack and messages.
type Look struct{}

var _ look.Look = Look{}

// Stylesheet is the module's stylesheet, "kit.css".
func (Look) Stylesheet() string { return "kit.css" }

// Context puts the served icon sprite's URL in the context, where the kit's icons read it.
func (Look) Context(ctx context.Context, a *assets.Assets) context.Context {
	return WithSpriteURL(ctx, a.SpriteURL())
}

// HTMLAttrs are the theme attributes for the request's cookies and, with a shell, the shell's
// (the sidebar's collapsed state from its cookie).
func (Look) HTMLAttrs(r *http.Request, shell bool) templ.Attributes {
	choice, dark := ThemeFromRequest(r)
	attrs := ThemeHTMLAttrs(choice, dark)
	if shell {
		maps.Copy(attrs, ShellHTMLAttrs(SidebarCollapsed(r)))
	}
	return attrs
}

// Main is the wrapper of a document without a shell.
func (Look) Main() templ.Component { return documentMain() }

// FlashStack is the kit's flash stack with this id.
func (Look) FlashStack(id string) templ.Component { return FlashStack(FlashStackProps{ID: id}) }

// FlashMessage is the kit's flash message.
func (Look) FlashMessage(m look.Flash) templ.Component { return FlashMessage(m) }

// ThemeFromRequest reads the theme cookies (contract section 10): fc_theme is the explicit choice
// (light, dark or system, anything else is system), fc_scheme the system scheme the page last
// observed. dark is true for an explicit dark, and in system mode when the browser last reported
// dark, so a dark page does not flash light before Datastar runs.
func ThemeFromRequest(r *http.Request) (choice ThemeChoice, dark bool) {
	choice = ThemeSystem
	switch ThemeChoice(cookieValue(r, ThemeCookie)) {
	case ThemeLight:
		choice = ThemeLight
	case ThemeDark:
		choice = ThemeDark
	case ThemeSystem:
	}
	switch choice {
	case ThemeDark:
		dark = true
	case ThemeSystem:
		dark = cookieValue(r, SchemeCookie) == "dark"
	case ThemeLight:
	}
	return choice, dark
}

// SidebarCollapsed reads the fc_sidebar cookie.
func SidebarCollapsed(r *http.Request) bool { return cookieValue(r, SidebarCookie) == "collapsed" }

// cookieValue is the value of the named request cookie, "" when there is none.
func cookieValue(r *http.Request, name string) string {
	if c, err := r.Cookie(name); err == nil && c != nil {
		return c.Value
	}
	return ""
}
