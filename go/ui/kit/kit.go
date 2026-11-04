// Package kit is the templ implementation of the FlowCatalyst UI kit
// (flowcatalyst-framework/ui-kit/components/*.md). One templ function per
// spec; its Props struct's fields mirror the spec's Props table (Go field =
// the camelCase prop name with a capital first letter; the json tag is the
// prop name, so the language-neutral fixtures decode straight into it).
//
// Conventions (docs/contracts/server-rendered-ui.md):
//
//   - Markup and class strings are the spec's, unchanged. A component never
//     merges classes; Class is appended after the spec's classes, so callers
//     override with `[&]:` variants as the spec says.
//   - Attrs (templ.Attributes) is passed to the component's root (or the part
//     the spec names, e.g. the <input> inside a checkbox) through [Attrs],
//     which refuses a malformed or on* name, skips nil and false, writes true
//     as a bare attribute and checks the value of a URL attribute.
//   - Slots are templ children ({ children... }) for the default slot and
//     templ.Component fields for named slots.
//   - Ids and signal names end up inside Datastar expressions (which are
//     JavaScript), so they are validated: an invalid one panics, because it is
//     a programming error and must never reach a page with user data in it.
//     User data never goes into a data-* expression; it goes into plain
//     attributes or text, which templ escapes.
//   - Every href, action and src a component renders from a prop goes through
//     [URL]; an unknown variant or size panics (never a default); [Raw] is the
//     one way to write unescaped markup. A refusal is a panic whose message
//     names the rule of docs/contracts/server-rendered-ui.md section 3.
package kit

import (
	"context"
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/i18n"
)

// DefaultSpriteURL is the icon sprite path the specs and fixtures use. Pages
// put the served URL in the context with WithSpriteURL.
const DefaultSpriteURL = "/icons/lucide-sprite.svg"

type spriteKey struct{}

// WithSpriteURL sets the URL icons reference (<use href="URL#i-name">).
func WithSpriteURL(ctx context.Context, url string) context.Context {
	return context.WithValue(ctx, spriteKey{}, url)
}

func spriteURL(ctx context.Context) string {
	if u, ok := ctx.Value(spriteKey{}).(string); ok && u != "" {
		return u
	}
	return DefaultSpriteURL
}

// IconHref is the sprite reference for a Lucide icon name ("x", "search").
func IconHref(ctx context.Context, name string) string {
	return spriteURL(ctx) + "#i-" + name
}

// cx joins class lists, skipping empty parts.
func cx(parts ...string) string {
	out := make([]string, 0, len(parts))
	for _, p := range parts {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return strings.Join(out, " ")
}

var (
	idPattern     = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_-]*$`)
	signalPattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9]*$`)
	kebabBoundary = regexp.MustCompile(`([a-z0-9])([A-Z])`)
)

// ID validates an element id that a Datastar expression or an aria reference
// will contain. It panics on anything else (programming error).
func ID(id string) string {
	if !idPattern.MatchString(id) {
		panic(fmt.Sprintf("kit: invalid element id %q (want [A-Za-z][A-Za-z0-9_-]*)", id))
	}
	return id
}

// Signal validates a camelCase Datastar signal name.
func Signal(name string) string {
	if !signalPattern.MatchString(name) {
		panic(fmt.Sprintf("kit: invalid signal name %q (want camelCase [A-Za-z][A-Za-z0-9]*)", name))
	}
	return name
}

// SignalAttr is the kebab-case form of a signal name for attribute keys
// (`data-bind:` + SignalAttr("qPools") = "data-bind:q-pools"): HTML lower-cases
// attribute names, Datastar maps kebab-case back to camelCase.
func SignalAttr(name string) string {
	return strings.ToLower(kebabBoundary.ReplaceAllString(Signal(name), "$1-$2"))
}

// ShowModal is the Datastar expression that opens the <dialog> with this id
// (dialog.md: Datastar handlers, not commandfor, for browser support).
func ShowModal(id string) string {
	return "document.getElementById('" + ID(id) + "').showModal()"
}

// CloseDialog closes the dialog containing the element (close/cancel buttons).
const CloseDialog = "el.closest('dialog').close()"

// LightDismiss closes a full-viewport dialog when its mask is clicked.
const LightDismiss = "evt.target === el && el.close()"

// OpenModal returns the attributes a trigger needs to open dialog id:
// the click handler and aria-haspopup.
func OpenModal(id string) templ.Attributes {
	return templ.Attributes{"data-on:click": ShowModal(id), "aria-haspopup": "dialog"}
}

// SubmitForm is the submit handler of a form that posts through Datastar
// (form-dialog.md, confirm-dialog.md): the form's own fields go to its own
// action, network retries are off (a POST that reached the server must not be
// sent twice). Reading el.action keeps the URL out of the expression.
const SubmitForm = "@post(el.action, {contentType: 'form', retryMaxCount: 0})"

// SubmitConfirm submits a confirm dialog's form and closes the dialog; the
// outcome is reported in the page's flash stack (confirm-dialog.md).
const SubmitConfirm = SubmitForm + "; el.closest('dialog').close()"

// OpenConfirm is the trigger expression for a confirm dialog shared by many
// records (confirm-dialog.md, "One dialog for many records"): the trigger's
// data-action attribute (a plain attribute, so a URL never enters the
// expression) becomes the form's action, then the dialog opens.
func OpenConfirm(id string) string {
	return "const d = document.getElementById('" + ID(id) + "'); d.querySelector('form').action = el.dataset.action; d.showModal()"
}

var pathPattern = regexp.MustCompile(`^/[A-Za-z0-9/_-]*$`)

// Path validates a local path that a Datastar expression will contain (an
// autocomplete's source). It panics on anything else (programming error),
// including a path starting with "//", which a fetch would send to another
// host.
func Path(p string) string {
	if !pathPattern.MatchString(p) || strings.HasPrefix(p, "//") {
		panic(fmt.Sprintf("kit: invalid path %q (want /[A-Za-z0-9/_-]*, not starting with //)", p))
	}
	return p
}

// ValidID reports whether id may be used as an element id or inside a
// Datastar expression (the rule ID enforces). Handlers check ids that come
// from a request with it before they reach ID.
func ValidID(id string) bool { return idPattern.MatchString(id) }

var recordIDPattern = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

// ValidRecordID reports whether id may be a record's id in a URL path: letters,
// digits, '_' and '-'. Unlike ValidID it allows a leading digit, because an
// untyped TSID (an event's, a dispatch job's) can start with one, and the
// ones made now do. Such an id is never an element id or part of a Datastar
// expression: build those from a prefix ("dj-" + id).
func ValidRecordID(id string) bool { return recordIDPattern.MatchString(id) }

// orDefault returns v, or def when v is empty.
func orDefault[T ~string](v, def T) T {
	if v == "" {
		return def
	}
	return v
}

// BlockedURL is what [URL] makes of a refused URL.
const BlockedURL = "about:invalid#blocked"

// URL applies the URL check of the contract (section 3 rule 3) to a value that
// becomes an href, action, formaction or src: characters up to 0x20 and 0x7f
// are ignored when the scheme is found (the part before the first ':' that
// comes before any '/', '?' or '#'); no scheme, or http, https, mailto or tel
// (any case), keeps the URL unchanged; any other scheme (javascript:, data:,
// vbscript:, ...) gives [BlockedURL].
func URL(u string) string {
	compact := make([]byte, 0, len(u))
	for i := range len(u) {
		if c := u[i]; c > 0x20 && c != 0x7f {
			compact = append(compact, c)
		}
	}
	s := string(compact)
	end := len(s)
	if i := strings.IndexAny(s, "/?#"); i >= 0 {
		end = i
	}
	colon := strings.IndexByte(s[:end], ':')
	if colon < 0 {
		return u
	}
	switch strings.ToLower(s[:colon]) {
	case "http", "https", "mailto", "tel":
		return u
	}
	return BlockedURL
}

// SafeURL is [URL] as a templ.SafeURL, for an href, action or src attribute:
// templ's own check (which also admits ftp) does not run on a SafeURL, so the
// contract's check is the only one.
func SafeURL(u string) templ.SafeURL { return templ.SafeURL(URL(u)) }

// Raw is the one way the kit writes unescaped markup (contract section 3
// rule 7): fixture slots, a sanitised Markdown rendering, a slot rendered by
// the kit itself. Never pass it request data.
func Raw(html string) templ.Component { return templ.Raw(html) }

var attrNamePattern = regexp.MustCompile(`^[A-Za-z_][A-Za-z0-9_.:-]*$`)

// urlAttrs are the attributes whose values pass through [URL] (contract
// section 3 rule 3), by lower-case name.
var urlAttrs = []string{"href", "action", "formaction", "src", "xlink:href"}

// Attrs checks a map of extra attributes before it is spread onto an element
// (contract section 3 rule 2) and returns the map to spread. A name that is
// not [A-Za-z_][A-Za-z0-9_.:-]* or that starts with "on" (any case) panics. A
// nil or false value is skipped, true stays (a bare attribute), the value of
// href, action, formaction, src and xlink:href goes through [URL]; numbers
// are written as decimal text. A value of any other type panics. Values are
// escaped when templ writes them.
func Attrs(in templ.Attributes) templ.Attributes {
	if len(in) == 0 {
		return in
	}
	out := make(templ.Attributes, len(in))
	for name, v := range in {
		if !attrNamePattern.MatchString(name) || strings.HasPrefix(strings.ToLower(name), "on") {
			panic(fmt.Sprintf("kit: attribute name %q refused (contract section 3 rule 2: want [A-Za-z_][A-Za-z0-9_.:-]*, not starting with on)", name))
		}
		var text string
		switch x := v.(type) {
		case nil:
			continue
		case bool:
			if x {
				out[name] = true
			}
			continue
		case *bool:
			if x != nil && *x {
				out[name] = true
			}
			continue
		case string:
			text = x
		case *string:
			if x == nil {
				continue
			}
			text = *x
		case templ.SafeURL:
			text = string(x)
		case int:
			text = strconv.Itoa(x)
		case int64:
			text = strconv.FormatInt(x, 10)
		case float64:
			text = strconv.FormatFloat(x, 'f', -1, 64)
		default:
			panic(fmt.Sprintf("kit: attribute %q has a value of type %T (contract section 3 rule 2: string, bool, nil or a number)", name, v))
		}
		if slices.Contains(urlAttrs, strings.ToLower(name)) {
			text = URL(text)
		}
		out[name] = text
	}
	return out
}

// pick returns the entry of table for v (def when v is empty). An unknown
// value panics: an unknown variant or size is refused, never replaced by a
// default (contract section 3 rules 5 and 6).
func pick[T ~string](what string, table map[T]string, v, def T) string {
	v = orDefault(v, def)
	s, ok := table[v]
	if !ok {
		panic(fmt.Sprintf("kit: unknown %s %q (contract section 3 rule 5: an unknown variant or size is refused)", what, string(v)))
	}
	return s
}

// oneOf returns v (def when empty) and panics unless it is one of allowed.
func oneOf[T ~string](what string, v, def T, allowed ...T) T {
	v = orDefault(v, def)
	if !slices.Contains(allowed, v) {
		panic(fmt.Sprintf("kit: unknown %s %q (contract section 3 rule 5: an unknown variant or size is refused)", what, string(v)))
	}
	return v
}

// jsq quotes s as a JavaScript single-quoted string literal, for a catalog
// message that goes into a Datastar expression.
func jsq(s string) string {
	var sb strings.Builder
	sb.WriteByte('\'')
	for _, r := range s {
		switch r {
		case '\\':
			sb.WriteString(`\\`)
		case '\'':
			sb.WriteString(`\'`)
		case '\n':
			sb.WriteString(`\n`)
		case '\r':
			sb.WriteString(`\r`)
		case ' ':
			sb.WriteString(` `)
		case ' ':
			sb.WriteString(` `)
		default:
			sb.WriteRune(r)
		}
	}
	sb.WriteByte('\'')
	return sb.String()
}

// jsMessage is the JavaScript expression for a catalog message whose
// placeholder arg is the JavaScript expression expr: the message's text as
// quoted string terms joined with + around expr, empty terms left out
// ("Remove {label}" with expr "t.textContent" is `'Remove ' + t.textContent`).
func jsMessage(ctx context.Context, key, arg, expr string) string {
	const mark = "\x00"
	parts := strings.Split(i18n.T(ctx, key, i18n.Args{arg: mark}), mark)
	var terms []string
	for i, part := range parts {
		if i > 0 {
			terms = append(terms, expr)
		}
		if part != "" {
			terms = append(terms, jsq(part))
		}
	}
	if len(terms) == 0 {
		return "''"
	}
	return strings.Join(terms, " + ")
}

// htmlMessage is a catalog message written as markup: the text is escaped,
// and each placeholder named in raw is replaced by that trusted markup (which
// the caller built from escaped values). A placeholder not in raw stays text.
func htmlMessage(ctx context.Context, key string, raw map[string]string) templ.Component {
	const mark = "\x00"
	args := i18n.Args{}
	for name := range raw {
		args[name] = mark + name + mark
	}
	text := templ.EscapeString(i18n.T(ctx, key, args))
	for name, html := range raw {
		text = strings.ReplaceAll(text, mark+name+mark, html)
	}
	return Raw(text)
}

// secretRefHelp is the secret reference input's help text: the catalog
// message with its two examples as <code> elements.
func secretRefHelp(ctx context.Context) templ.Component {
	return htmlMessage(ctx, "kit.secretref.help", map[string]string{
		"example":      "<code>billing-api-key</code>",
		"vaultExample": "<code>vault:billing-api-key</code>",
	})
}

// permissionRequired is the permission-denied dialog's "required permission"
// line: the catalog message with the permission as a <code> element.
func permissionRequired(ctx context.Context, permission string) templ.Component {
	return htmlMessage(ctx, "kit.permission_denied.required", map[string]string{
		"permission": `<code class="rounded bg-foreground/10 px-1.5 py-0.5 text-xs">` + templ.EscapeString(permission) + "</code>",
	})
}
