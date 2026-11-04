package kit

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"testing"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/htmlnorm"
)

// The conformance test renders every fixture in the UI kit's language-neutral
// fixture set (ui-kit/fixtures of this repository, read in place) and compares
// the result with the fixture's golden HTML after normalisation (package
// htmlnorm). UI_KIT_FIXTURES=/path/to/fixtures runs it against another copy.
// Coverage is checked both ways: a fixture directory without a renderer fails,
// and so does a renderer without a fixture directory (contract
// server-rendered-ui.md section 2 rule 3). Props are decoded strictly and every
// slot a fixture fills must appear in the output.

type fixture struct {
	Props  json.RawMessage   `json:"props"`
	Slots  map[string]string `json:"slots"`
	Source string            `json:"source"`
	Notes  string            `json:"notes"`

	used map[string]bool
}

// slot returns a named slot as raw HTML (fixture content is trusted test
// data), or nil when the fixture does not fill it.
func (f *fixture) slot(name string) templ.Component {
	s, ok := f.Slots[name]
	if !ok {
		return nil
	}
	f.used[name] = true
	return Raw(s)
}

// decode unmarshals the props strictly: an unknown prop name in a fixture
// fails, so a typo cannot silently test the defaults.
func decode[P any](f *fixture) (P, error) {
	var p P
	if len(f.Props) == 0 {
		return p, nil
	}
	dec := json.NewDecoder(bytes.NewReader(f.Props))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&p); err != nil {
		return p, fmt.Errorf("props: %w", err)
	}
	return p, nil
}

// withDefault renders c with the fixture's default slot as its children.
func withDefault(f *fixture, c templ.Component) templ.Component {
	children := f.slot("default")
	if children == nil {
		return c
	}
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		return c.Render(templ.WithChildren(ctx, children), w)
	})
}

type renderer func(f *fixture) (templ.Component, error)

// simple adapts a component whose only slot is the default one.
func simple[P any](fn func(P) templ.Component) renderer {
	return func(f *fixture) (templ.Component, error) {
		p, err := decode[P](f)
		if err != nil {
			return nil, err
		}
		return withDefault(f, fn(p)), nil
	}
}

// renderers maps a fixture directory (the kit's component name) to the templ
// implementation. Adding a fixture directory without an entry fails the test.
var renderers = map[string]renderer{
	"button":               simple(Button),
	"legacy-button":        simple(LegacyButton),
	"icon-button":          simple(IconButton),
	"input":                simple(Input),
	"textarea":             simple(Textarea),
	"select":               simple(Select),
	"checkbox":             simple(Checkbox),
	"switch":               simple(Switch),
	"label":                simple(Label),
	"field":                simple(Field),
	"field-group":          simple(FieldGroup),
	"form-field":           simple(FormField),
	"badge":                simple(Badge),
	"tag":                  simple(Tag),
	"alert":                simple(Alert),
	"flash-banner":         simple(FlashBanner),
	"spinner":              simple(Spinner),
	"skeleton":             simple(Skeleton),
	"breadcrumb":           simple(Breadcrumb),
	"empty-state":          simple(EmptyState),
	"search-input":         simple(SearchInput),
	"filter-select":        simple(FilterSelect),
	"table":                simple(Table),
	"table-row":            simple(TR),
	"table-head":           simple(TH),
	"table-cell":           simple(TD),
	"data-table":           simple(DataTable),
	"sortable-header":      simple(SortableHeader),
	"empty-row":            simple(EmptyRow),
	"row-actions":          simple(RowActions),
	"table-toolbar-state":  simple(TableToolbarState),
	"flash-message":        simple(FlashMessage),
	"flash-stack":          simple(FlashStack),
	"pagination":           simple(Pagination),
	"cursor-pager":         simple(CursorPager),
	"confirm-dialog":       simple(ConfirmDialog),
	"app-sidebar":          simple(AppSidebar),
	"user-menu":            simple(UserMenu),
	"app-shell":            simple(AppShell),
	"form-dialog":          simple(FormDialog),
	"detail-field":         simple(DetailField),
	"autocomplete":         simple(Autocomplete),
	"autocomplete-options": simple(AutocompleteOptions),
	"code-chips":           simple(CodeChips),
	"code-block":           simple(CodeBlock),
	"code-editor":          simple(CodeEditor),
	"diagram":              simple(Diagram),
	"paginator":            simple(Paginator),
	"multi-select":         simple(MultiSelect),
	"toggle-group":         simple(ToggleGroup),
	"pick-list":            simple(PickList),
	"local-time":           simple(LocalTime),
	"local-number":         simple(LocalNumber),
	"local-money":          simple(LocalMoney),
	"local-quantity":       simple(LocalQuantity),
	"date-range":           simple(DateRange),
	"tabs": func(f *fixture) (templ.Component, error) {
		p, err := decode[TabsProps](f)
		p.Panels = map[string]templ.Component{}
		for _, t := range p.Tabs {
			p.Panels[t.Value] = f.slot(t.Value)
		}
		return Tabs(p), err
	},
	"tab-list": func(f *fixture) (templ.Component, error) {
		p, err := decode[TabListProps](f)
		p.Panels = map[string]templ.Component{}
		for _, t := range p.Tabs {
			p.Panels[t.Value] = f.slot(t.Value)
		}
		return TabList(p), err
	},
	"search-select":            searchSelect,
	"password-input":           simple(PasswordInput),
	"secret-ref-input":         simple(SecretRefInput),
	"permission-denied-dialog": permissionDenied,
	"markdown":                 simple(Markdown),
	"form-section": func(f *fixture) (templ.Component, error) {
		p, err := decode[FormSectionProps](f)
		p.Actions = f.slot("actions")
		return withDefault(f, FormSection(p)), err
	},
	"drawer-content": func(f *fixture) (templ.Component, error) {
		p, err := decode[DrawerProps](f)
		p.Extras = f.slot("extras")
		p.Footer = f.slot("footer")
		return withDefault(f, DrawerContent(p)), err
	},
	"page-header": func(f *fixture) (templ.Component, error) {
		p, err := decode[PageHeaderProps](f)
		p.Actions = f.slot("actions")
		return PageHeader(p), err
	},
	"table-toolbar": func(f *fixture) (templ.Component, error) {
		p, err := decode[TableToolbarProps](f)
		p.Filters = f.slot("filters")
		return TableToolbar(p), err
	},
	"dialog": func(f *fixture) (templ.Component, error) {
		p, err := decode[DialogProps](f)
		p.Footer = f.slot("footer")
		return withDefault(f, Dialog(p)), err
	},
	"sheet": func(f *fixture) (templ.Component, error) {
		p, err := decode[SheetProps](f)
		p.Footer = f.slot("footer")
		return withDefault(f, Sheet(p)), err
	},
	"drawer": func(f *fixture) (templ.Component, error) {
		p, err := decode[DrawerProps](f)
		p.Extras = f.slot("extras")
		p.Footer = f.slot("footer")
		return withDefault(f, Drawer(p)), err
	},
	"alert-dialog": simple(AlertDialog),
	"avatar":       simple(Avatar),
	"dirty-form":   simple(DirtyForm),
	"json-block":   simple(JSONBlock),
	"kbd":          simple(Kbd),
	"progress":     simple(Progress),
	"radio-group":  simple(RadioGroup),
	"separator":    simple(Separator),
	"accordion": func(f *fixture) (templ.Component, error) {
		p, err := decode[AccordionProps](f)
		p.Panels = map[string]templ.Component{}
		for _, it := range p.Items {
			p.Panels[it.Value] = f.slot(it.Value)
		}
		return Accordion(p), err
	},
	"card": func(f *fixture) (templ.Component, error) {
		p, err := decode[CardProps](f)
		p.Footer = f.slot("footer")
		return withDefault(f, Card(p)), err
	},
	"tooltip": func(f *fixture) (templ.Component, error) {
		p, err := decode[TooltipProps](f)
		p.Trigger = f.slot("trigger")
		return Tooltip(p), err
	},
	"hover-card": func(f *fixture) (templ.Component, error) {
		p, err := decode[HoverCardProps](f)
		p.Trigger = f.slot("trigger")
		return withDefault(f, HoverCard(p)), err
	},
	"dropdown-menu": func(f *fixture) (templ.Component, error) {
		p, err := decode[DropdownMenuProps](f)
		p.Trigger = f.slot("trigger")
		p.Items = f.slot("items")
		return DropdownMenu(p), err
	},
}

// notYet names fixture directories that have no templ renderer yet (contract
// server-rendered-ui.md section 2 rules 3 and 4: fixtures come before
// renderers). It is checked both ways, like Java's NOT_YET: an entry must have
// a fixture directory and no renderer, so the list cannot rot. It is empty:
// GU4 step 2 wrote the last thirteen renderers.
var notYet = map[string]bool{}

// fixturesDir is ui-kit/fixtures of the repository that holds this module
// (go/ui is two levels below the repository root), or UI_KIT_FIXTURES.
func fixturesDir(t testing.TB) string {
	t.Helper()
	if d := os.Getenv("UI_KIT_FIXTURES"); d != "" {
		return d
	}
	dir, err := os.Getwd()
	if err != nil {
		t.Fatal(err)
	}
	for {
		mod, err := os.ReadFile(filepath.Join(dir, "go.mod"))
		if err == nil && strings.Contains(string(mod), "module github.com/flowcatalyst/framework/go/ui\n") {
			return filepath.Join(filepath.Dir(filepath.Dir(dir)), "ui-kit", "fixtures")
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			t.Fatal("kit: no go.mod of module go/ui above the working directory")
		}
		dir = parent
	}
}

// searchSelectFixture is the props of a search-select fixture: Href is a
// string in which {value} stands for the option's value (search-select.md,
// Props), and OptionAttrs a plain object, because the component's own props
// hold a function and templ attributes.
type searchSelectFixture struct {
	ID          string         `json:"id"`
	Label       string         `json:"label"`
	Options     []Option       `json:"options"`
	Selected    string         `json:"selected"`
	Href        string         `json:"href"`
	Placeholder string         `json:"placeholder"`
	Signal      string         `json:"signal"`
	EmptyText   string         `json:"emptyText"`
	OptionAttrs map[string]any `json:"optionAttrs"`
	Class       string         `json:"class"`
}

func searchSelect(f *fixture) (templ.Component, error) {
	fp, err := decode[searchSelectFixture](f)
	return SearchSelect(SearchSelectProps{
		ID: fp.ID, Label: fp.Label, Options: fp.Options, Selected: fp.Selected,
		Href:        func(value string) string { return strings.ReplaceAll(fp.Href, "{value}", value) },
		Placeholder: fp.Placeholder, Signal: fp.Signal, EmptyText: fp.EmptyText,
		OptionAttrs: templ.Attributes(fp.OptionAttrs), Class: fp.Class,
	}), err
}

func permissionDenied(f *fixture) (templ.Component, error) {
	p, err := decode[PermissionDeniedDialogProps](f)
	return PermissionDeniedDialog(p), err
}

func TestConformance(t *testing.T) {
	root := fixturesDir(t)
	dirs, err := os.ReadDir(root)
	if err != nil {
		t.Fatalf("read fixtures (%s): %v", root, err)
	}
	seen := map[string]bool{}
	total := 0
	for _, d := range dirs {
		if !d.IsDir() {
			continue
		}
		component := d.Name()
		seen[component] = true
		render, ok := renderers[component]
		if !ok {
			if !notYet[component] {
				t.Errorf("fixture component %q has no templ renderer: implement it and add it to renderers", component)
			}
			continue
		}
		cases, _ := filepath.Glob(filepath.Join(root, component, "*.json"))
		if len(cases) == 0 {
			t.Errorf("%s: no fixtures", component)
		}
		for _, jsonPath := range cases {
			name := component + "/" + strings.TrimSuffix(filepath.Base(jsonPath), ".json")
			total++
			t.Run(name, func(t *testing.T) {
				checkFixture(t, render, jsonPath)
			})
		}
	}
	for name := range notYet {
		switch _, hasRenderer := renderers[name]; {
		case !seen[name]:
			t.Errorf("notYet lists %q, which has no fixture directory: remove it", name)
		case hasRenderer:
			t.Errorf("notYet lists %q, which has a renderer: remove it from notYet", name)
		}
	}
	var missing []string
	for name := range renderers {
		if !seen[name] {
			missing = append(missing, name)
		}
	}
	sort.Strings(missing)
	if len(missing) > 0 {
		t.Errorf("renderers without fixtures (every component needs at least one): %s", strings.Join(missing, ", "))
	}
	if total == 0 {
		t.Fatal("no fixtures ran")
	}
	t.Logf("%d fixture directories, %d cases", len(seen), total)
}

func checkFixture(t *testing.T, render renderer, jsonPath string) {
	t.Helper()
	raw, err := os.ReadFile(jsonPath)
	if err != nil {
		t.Fatal(err)
	}
	f := &fixture{used: map[string]bool{}}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(f); err != nil {
		t.Fatalf("fixture %s: %v", jsonPath, err)
	}
	golden, err := os.ReadFile(strings.TrimSuffix(jsonPath, ".json") + ".html")
	if err != nil {
		t.Fatalf("golden: %v", err)
	}
	c, err := render(f)
	if err != nil {
		t.Fatalf("fixture %s: %v", jsonPath, err)
	}
	for name := range f.Slots {
		if !f.used[name] {
			t.Fatalf("fixture %s fills slot %q, which the component does not have", jsonPath, name)
		}
	}
	var out strings.Builder
	if err := c.Render(context.Background(), &out); err != nil {
		t.Fatalf("render: %v", err)
	}
	want, got := htmlnorm.MustNormalize(string(golden)), htmlnorm.MustNormalize(out.String())
	if want != got {
		t.Errorf("%s differs from its golden (source: %s)\n%s", filepath.Base(jsonPath), f.Source, htmlnorm.Diff(want, got))
	}
}
