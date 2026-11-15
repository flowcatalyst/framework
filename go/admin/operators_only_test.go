package admin_test

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// tierResolver is permsResolver with a tier: X-Perms (absent: not signed in) and X-Tier (absent: none).
type tierResolver struct{}

func (tierResolver) Resolve(r *http.Request) (fw.Context, error) {
	cx, err := permsResolver{}.Resolve(r)
	if err != nil {
		return cx, err
	}
	if tier := r.Header.Get("X-Tier"); tier != "" {
		cx = cx.WithTier(tier)
	}
	return cx, nil
}

type opsRig struct {
	t       *testing.T
	handler http.Handler
	adm     *admin.Declared
	web     *httpfw.Declared
}

// startArea builds application "acme" with the admin skeleton declared with opts.
func startArea(t *testing.T, declare func(*httpfw.Declared, *admin.Declared), opts ...admin.Option) (*opsRig, error) {
	t.Helper()
	app := fw.New("admin-test", fw.WithEnvironment(map[string]string{"APP_ENV": "test", "HTTP_HOST": "127.0.0.1", "HTTP_PORT": "0"}),
		fw.WithConfigDir(t.TempDir()), fw.WithOutput(io.Discard, io.Discard))
	web := httpfw.Declare(app)
	adm := admin.Declare(web, fw.MustApplicationCode("acme"), opts...)
	if declare != nil {
		declare(web, adm)
	}
	out := &opsRig{t: t, adm: adm, web: web}
	run, err := app.Start(t.Context(), func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
		routes, err := adm.Build(b)
		if err != nil {
			return nil, err
		}
		srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{routes}, httpfw.WithResolver(tierResolver{}))
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

func (r *opsRig) get(target string, perms []string, tier string) answer {
	r.t.Helper()
	req := httptest.NewRequest("GET", target, http.NoBody)
	if perms != nil {
		req.Header.Set("X-Perms", strings.Join(perms, ","))
	}
	if tier != "" {
		req.Header.Set("X-Tier", tier)
	}
	rec := httptest.NewRecorder()
	r.handler.ServeHTTP(rec, req)
	b, _ := io.ReadAll(rec.Result().Body)
	return answer{status: rec.Code, body: string(b), header: rec.Result().Header}
}

func (a answer) code() string {
	s, _ := a.tree()["error"].(string)
	return s
}

func sectionIDsOf(a answer) []string {
	var ids []string
	list, _ := a.tree()["sections"].([]any)
	for _, s := range list {
		if m, ok := s.(map[string]any); ok {
			id, _ := m["id"].(string)
			ids = append(ids, id)
		}
	}
	return ids
}

var skeleton = []string{"/api/admin/navigation", "/api/admin/about"}

func TestOperatorsOnlyAreaGate(t *testing.T) {
	t.Parallel()
	r, err := startArea(t, nil, admin.OperatorsOnly())
	if err != nil {
		t.Fatal(err)
	}
	if !r.adm.OperatorsOnly() {
		t.Error("the registry does not say it is operators-only")
	}
	view := []string{"acme:admin:console:view"}
	wild := []string{"acme:admin:*:*"}
	for _, target := range skeleton {
		if a := r.get(target, view, "ANCHOR"); a.status != 200 {
			t.Errorf("%s: an operator with the permission: %d %s", target, a.status, a.body)
		}
		if a := r.get(target, wild, "ANCHOR"); a.status != 200 {
			t.Errorf("%s: an operator with the wildcard: %d %s", target, a.status, a.body)
		}
		for _, tier := range []string{"CLIENT", "PARTNER", "", "anchor"} {
			for _, perms := range [][]string{view, wild} {
				a := r.get(target, perms, tier)
				if a.status != 403 || a.code() != "FORBIDDEN" || !strings.Contains(a.body, "this is for platform operators only") {
					t.Errorf("%s: tier %q with %v: %d %s, want 403 for platform operators", target, tier, perms, a.status, a.body)
				}
			}
		}
		a := r.get(target, []string{"acme:admin:other:view"}, "ANCHOR")
		if a.status != 403 || a.code() != "FORBIDDEN" || !strings.Contains(a.body, "acme:admin:console:view") || strings.Contains(a.body, "platform operators") {
			t.Errorf("%s: an operator without the permission: %d %s, want the permission message", target, a.status, a.body)
		}
		// the permission check comes before the operator check: a client without the permission is told about the permission
		if a := r.get(target, []string{"acme:admin:other:view"}, "CLIENT"); a.status != 403 || strings.Contains(a.body, "platform operators") {
			t.Errorf("%s: a client without the permission: %d %s, want the permission message", target, a.status, a.body)
		}
		if a := r.get(target, nil, "ANCHOR"); a.status != 401 {
			t.Errorf("%s: not signed in: %d", target, a.status)
		}
	}
	// the navigation of an operator lists the sections
	if a := r.get("/api/admin/navigation", view, "ANCHOR"); !slices.Equal(sectionIDsOf(a), []string{"about"}) {
		t.Errorf("an operator's navigation: %s", a.body)
	}
}

func TestOperatorsOnlyAreaVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Operations []struct {
			ID     string `json:"id"`
			Method string `json:"method"`
			Path   string `json:"path"`
		} `json:"operations"`
		Gate []struct {
			Name        string   `json:"name"`
			Tier        string   `json:"tier"`
			Permissions []string `json:"permissions"`
			Allowed     bool     `json:"allowed"`
			Message     string   `json:"message"`
			Navigation  []string `json:"navigation"`
		} `json:"gate"`
	}
	conformance.ReadJSON(t, "admin/operators-only-area.json", &doc)
	if len(doc.Operations) != 2 || len(doc.Gate) == 0 {
		t.Fatalf("the vector has %d operations and %d gate cases", len(doc.Operations), len(doc.Gate))
	}
	r, err := startArea(t, nil, admin.OperatorsOnly())
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range doc.Gate {
		for _, op := range doc.Operations {
			a := r.get(op.Path, c.Permissions, c.Tier)
			switch {
			case c.Allowed:
				if a.status != 200 {
					t.Errorf("%s / %s: %d %s", c.Name, op.ID, a.status, a.body)
				}
			case a.status != 403 || a.code() != "FORBIDDEN":
				t.Errorf("%s / %s: %d %s, want 403 FORBIDDEN", c.Name, op.ID, a.status, a.body)
			case (c.Message == "operators") != strings.Contains(a.body, "platform operators"):
				t.Errorf("%s / %s: wrong 403 message for %q: %s", c.Name, op.ID, c.Message, a.body)
			}
		}
		if c.Allowed {
			if got := sectionIDsOf(r.get("/api/admin/navigation", c.Permissions, c.Tier)); !slices.Equal(got, c.Navigation) {
				t.Errorf("%s: navigation %v, want %v", c.Name, got, c.Navigation)
			}
		}
	}
}

func operatorsSection(id string, marked bool) admin.Section {
	s := admin.NewSection(id, strings.ToUpper(id[:1])+id[1:], "/admin/"+id, "acme:admin:"+id+":view")
	if marked {
		s = s.OperatorsOnly()
	}
	return s
}

func TestAnOperatorsOnlyAreaRefusesASectionThatIsNot(t *testing.T) {
	t.Parallel()
	_, err := startArea(t, func(_ *httpfw.Declared, adm *admin.Declared) {
		adm.Section(operatorsSection("reports", true), operatorsSection("users", false))
	}, admin.OperatorsOnly())
	if _, ok := errors.AsType[*fw.StartupError](err); !ok {
		t.Fatalf("want a *fw.StartupError, got %v", err)
	}
	text := err.Error()
	if !strings.Contains(text, "admin section 'users' is not operators-only, but the admin area is declared operators-only (admin.OperatorsOnly()): every section of it must be") {
		t.Errorf("the problem: %s", text)
	}
	if strings.Contains(text, "section 'reports'") {
		t.Errorf("the operators-only section is reported: %s", text)
	}
	// the framework's own about section is not a problem, and an area of operators-only sections builds
	r, err := startArea(t, func(_ *httpfw.Declared, adm *admin.Declared) { adm.Section(operatorsSection("reports", true)) }, admin.OperatorsOnly())
	if err != nil {
		t.Fatalf("an all-operators area: %v", err)
	}
	if got := sectionIDsOf(r.get("/api/admin/navigation", []string{"acme:admin:*:*"}, "ANCHOR")); !slices.Equal(got, []string{"about", "reports"}) {
		t.Errorf("navigation %v", got)
	}
	// without the option the same sections build
	if _, err := startArea(t, func(_ *httpfw.Declared, adm *admin.Declared) {
		adm.Section(operatorsSection("reports", true), operatorsSection("users", false))
	}); err != nil {
		t.Errorf("the default area: %v", err)
	}
}

func TestTheOpenapiDocumentMarksTheSkeletonOnlyInAnOperatorsOnlyArea(t *testing.T) {
	t.Parallel()
	for _, only := range []bool{true, false} {
		var opts []admin.Option
		if only {
			opts = append(opts, admin.OperatorsOnly())
		}
		r, err := startArea(t, nil, opts...)
		if err != nil {
			t.Fatal(err)
		}
		r.web.Info("acme", "1")
		sessionapi.Declare(r.web, true)
		doc, problems := r.web.OpenAPI()
		if len(problems) > 0 {
			t.Fatalf("problems: %v", problems)
		}
		want := 0
		if only {
			want = 2
		}
		if n := strings.Count(string(doc), `"x-fw-operators-only": true`); n != want {
			t.Errorf("operators-only %v: %d operations carry x-fw-operators-only, want %d", only, n, want)
		}
	}
}
