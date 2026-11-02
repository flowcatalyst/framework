package kit

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/a-h/templ"
	"golang.org/x/net/html"
)

// The escaping suite: contract server-rendered-ui.md section 3, one test per
// rule, ported from Java's EscapingTest. Each test fails if the escaping or the
// check it exercises is taken out.

const xss = `<script>alert(1)</script>"'&<img src=x onerror=alert(2)>`

func assertNoLiveMarkup(t *testing.T, out string) {
	t.Helper()
	if n := danger(out, t); n != 0 {
		t.Errorf("%d live script/img/on* in:\n%s", n, out)
	}
}

func assertPanics(t *testing.T, what string, rule string, fn func()) {
	t.Helper()
	msg := refused(fn)
	if msg == "" {
		t.Errorf("%s: not refused", what)
		return
	}
	if !strings.Contains(msg, rule) {
		t.Errorf("%s: panic %q does not name %q", what, msg, rule)
	}
}

// Rule 1: every text and attribute value is escaped.
func TestTextInSlotsAndPropsIsEscaped(t *testing.T) {
	out := render(t, Alert(AlertProps{Variant: AlertDestructive, Role: "alert", Title: xss, Description: xss}), plain(xss))
	assertNoLiveMarkup(t, out)
	if !strings.Contains(out, "&lt;script&gt;alert(1)&lt;/script&gt;") {
		t.Errorf("script tag not escaped:\n%s", out)
	}
	doc := parse(t, out)
	if got := text(find(doc, "p")[0]); got != xss {
		t.Errorf("title text = %q, want the payload as text", got)
	}
	if !strings.Contains(text(doc[0]), xss) {
		t.Error("document text lacks the payload")
	}
}

func TestAttributeValuesAreEscapedAndCannotBreakOut(t *testing.T) {
	out := render(t, Input(InputProps{ID: "a", Name: "n", Value: xss, Placeholder: xss, Class: xss}), nil)
	assertNoLiveMarkup(t, out)
	doc := parse(t, out)
	in := find(doc, "input")
	if len(doc) != 1 || len(in) != 1 {
		t.Fatalf("want one input element, got %d top-level nodes", len(doc))
	}
	if attr(in[0], "value") != xss || attr(in[0], "placeholder") != xss || !strings.Contains(attr(in[0], "class"), xss) {
		t.Errorf("attribute values are not the literal payload: %v", in[0].Attr)
	}
}

func TestTextareaAndSelectAndTableTextAreEscaped(t *testing.T) {
	assertNoLiveMarkup(t, render(t, Textarea(TextareaProps{ID: "t", Name: "t", Value: xss, Placeholder: xss}), nil))
	assertNoLiveMarkup(t, render(t, Select(SelectProps{ID: "s", Name: "s", Options: []Option{{Value: xss, Label: xss}}, Selected: xss, Placeholder: xss}), nil))
	assertNoLiveMarkup(t, render(t, TD(TDProps{Class: xss}), plain(xss)))
}

func TestPickValueWithAKeyIsAQuotedLiteralInTheSignalAndJSONInTheMembers(t *testing.T) {
	value := "it's a\\b\r\n<c> \"d\""
	out := render(t, PickList(PickListProps{
		ID: "pick", Signal: "picked", FilterSignal: "pickQ", Name: "roles", ItemIDPrefix: "pk-",
		Items: []PickItem{{Value: value, Name: "N", Code: "c", Checked: true, Key: "k0", Group: "g"}},
	}), nil)
	assertNoLiveMarkup(t, out)
	doc := parse(t, out)
	if got, want := attr(find(doc, "form")[0], "data-signals"), `{picked: ['it\'s a\\b\r\n\x3cc> "d"'], pickQ: ''}`; got != want {
		t.Errorf("data-signals = %q, want %q", got, want)
	}
	var members, values string
	var boxID string
	walk(doc, func(n *html.Node) {
		if hasAttr(n, "data-members") {
			members = attr(n, "data-members")
		}
		if hasAttr(n, "data-value") {
			values = attr(n, "data-value")
		}
		if n.Data == "input" && attr(n, "type") == "checkbox" {
			boxID = attr(n, "id")
		}
	})
	var decoded []string
	if err := json.Unmarshal([]byte(members), &decoded); err != nil || len(decoded) != 1 || decoded[0] != value {
		t.Errorf("data-members = %q, want a JSON array holding the value (err %v)", members, err)
	}
	if boxID != "pk-k0" {
		t.Errorf("checkbox id = %q", boxID)
	}
	// the HTML parser (as a browser does) turns CR LF in an attribute value into LF
	if want := strings.ReplaceAll(value, "\r\n", "\n"); values != want {
		t.Errorf("data-value = %q, want the value as text %q", values, want)
	}
}

func TestPickValueWithoutAKeyMustBeIDSafe(t *testing.T) {
	assertPanics(t, "pick value a:b without a key", "invalid element id", func() {
		render(t, PickList(PickListProps{
			ID: "pick", Signal: "picked", FilterSignal: "pickQ", Name: "roles", ItemIDPrefix: "pk-",
			Items: []PickItem{{Value: "a:b", Name: "N", Code: "c"}},
		}), nil)
	})
}

func TestLockedPickIsSubmittedAndCountedEvenWhenNotMarkedChecked(t *testing.T) {
	out := render(t, PickList(PickListProps{
		ID: "pick", Signal: "picked", FilterSignal: "pickQ", Name: "roles", ItemIDPrefix: "pk-",
		Items: []PickItem{{Value: "r", Name: "N", Code: "c", Locked: true}, {Value: "s", Name: "M", Code: "d"}},
	}), nil)
	doc := parse(t, out)
	var locked, hidden, counter *html.Node
	walk(doc, func(n *html.Node) {
		switch {
		case n.Data == "input" && attr(n, "id") == "pk-r":
			locked = n
		case n.Data == "input" && attr(n, "type") == "hidden":
			hidden = n
		case hasAttr(n, "data-text"):
			counter = n
		}
	})
	if locked == nil || !hasAttr(locked, "checked") || !hasAttr(locked, "disabled") || hasAttr(locked, "name") {
		t.Fatalf("locked checkbox: %+v", locked)
	}
	if hidden == nil || attr(hidden, "value") != "r" {
		t.Errorf("locked pick is not submitted by a hidden input")
	}
	if got, want := attr(find(doc, "form")[0], "data-signals"), `{picked: [''], pickQ: ''}`; got != want {
		t.Errorf("data-signals = %q, want %q", got, want)
	}
	if counter == nil || text(counter) != "1" || attr(counter, "data-text") != "$picked.filter(Boolean).length + 1" {
		t.Errorf("counter: %+v", counter)
	}
}

func TestMultiSelectWithoutChipsHasNoChipListAndNoChipHandler(t *testing.T) {
	p := MultiSelectProps{ID: "m", Name: "n", Label: "L", Options: []Option{{Value: "a", Label: "A"}}, Selected: []string{"a"}}
	if out := render(t, MultiSelect(p), nil); strings.Contains(out, "fc-multiselect-chip") {
		t.Error("chip markup without Chips")
	}
	if got := p.detailsAttrs()["data-on:change"]; got != summaryExpr {
		t.Errorf("data-on:change = %v, want the summary expression only", got)
	}
}

// Rule 2: extra attributes.
func TestAttrsMapValuesAreEscaped(t *testing.T) {
	out := render(t, Button(ButtonProps{Attrs: templ.Attributes{"data-x": xss}}), nil)
	assertNoLiveMarkup(t, out)
	btn := find(parse(t, out), "button")[0]
	if attr(btn, "data-x") != xss {
		t.Errorf("data-x = %q, want the literal payload", attr(btn, "data-x"))
	}
}

func TestAttrsRefuseEventHandlersAndMalformedNames(t *testing.T) {
	const rule = "contract section 3 rule 2"
	for _, name := range []string{"onclick", "ONmouseover", "onerror", "a b", `a"=x`, "", "1a", "a>", "a=b", "a/b", "é", "a\nb"} {
		assertPanics(t, name, rule, func() { Attrs(templ.Attributes{name: "x"}) })
		// and through a component
		assertPanics(t, "button "+name, rule, func() {
			render(t, Button(ButtonProps{Attrs: templ.Attributes{name: "x"}}), nil)
		})
	}
	got := Attrs(templ.Attributes{"data-on:click": "go()", "data-on:click__prevent.debounce.300ms": "x", "_hyper": "y", "aria-label": "z", "xlink:href": "#a"})
	if len(got) != 5 || got["data-on:click"] != "go()" {
		t.Errorf("valid names were not kept: %v", got)
	}
}

func TestAttrsValueRules(t *testing.T) {
	yes := true
	var nilString *string
	got := Attrs(templ.Attributes{
		"nil": nil, "no": false, "yes": true, "yesptr": &yes, "nilptr": nilString,
		"n": 3, "f": 2.5, "big": float64(1000000), "i64": int64(7), "s": "text",
	})
	want := templ.Attributes{"yes": true, "yesptr": true, "n": "3", "f": "2.5", "big": "1000000", "i64": "7", "s": "text"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for k, v := range want {
		if got[k] != v {
			t.Errorf("%s = %#v, want %#v", k, got[k], v)
		}
	}
	assertPanics(t, "struct value", "contract section 3 rule 2", func() { Attrs(templ.Attributes{"data-x": struct{}{}}) })

	// rendered: true is a bare attribute, false and nil are absent
	out := render(t, Button(ButtonProps{Attrs: templ.Attributes{"data-a": true, "data-b": false, "data-c": nil, "data-d": "v"}}), nil)
	btn := find(parse(t, out), "button")[0]
	if !hasAttr(btn, "data-a") || attr(btn, "data-a") != "" || hasAttr(btn, "data-b") || hasAttr(btn, "data-c") || attr(btn, "data-d") != "v" {
		t.Errorf("rendered attributes: %v", btn.Attr)
	}
}

// Rule 3: URL attributes.
func TestURLCheck(t *testing.T) {
	keep := []string{
		"/orders?x=1#a", "https://example.com/a:b", "mailto:a@b.c", "tel:+123", "HTTP://X", "orders/a:b", "?a=b:c", "#frag:x", "", "about", "a/b:c",
		"//host/path", "/a:b", "x?y=javascript:1",
	}
	for _, u := range keep {
		if got := URL(u); got != u {
			t.Errorf("URL(%q) = %q, want it kept", u, got)
		}
	}
	block := []string{
		"javascript:alert(1)", "  JaVaScript:alert(1)", "data:text/html,<script>1</script>", "vbscript:x", "jav\tascript:alert(1)",
		"java\nscript:alert(1)", "\x01javascript:1", "javascript\x00:1", "\u0000javascript:1", "ftp://h/x", "blob:x", "file:///etc/passwd", "j a v a s c r i p t:1",
		"JAVASCRIPT:1", "about:blank",
	}
	for _, u := range block {
		if got := URL(u); got != BlockedURL {
			t.Errorf("URL(%q) = %q, want %q", u, got, BlockedURL)
		}
	}
	if BlockedURL != "about:invalid#blocked" {
		t.Errorf("BlockedURL = %q", BlockedURL)
	}
	if got := Attrs(templ.Attributes{"href": "data:text/html,x"})["href"]; got != BlockedURL {
		t.Errorf("Attrs href = %v", got)
	}
	for _, name := range []string{"href", "action", "formaction", "src", "xlink:href", "HREF", "Src"} {
		if got := Attrs(templ.Attributes{name: "javascript:x"})[name]; got != BlockedURL {
			t.Errorf("Attrs %s = %v", name, got)
		}
	}
	if got := Attrs(templ.Attributes{"data-href": "javascript:x"})["data-href"]; got != "javascript:x" {
		t.Errorf("a data attribute is not a URL attribute: %v", got)
	}
}

func TestUnsafeURLSchemesAreBlockedOnEveryRenderedURL(t *testing.T) {
	for _, bad := range []string{"javascript:alert(1)", "  JaVaScript:alert(1)", "data:text/html,<script>1</script>"} {
		outs := map[string]string{
			"badge":  render(t, Badge(BadgeProps{Href: bad}), plain("x")),
			"button": render(t, Button(ButtonProps{Href: bad}), plain("x")),
			"legacy": render(t, LegacyButton(LegacyButtonProps{Href: bad}), plain("x")),
			"crumb":  render(t, Breadcrumb(BreadcrumbProps{Items: []Crumb{{Label: "a", Href: bad}, {Label: "b"}}}), nil),
			"empty":  render(t, EmptyState(EmptyStateProps{Message: "m", ClearHref: bad}), nil),
			"sort":   render(t, SortableHeader(SortableHeaderProps{Label: "l", Href: bad}), nil),
			"pager":  render(t, CursorPager(CursorPagerProps{OlderHref: bad}), nil),
		}
		for name, out := range outs {
			as := find(parse(t, out), "a")
			if len(as) == 0 {
				t.Errorf("%s: no link rendered for %q", name, bad)
				continue
			}
			for _, a := range as {
				if got := attr(a, "href"); got != BlockedURL {
					t.Errorf("%s: href = %q for %q", name, got, bad)
				}
			}
		}
	}
	form := render(t, ConfirmDialog(ConfirmDialogProps{ID: "c", Action: "javascript:alert(1)", Title: "t", Message: "m", ConfirmLabel: "ok"}), nil)
	if got := attr(find(parse(t, form), "form")[0], "action"); got != BlockedURL {
		t.Errorf("confirm dialog action = %q", got)
	}
	fd := render(t, FormDialog(FormDialogProps{ID: "fd", Title: "t", Action: "data:x", Signal: "fd"}), nil)
	if got := attr(find(parse(t, fd), "form")[0], "action"); got != BlockedURL {
		t.Errorf("form dialog action = %q", got)
	}
	shell := render(t, UserMenu(AppShellProps{ProfileHref: "javascript:1", ResetPasswordHref: "vbscript:1", SignOutAction: "data:1"}), nil)
	doc := parse(t, shell)
	for _, a := range find(doc, "a") {
		if attr(a, "href") != BlockedURL {
			t.Errorf("user menu link href = %q", attr(a, "href"))
		}
	}
	if got := attr(find(doc, "form")[0], "action"); got != BlockedURL {
		t.Errorf("sign out action = %q", got)
	}
	ss := render(t, SearchSelect(SearchSelectProps{
		ID: "s", Label: "l", Options: []Option{{Value: "a", Label: "A"}}, Signal: "q",
		Href: func(string) string { return "javascript:1" },
	}), nil)
	if got := attr(find(parse(t, ss), "a")[0], "href"); got != BlockedURL {
		t.Errorf("search select option href = %q", got)
	}
	for _, p := range []PermissionDeniedDialogProps{{ID: "p", Kind: SessionExpired, LoginURL: "javascript:1"}, {ID: "p", HomeURL: "javascript:1"}} {
		a := find(parse(t, render(t, PermissionDeniedDialog(p), nil)), "a")[0]
		if attr(a, "href") != BlockedURL {
			t.Errorf("permission denied link href = %q", attr(a, "href"))
		}
	}
	dg := render(t, Diagram(DiagramProps{Label: "d", Bundle: "javascript:1"}), plain("graph TD"))
	if got := attr(find(parse(t, dg), "fc-diagram")[0], "data-bundle"); got != BlockedURL {
		t.Errorf("diagram bundle = %q", got)
	}
}

// Rule 4: ids, signals and paths that enter an expression.
func TestKbdRefusesAnEmptyKeyList(t *testing.T) {
	assertPanics(t, "kbd no keys", "kbd keys must not be empty", func() { render(t, Kbd(KbdProps{}), nil) })
	assertPanics(t, "kbd empty keys", "kbd keys must not be empty", func() { render(t, Kbd(KbdProps{Keys: []string{}}), nil) })
}

func TestIdsSignalsAndPathsThatReachExpressionsAreValidated(t *testing.T) {
	evil := "x');alert(1);//"
	assertPanics(t, "ID", "invalid element id", func() { ID(evil) })
	assertPanics(t, "Signal", "invalid signal name", func() { Signal(evil) })
	assertPanics(t, "Path", "invalid path", func() { Path(evil) })
	assertPanics(t, "ShowModal", "invalid element id", func() { ShowModal(evil) })
	assertPanics(t, "SignalAttr", "invalid signal name", func() { SignalAttr(evil) })
	// and the components use them: a dialog, a drawer signal, a tab value, an autocomplete source
	assertPanics(t, "dialog id", "invalid element id", func() { render(t, Dialog(DialogProps{ID: evil, Title: "t"}), nil) })
	assertPanics(t, "drawer signal", "invalid signal name", func() { render(t, Drawer(DrawerProps{Signal: evil}), nil) })
	assertPanics(t, "tab value", "invalid element id", func() {
		render(t, Tabs(TabsProps{Signal: "t", Tabs: []TabItem{{Value: evil, Label: "x"}}}), nil)
	})
	assertPanics(t, "autocomplete source", "invalid path", func() {
		render(t, Autocomplete(AutocompleteProps{ID: "a", Source: evil}), nil)
	})
	assertPanics(t, "form dialog signal", "invalid signal name", func() {
		render(t, FormDialog(FormDialogProps{ID: "d", Signal: evil}), nil)
	})
	if got := ID("a-b_c9"); got != "a-b_c9" {
		t.Errorf("ID = %q", got)
	}
	for _, bad := range []string{"", "9a", "a b", "a.b", "a:b"} {
		assertPanics(t, "ID "+bad, "invalid element id", func() { ID(bad) })
	}
	for _, bad := range []string{"", "9a", "a-b", "a_b", "a b"} {
		assertPanics(t, "Signal "+bad, "invalid signal name", func() { Signal(bad) })
	}
	for _, bad := range []string{"", "x", "/a b", "/a.b", "/a?b", "//x'", "//evil", "//"} {
		assertPanics(t, "Path "+bad, "invalid path", func() { Path(bad) })
	}
	if got := Path("/a/b_c-d"); got != "/a/b_c-d" {
		t.Errorf("Path = %q", got)
	}
}

func TestSignalAttrIsKebabCase(t *testing.T) {
	for in, want := range map[string]string{"qPools": "q-pools", "tabsDemo": "tabs-demo", "q": "q", "a1B": "a1-b", "ABC": "abc"} {
		if got := SignalAttr(in); got != want {
			t.Errorf("SignalAttr(%q) = %q, want %q", in, got, want)
		}
	}
}

// Rule 5: unknown variants and sizes.
func TestUnknownVariantsAreRefusedNotSilentlyDefaulted(t *testing.T) {
	const rule = "contract section 3 rule 5"
	cases := map[string]func(){
		"badge variant":     func() { render(t, Badge(BadgeProps{Variant: "<b>"}), plain("x")) },
		"button variant":    func() { render(t, Button(ButtonProps{Variant: "huge"}), nil) },
		"button size":       func() { render(t, Button(ButtonProps{Size: "xl"}), nil) },
		"button classes":    func() { ButtonClasses("x", ButtonMd) },
		"legacy variant":    func() { render(t, LegacyButton(LegacyButtonProps{Variant: `x" onclick="1`}), nil) },
		"tag severity":      func() { render(t, Tag(TagProps{Severity: "loud", Label: "l"}), nil) },
		"alert variant":     func() { render(t, Alert(AlertProps{Variant: "loud"}), nil) },
		"alert role":        func() { render(t, Alert(AlertProps{Role: "button"}), nil) },
		"flash banner kind": func() { render(t, FlashBanner(FlashBannerProps{Kind: "warn", Message: "m"}), nil) },
		"flash banner role": func() { render(t, FlashBanner(FlashBannerProps{Role: "x", Message: "m"}), nil) },
		"flash kind":        func() { render(t, FlashMessage(FlashMessageProps{Kind: "loud", Summary: "s"}), nil) },
		"dialog width":      func() { render(t, Dialog(DialogProps{ID: "d", Width: "huge"}), nil) },
		"dialog mask":       func() { render(t, Dialog(DialogProps{ID: "d", Mask: "pink"}), nil) },
		"confirm tone":      func() { render(t, ConfirmDialog(ConfirmDialogProps{ID: "d", Tone: "loud"}), nil) },
		"sheet side":        func() { render(t, Sheet(SheetProps{ID: "d", Side: "middle"}), nil) },
		"drawer size":       func() { render(t, Drawer(DrawerProps{Signal: "s", Size: "huge"}), nil) },
		"field orientation": func() { render(t, Field(FieldProps{Orientation: "diagonal"}), nil) },
		"toggle size": func() {
			render(t, ToggleGroup(ToggleGroupProps{Size: "xl", Options: []Option{{Value: "a", Label: "A"}}}), nil)
		},
		"toggle kind": func() {
			render(t, ToggleGroup(ToggleGroupProps{Kind: "weird", Options: []Option{{Value: "a", Label: "A"}}}), nil)
		},
		"sort direction":       func() { render(t, SortableHeader(SortableHeaderProps{Direction: "sideways"}), nil) },
		"permission kind":      func() { render(t, PermissionDeniedDialog(PermissionDeniedDialogProps{ID: "p", Kind: "other"}), nil) },
		"permission kind spec": func() { render(t, PermissionDeniedDialog(PermissionDeniedDialogProps{ID: "p", Kind: "Denied"}), nil) },
	}
	for name, fn := range cases {
		assertPanics(t, name, rule, fn)
	}
	// the documented values still render
	render(t, Badge(BadgeProps{Variant: BadgeInfo}), plain("x"))
	render(t, Alert(AlertProps{Role: "status"}), nil)
	render(t, FlashBanner(FlashBannerProps{Kind: FlashError, Role: "alert", Message: "m"}), nil)
}

// Rule 7: Raw is the only unescaped path.
func TestRawSlotIsTheOnlyUnescapedPath(t *testing.T) {
	if out := render(t, Badge(BadgeProps{}), Raw("<b>ok</b>")); !strings.Contains(out, "<b>ok</b>") {
		t.Errorf("Raw was escaped: %s", out)
	}
	if out := render(t, Badge(BadgeProps{}), plain("<b>no</b>")); strings.Contains(out, "<b>") {
		t.Errorf("Text was not escaped: %s", out)
	}
	// a Detail field's slot is the other raw path: it is rendered component output
	if out := render(t, DetailField(DetailFieldProps{Label: "l", Value: "<b>v</b>"}), nil); strings.Contains(out, "<b>v") {
		t.Errorf("DetailField value was not escaped: %s", out)
	}
}

func TestEscapeCoversTheFiveCharacters(t *testing.T) {
	out := render(t, Input(InputProps{Value: `<>&"'`}), nil)
	if !strings.Contains(out, `value="&lt;&gt;&amp;&#34;&#39;"`) {
		t.Errorf("the five characters are not escaped in an attribute value: %s", out)
	}
	if got := attr(find(parse(t, out), "input")[0], "value"); got != `<>&"'` {
		t.Errorf("value = %q", got)
	}
	if got := render(t, plain("<b>"), nil); got != "&lt;b&gt;" {
		t.Errorf("Text = %q", got)
	}
}

// Rule 6: a refusal is a panic that names the rule.
func TestRequiredApplicationPathsHaveNoDefaults(t *testing.T) {
	assertPanics(t, "home URL", "contract section 7", func() {
		render(t, PermissionDeniedDialog(PermissionDeniedDialogProps{ID: "p"}), nil)
	})
	assertPanics(t, "login URL", "contract section 7", func() {
		render(t, PermissionDeniedDialog(PermissionDeniedDialogProps{ID: "p", Kind: SessionExpired}), nil)
	})
	out := render(t, PermissionDeniedDialog(PermissionDeniedDialogProps{ID: "p", HomeURL: "/home"}), nil)
	if got := attr(find(parse(t, out), "a")[0], "href"); got != "/home" {
		t.Errorf("home href = %q", got)
	}
}

func TestNoPlatformPathsInTheKit(t *testing.T) {
	for _, src := range []string{"permissiondenied.templ", "appshell.templ", "kit.go"} {
		b := readFile(t, src)
		for _, bad := range []string{`"/ui`, `/ui/login`, `"/auth`, `/bff/`} {
			if strings.Contains(b, bad) {
				t.Errorf("%s names the application path %s", src, bad)
			}
		}
	}
}
