package admin_test

import (
	"context"
	"encoding/json/v2"
	"io"
	"maps"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
)

// permsResolver is the test's ContextResolver: the caller's permissions are in X-Perms, comma
// separated. A request without the header is not signed in.
type permsResolver struct{}

func (permsResolver) Resolve(r *http.Request) (fw.Context, error) {
	header, ok := r.Header["X-Perms"]
	if !ok {
		return fw.Context{}, httpfw.Unauthenticated("not signed in")
	}
	var perms []fw.Permission
	for p := range strings.SplitSeq(strings.Join(header, ","), ",") {
		if p = strings.TrimSpace(p); p != "" {
			perms = append(perms, fw.MustPermission(p))
		}
	}
	return fw.ContextFor(fw.MustClientID("demo")).WithPermissions(perms...), nil
}

type rig struct {
	t       *testing.T
	handler http.Handler
	adm     *admin.Declared
	web     *httpfw.Declared
}

// start builds an application with the admin skeleton for application "acme", the declared settings
// of module "demo" and whatever declare adds.
func start(t *testing.T, env map[string]string, declare func(app *fw.App, web *httpfw.Declared, adm *admin.Declared)) (*rig, error) {
	t.Helper()
	all := map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}
	maps.Copy(all, env)
	app := fw.New("admin-test", fw.WithEnvironment(all), fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard))
	web := httpfw.Declare(app)
	adm := admin.Declare(web, fw.MustApplicationCode("acme"))
	declare(app, web, adm)
	out := &rig{t: t, adm: adm, web: web}
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		routes, err := adm.Build(b)
		if err != nil {
			return nil, err
		}
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{routes}, httpfw.WithResolver(permsResolver{}))
		if err != nil {
			return nil, err
		}
		out.handler = srv.Handler()
		return srv, nil
	})
	if err != nil {
		return nil, err
	}
	t.Cleanup(func() { _ = run.Shutdown() })
	return out, nil
}

func mustStart(t *testing.T, env map[string]string, declare func(app *fw.App, web *httpfw.Declared, adm *admin.Declared)) *rig {
	t.Helper()
	r, err := start(t, env, declare)
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	return r
}

type answer struct {
	status int
	body   string
	header http.Header
}

func (a answer) tree() map[string]any {
	var m map[string]any
	_ = json.Unmarshal([]byte(a.body), &m)
	return m
}

// get sends a GET; held is the caller's permissions, nil for an unauthenticated caller.
func (r *rig) get(target string, held []string) answer {
	r.t.Helper()
	req := httptest.NewRequest("GET", target, http.NoBody)
	if held != nil {
		req.Header.Set("X-Perms", strings.Join(held, ","))
	}
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return answer{status: rec.Code, body: string(b), header: rec.Result().Header}
}

func nothing(*fw.App, *httpfw.Declared, *admin.Declared) {}

func things(_ *fw.App, _ *httpfw.Declared, adm *admin.Declared) {
	adm.Section(
		admin.NewSection("things", "Things", "/admin/things", "acme:admin:things:view").WithIcon("i-lucide-box"),
		admin.NewSection("users", "Users", "/admin/users", "acme:admin:users:view").WithDescription("Who may sign in."),
	)
}

func vector(t *testing.T, name string, into any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "conformance", "admin", name))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatal(err)
	}
}

// ---- section and registry rules ------------------------------------------------------------------

func thingOp(pattern string, perm string) httpfw.Described {
	b := httpfw.Get[admin.Navigation](pattern).ID("thing" + strings.NewReplacer("/", "", "{", "", "}", "", "-", "").Replace(pattern)).Tag("admin")
	if perm != "" {
		b = b.Permission(fw.MustPermission(perm))
	}
	return b.Build()
}

func TestSectionRules(t *testing.T) {
	t.Parallel()
	cases := []struct {
		name    string
		section admin.Section
		want    []string // substrings of the problems; none: the section is sound
	}{
		{"sound", admin.NewSection("things", "Things", "/admin/things", "acme:admin:things:view").
			Serving(thingOp("/api/admin/things", "acme:admin:things:view")), nil},
		{"the root path", admin.NewSection("home", "Home", "/admin", "acme:admin:home:view"), nil},
		{"a bad id", admin.NewSection("Things", "Things", "/admin/things", "acme:admin:things:view"), []string{"the id must match"}},
		{"a blank title", admin.NewSection("things", " ", "/admin/things", "acme:admin:things:view"), []string{"the title is blank"}},
		{"a path outside /admin", admin.NewSection("things", "Things", "/things", "acme:admin:things:view"), []string{"the path must be /admin or under it"}},
		{"a path that only starts like /admin", admin.NewSection("things", "Things", "/administrator", "acme:admin:things:view"), []string{"the path must be /admin or under it"}},
		{"three segments", admin.NewSection("things", "Things", "/admin/things", "acme:admin:view"), []string{"the permission must have four"}},
		{"an empty segment", admin.NewSection("things", "Things", "/admin/things", "acme::things:view"), []string{"the permission must have four"}},
		{"an operation without a permission", admin.NewSection("things", "Things", "/admin/things", "acme:admin:things:view").
			Serving(thingOp("/api/admin/things", "")), []string{"has no required permission"}},
		{"an operation with a short permission", admin.NewSection("things", "Things", "/admin/things", "acme:admin:things:view").
			Serving(thingOp("/api/admin/things", "acme:things:view")), []string{"the permission must have four"}},
		{"an operation outside /api/admin/", admin.NewSection("things", "Things", "/admin/things", "acme:admin:things:view").
			Serving(thingOp("/api/things", "acme:admin:things:view")), []string{"must be under /api/admin/"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			t.Parallel()
			got := c.section.Problems()
			if len(c.want) == 0 {
				if len(got) != 0 {
					t.Fatalf("expected a sound section, got %v", got)
				}
				return
			}
			for _, w := range c.want {
				if !slices.ContainsFunc(got, func(p string) bool { return strings.Contains(p, w) }) {
					t.Errorf("no problem contains %q; got %v", w, got)
				}
			}
		})
	}
}

func TestRegistryRulesFailStartUpWithEveryProblem(t *testing.T) {
	t.Parallel()
	_, err := start(t, nil, func(_ *fw.App, _ *httpfw.Declared, adm *admin.Declared) {
		adm.Section(
			admin.NewSection("things", "Things", "/admin/things", "acme:admin:things:view"),
			admin.NewSection("things", "Again", "/admin/things", "acme:admin:other:view"),
			admin.NewSection("other", "Other", "/admin/other", "billing:admin:other:view"),
			admin.NewSection("bad", "Bad", "/elsewhere", "acme:admin:bad"),
		)
	})
	if err == nil {
		t.Fatal("expected start-up to fail")
	}
	for _, want := range []string{
		"admin section id 'things' is registered twice",
		"admin path '/admin/things' is used by two sections",
		"admin permission 'billing:admin:other:view' does not belong to application 'acme'",
		"admin section 'bad': the path must be /admin or under it",
		"admin section 'bad': the permission must have four ':'-separated segments",
	} {
		if !strings.Contains(err.Error(), want) {
			t.Errorf("the start-up error lacks %q:\n%v", want, err)
		}
	}
}

func TestTheOpenapiDocumentCarriesTheSkeletonWithItsPermission(t *testing.T) {
	t.Parallel()
	app := fw.New("admin-doc", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	web := httpfw.Declare(app)
	web.Info("acme", "1")
	sessionapi.Declare(web, true) // how callers authenticate: the document needs a scheme
	admin.Declare(web, fw.MustApplicationCode("acme"))
	doc, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("problems: %v", problems)
	}
	text := string(doc)
	for _, want := range []string{`"operationId": "getAdminNavigation"`, `"operationId": "getAdminAbout"`, `"x-fw-permission": "acme:admin:console:view"`} {
		if !strings.Contains(text, want) {
			t.Errorf("the document lacks %s", want)
		}
	}
}

func TestPermissionsAreSortedAndOnePerCode(t *testing.T) {
	t.Parallel()
	r := mustStart(t, nil, things)
	var got []string
	for _, p := range admin.Permissions(r.adm.Sections()) {
		got = append(got, p.String())
	}
	want := []string{"acme:admin:console:view", "acme:admin:things:view", "acme:admin:users:view"}
	if !slices.Equal(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}

// ---- the gate ------------------------------------------------------------------------------------

func TestGate(t *testing.T) {
	t.Parallel()
	r := mustStart(t, nil, things)
	cases := []struct {
		name   string
		held   []string
		status int
		code   string
	}{
		{"not signed in", nil, 401, "UNAUTHENTICATED"},
		{"no permission", []string{}, 403, "FORBIDDEN"},
		{"another section's permission", []string{"acme:admin:things:view"}, 403, "FORBIDDEN"},
		{"another application's console", []string{"other:admin:console:view"}, 403, "FORBIDDEN"},
		{"the console permission", []string{"acme:admin:console:view"}, 200, ""},
		{"a wildcard", []string{"acme:admin:*:*"}, 200, ""},
	}
	for _, c := range cases {
		for _, path := range []string{"/api/admin/navigation", "/api/admin/about"} {
			a := r.get(path, c.held)
			if a.status != c.status {
				t.Errorf("%s %s: status %d, want %d (%s)", c.name, path, a.status, c.status, a.body)
				continue
			}
			if c.code != "" && a.tree()["error"] != c.code {
				t.Errorf("%s %s: error %v, want %s", c.name, path, a.tree()["error"], c.code)
			}
		}
	}
	forbidden := r.get("/api/admin/about", []string{})
	if forbidden.tree()["message"] != "this needs the permission acme:admin:console:view" {
		t.Errorf("the message must name the permission: %s", forbidden.body)
	}
}

func TestAboutSaysWhatTheApplicationIsAndLeavesFailureMessagesOut(t *testing.T) {
	t.Parallel()
	r := mustStart(t, map[string]string{"APP_ENV": "test"}, nothing)
	a := r.get("/api/admin/about", []string{"acme:admin:console:view"})
	tree := a.tree()
	if tree["application"] != "acme" || tree["environment"] != "test" || tree["ready"] != true {
		t.Fatalf("unexpected about: %s", a.body)
	}
	if _, ok := tree["startedAt"].(string); !ok {
		t.Fatalf("startedAt is not text: %s", a.body)
	}
	if _, ok := tree["checks"].([]any); !ok {
		t.Fatalf("checks is not a list: %s", a.body)
	}
	if strings.Contains(a.body, "reason") || strings.Contains(a.body, "message") {
		t.Errorf("a failure message must not be on the wire: %s", a.body)
	}
}

// ---- the navigation vector -----------------------------------------------------------------------

func TestNavigationVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Sections []map[string]any `json:"sections"`
		Cases    []struct {
			Name        string   `json:"name"`
			Permissions []string `json:"permissions"`
			Expect      struct {
				Status          int              `json:"status"`
				Error           string           `json:"error"`
				Sections        []string         `json:"sections"`
				SectionsDetail  []map[string]any `json:"sectionsDetail"`
				HasDetailMarker bool             `json:"-"`
			} `json:"expect"`
		} `json:"cases"`
	}
	vector(t, "navigation.json", &doc)
	if len(doc.Cases) != 12 {
		t.Fatalf("the vector has %d cases, expected 12", len(doc.Cases))
	}
	// the vector's own sections are the fixture's: things then users
	if len(doc.Sections) != 2 || doc.Sections[0]["id"] != "things" || doc.Sections[1]["id"] != "users" {
		t.Fatalf("the vector's sections changed: %v", doc.Sections)
	}
	r := mustStart(t, nil, things)
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			a := r.get("/api/admin/navigation", append([]string{}, c.Permissions...))
			if a.status != c.Expect.Status {
				t.Fatalf("status %d, want %d: %s", a.status, c.Expect.Status, a.body)
			}
			tree := a.tree()
			if c.Expect.Status != 200 {
				if tree["error"] != c.Expect.Error {
					t.Fatalf("error %v, want %s", tree["error"], c.Expect.Error)
				}
				return
			}
			var ids []string
			list, _ := tree["sections"].([]any)
			for _, s := range list {
				ids = append(ids, s.(map[string]any)["id"].(string)) //nolint:forcetypeassert // the test's own answer
			}
			if !slices.Equal(ids, c.Expect.Sections) {
				t.Fatalf("sections %v, want %v", ids, c.Expect.Sections)
			}
			if c.Expect.SectionsDetail != nil {
				var got []map[string]any
				for _, s := range list {
					got = append(got, s.(map[string]any)) //nolint:forcetypeassert // the test's own answer
				}
				gotJSON, _ := json.Marshal(got)
				wantJSON, _ := json.Marshal(c.Expect.SectionsDetail)
				var g, w any
				_ = json.Unmarshal(gotJSON, &g)
				_ = json.Unmarshal(wantJSON, &w)
				if !equalValues(g, w) {
					t.Fatalf("detail\n got %s\nwant %s", gotJSON, wantJSON)
				}
			}
		})
	}
}

func equalValues(a, b any) bool {
	x, _ := json.Marshal(a, json.Deterministic(true))
	y, _ := json.Marshal(b, json.Deterministic(true))
	return string(x) == string(y)
}

// ---- the config vector ---------------------------------------------------------------------------

type vectorDecl struct {
	Key         string `json:"key"`
	Type        string `json:"type"`
	Default     string `json:"default"`
	Required    bool   `json:"required"`
	Description string `json:"description"`
}

func declareFrom(t *testing.T, decls []vectorDecl) func(*fw.App, *httpfw.Declared, *admin.Declared) {
	return func(app *fw.App, _ *httpfw.Declared, adm *admin.Declared) {
		m := app.Module("demo")
		for _, d := range decls {
			switch d.Type {
			case "secret":
				s := config.SecretValue(d.Key, d.Description)
				if d.Required {
					s = s.Required()
				}
				config.Declare(m, s)
			case "string":
				s := config.String(d.Key, d.Description)
				if d.Required {
					s = s.Required()
				}
				config.Declare(m, s)
			case "integer":
				config.Declare(m, config.Int(d.Key, d.Description))
			case "uri":
				config.Declare(m, config.URL(d.Key, d.Description))
			case "list":
				config.Declare(m, config.StringList(d.Key, d.Description))
			case "bool":
				config.Declare(m, config.Bool(d.Key, d.Description).WithDefault(d.Default == "true"))
			case "duration":
				config.Declare(m, config.Duration(d.Key, d.Description).WithDefault(30_000_000_000))
			default:
				t.Fatalf("unknown type %s", d.Type)
			}
		}
		adm.Config()
	}
}

func TestConfigVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Environment map[string]string `json:"environment"`
		Declare     []vectorDecl      `json:"declare"`
		Cases       []struct {
			Name        string   `json:"name"`
			Path        string   `json:"path"`
			Permissions []string `json:"permissions"`
			Expect      struct {
				Status int            `json:"status"`
				Error  string         `json:"error"`
				Body   map[string]any `json:"body"`
			} `json:"expect"`
		} `json:"cases"`
	}
	vector(t, "config.json", &doc)
	if len(doc.Cases) != 15 {
		t.Fatalf("the vector has %d cases, expected 15", len(doc.Cases))
	}
	r := mustStart(t, doc.Environment, declareFrom(t, doc.Declare))
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			a := r.get(c.Path, append([]string{}, c.Permissions...))
			if a.status != c.Expect.Status {
				t.Fatalf("status %d, want %d: %s", a.status, c.Expect.Status, a.body)
			}
			tree := a.tree()
			if c.Expect.Status != 200 {
				if tree["error"] != c.Expect.Error {
					t.Fatalf("error %v, want %s", tree["error"], c.Expect.Error)
				}
				return
			}
			for _, secret := range []string{"s3cr3t-value-DO-NOT-SHOW", "key-material-DO-NOT-SHOW", "pw999", "token=abc"} {
				if strings.Contains(a.body, secret) {
					t.Fatalf("the answer shows %q: %s", secret, a.body)
				}
			}
			body := c.Expect.Body
			if _, single := body["key"]; single {
				if !equalValues(withoutType(tree), withoutType(body)) {
					t.Fatalf("setting\n got %v\nwant %v", tree, body)
				}
				return
			}
			if tree["environment"] != body["environment"] {
				t.Fatalf("environment %v, want %v", tree["environment"], body["environment"])
			}
			health, _ := tree["health"].(map[string]any)
			if health["ready"] != body["ready"] {
				t.Fatalf("ready %v, want %v", health["ready"], body["ready"])
			}
			var got []any
			list, _ := tree["settings"].([]any)
			for _, s := range list {
				if m, _ := s.(map[string]any); m["module"] == "demo" {
					got = append(got, withoutType(m))
				}
			}
			var want []any
			wantList, _ := body["settings"].([]any)
			for _, s := range wantList {
				want = append(want, withoutType(s.(map[string]any))) //nolint:forcetypeassert // the vector
			}
			if !equalValues(got, want) {
				gotJSON, _ := json.Marshal(got)
				wantJSON, _ := json.Marshal(want)
				t.Fatalf("settings\n got %s\nwant %s", gotJSON, wantJSON)
			}
		})
	}
}

func withoutType(m map[string]any) map[string]any {
	out := map[string]any{}
	for k, v := range m {
		if k != "type" {
			out[k] = v
		}
	}
	return out
}

func TestConfigWithoutValuesShowsStateAndSourceOnly(t *testing.T) {
	t.Parallel()
	r := mustStart(t, map[string]string{"DEMO_NAME": "orders"}, func(app *fw.App, _ *httpfw.Declared, adm *admin.Declared) {
		config.Declare(app.Module("demo"), config.String("demo.name", "A name."))
		adm.Config(admin.WithoutValues())
	})
	a := r.get("/api/admin/config/demo.name", []string{"acme:admin:config:view"})
	tree := a.tree()
	if _, has := tree["value"]; has || tree["withheld"] != true || tree["state"] != "SET" || tree["source"] != "environment variable DEMO_NAME" {
		t.Fatalf("unexpected: %s", a.body)
	}
}
