package partitionsadmin_test

import (
	"encoding/json/v2"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/partitions/partitionsadmin"
)

// every operation of the section, by operation id: the request that reaches its handler and what an
// authorised operator gets for it.
type operation struct {
	id, method, target, body string
	permission               string
	ok                       int
}

var operations = []operation{
	{"listAdminPartitions", "GET", "/api/admin/partitions", "", "view", 200},
	{"getAdminPartitionTable", "GET", "/api/admin/partitions/readings", "", "view", 200},
	{"listAdminPartitionArchives", "GET", "/api/admin/partitions/readings/archives", "", "view", 200},
	{"runAdminPartitionPass", "POST", "/api/admin/partitions/passes", `{}`, "manage", 200},
	{"ensureAdminPartitions", "POST", "/api/admin/partitions/readings/ensure", `{"from":"2026-12-01T00:00:00Z","to":"2026-12-20T00:00:00Z"}`, "manage", 200},
}

func TestTheTableCoversEveryOperationOfTheSection(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	var sectionOps []string
	for _, s := range r.adm.Sections() {
		if s.ID != partitionsadmin.SectionID {
			continue
		}
		if !s.ForOperators || s.Path != "/admin/partitions" || s.Permission != "acme:admin:partitions:view" {
			t.Errorf("section %+v", s)
		}
		for _, op := range s.Operations {
			info := op.Info()
			sectionOps = append(sectionOps, info.ID)
			if !info.OperatorsOnly {
				t.Errorf("%s is not marked operators-only", info.ID)
			}
		}
	}
	var tested []string
	for _, o := range operations {
		tested = append(tested, o.id)
	}
	slices.Sort(sectionOps)
	slices.Sort(tested)
	if !slices.Equal(sectionOps, tested) {
		t.Fatalf("the section serves %v but the gate table covers %v", sectionOps, tested)
	}
}

func (o operation) caller(base caller) caller {
	// an operator holding exactly the permission the operation needs
	perm := "acme:admin:partitions:" + o.permission
	return caller{perms: []string{"acme:admin:console:view", perm}, tier: base.tier}
}

func TestEveryOperationRequiresAPlatformOperatorInAdditionToItsPermission(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	for _, o := range operations {
		t.Run(o.id, func(t *testing.T) {
			// an ANCHOR with the permission
			a := r.send(o.method, o.target, o.body, o.caller(caller{tier: "ANCHOR"}))
			if a.status != o.ok {
				t.Fatalf("an operator with the permission: %d %s, want %d", a.status, a.body, o.ok)
			}
			// the wildcard grant of the suggested admin role works for an operator too
			if a := r.send(o.method, o.target, o.body, operatorWild); a.status != o.ok {
				t.Errorf("an operator with acme:admin:*:*: %d %s", a.status, a.body)
			}
			// PARTNER, CLIENT and no tier, with every permission the section has
			for _, tier := range []string{"PARTNER", "CLIENT", "", "anchor", "SERVICE"} {
				c := withTier(operatorWithBoth, tier)
				a := r.send(o.method, o.target, o.body, c)
				if a.status != 403 || a.errorCode() != "FORBIDDEN" || !strings.Contains(a.errorMessage(), "platform operators") {
					t.Errorf("tier %q with the permission: %d %s, want 403 FORBIDDEN for platform operators", tier, a.status, a.body)
				}
				// even the wildcard does not open it
				if a := r.send(o.method, o.target, o.body, withTier(operatorWild, tier)); a.status != 403 {
					t.Errorf("tier %q with acme:admin:*:*: %d", tier, a.status)
				}
			}
			// an ANCHOR without the permission
			a = r.send(o.method, o.target, o.body, operatorNoPerms)
			if a.status != 403 || a.errorCode() != "FORBIDDEN" || !strings.Contains(a.errorMessage(), "acme:admin:partitions:") {
				t.Errorf("an operator without the permission: %d %s", a.status, a.body)
			}
			// view does not open manage and the reverse
			other := "view"
			if o.permission == "view" {
				other = "manage"
			}
			wrong := caller{perms: []string{"acme:admin:console:view", "acme:admin:partitions:" + other}, tier: "ANCHOR"}
			if a := r.send(o.method, o.target, o.body, wrong); a.status != 403 {
				t.Errorf("an operator holding %s only: %d", other, a.status)
			}
			// not signed in
			if a := r.send(o.method, o.target, o.body, caller{}); a.status != 401 {
				t.Errorf("not signed in: %d", a.status)
			}
		})
	}
}

func sectionIDs(a answer) []string {
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

func TestTheNavigationListsTheSectionToOperatorsOnly(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	for _, c := range []struct {
		name   string
		caller caller
		want   []string
	}{
		{"an operator with view", withTier(operatorViewOnly, "ANCHOR"), []string{"about", "partitions"}},
		{"an operator with the wildcard", operatorWild, []string{"about", "partitions"}},
		{"an operator without the permission", operatorNoPerms, []string{"about"}},
		{"a client user with the permission", withTier(operatorWithBoth, "CLIENT"), []string{"about"}},
		{"a partner with the permission", withTier(operatorWithBoth, "PARTNER"), []string{"about"}},
		{"no tier with the permission", withTier(operatorWithBoth, ""), []string{"about"}},
		{"a client user with the wildcard", withTier(operatorWild, "CLIENT"), []string{"about"}},
	} {
		a := r.send("GET", "/api/admin/navigation", "", c.caller)
		if a.status != 200 {
			t.Errorf("%s: %d %s", c.name, a.status, a.body)
			continue
		}
		if got := sectionIDs(a); !slices.Equal(got, c.want) {
			t.Errorf("%s: sections %v, want %v", c.name, got, c.want)
		}
	}
	a := r.send("GET", "/api/admin/navigation", "", operatorViewOnly)
	if !strings.Contains(a.body, `"path":"/admin/partitions"`) || !strings.Contains(a.body, `"permission":"acme:admin:partitions:view"`) {
		t.Errorf("the navigation entry: %s", a.body)
	}
}

func TestTheOpenapiDocumentMarksTheOperationsOperatorsOnly(t *testing.T) {
	t.Parallel()
	r := startRig(t)
	r.web.Info("acme", "1")
	sessionapi.Declare(r.web, true)
	doc, problems := r.web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("problems: %v", problems)
	}
	text := string(doc)
	for _, o := range operations {
		if !strings.Contains(text, `"operationId": "`+o.id+`"`) {
			t.Errorf("no operation %s", o.id)
		}
	}
	if n := strings.Count(text, `"x-fw-operators-only": true`); n != len(operations) {
		t.Errorf("%d operations carry x-fw-operators-only, want %d (and only these)", n, len(operations))
	}
	for _, want := range []string{
		`"x-fw-permission": "acme:admin:partitions:view"`, `"x-fw-permission": "acme:admin:partitions:manage"`, `"format": "duration"`,
		"PARTITION_TABLE_NOT_FOUND", "PARTITION_PASS_RUNNING", "PARTITION_RANGE_INVALID", "PARTITION_RANGE_TOO_LARGE", "PARTITION_DETACHED_LEFTOVER",
	} {
		if !strings.Contains(text, want) {
			t.Errorf("the document lacks %s", want)
		}
	}
}

func TestAnOperatorsOnlySectionWithAnUnmarkedOperationIsAStartUpProblem(t *testing.T) {
	t.Parallel()
	app := fw.New("x", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	web := httpfw.Declare(app)
	adm := admin.Declare(web, fw.MustApplicationCode("acme"))
	marked := httpfw.Get[admin.Navigation]("/api/admin/a").ID("opA").Tag("admin").Permission(fw.MustPermission("acme:admin:x:view")).OperatorsOnly().Build()
	unmarked := httpfw.Get[admin.Navigation]("/api/admin/b").ID("opB").Tag("admin").Permission(fw.MustPermission("acme:admin:x:view")).Build()
	good := admin.NewSection("x", "X", "/admin/x", "acme:admin:x:view").OperatorsOnly().Serving(marked)
	if got := good.Problems(); len(got) != 0 {
		t.Fatalf("a sound section: %v", got)
	}
	bad := admin.NewSection("y", "Y", "/admin/y", "acme:admin:x:view").OperatorsOnly().Serving(marked, unmarked)
	got := bad.Problems()
	if len(got) != 1 || !strings.Contains(got[0], "opB") || !strings.Contains(got[0], "OperatorsOnly()") {
		t.Fatalf("problems %v", got)
	}
	adm.Section(bad)
	if problems := web.Problems(); !slices.ContainsFunc(problems, func(p string) bool { return strings.Contains(p, "opB") }) {
		t.Errorf("registering the section does not report the problem: %v", problems)
	}
	// an unmarked section may hold marked operations (the gate is stricter, not wrong)
	if got := admin.NewSection("z", "Z", "/admin/z", "acme:admin:x:view").Serving(marked).Problems(); len(got) != 0 {
		t.Errorf("%v", got)
	}
}

// ---- the vector ----

func TestGateVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Operations []struct {
			ID         string `json:"id"`
			Method     string `json:"method"`
			Path       string `json:"path"`
			Permission string `json:"permission"`
		} `json:"operations"`
		Gate []struct {
			Name        string   `json:"name"`
			Tier        string   `json:"tier"`
			Permissions []string `json:"permissions"`
			View        bool     `json:"view"`
			Manage      bool     `json:"manage"`
			Navigation  bool     `json:"navigation"`
		} `json:"gate"`
	}
	conformance.ReadJSON(t, "admin/partitions.json", &doc)
	if len(doc.Operations) != len(operations) || len(doc.Gate) == 0 {
		t.Fatalf("the vector has %d operations and %d gate cases", len(doc.Operations), len(doc.Gate))
	}
	r := startRig(t)
	for _, c := range doc.Gate {
		who := caller{perms: c.Permissions, tier: c.Tier}
		for _, vo := range doc.Operations {
			o := operations[slices.IndexFunc(operations, func(o operation) bool { return o.id == vo.ID })]
			target := strings.ReplaceAll(vo.Path, "{table}", "readings")
			a := r.send(vo.Method, target, o.body, who)
			allowed := c.View
			if vo.Permission == "manage" {
				allowed = c.Manage
			}
			if refused := a.status == 403; refused == allowed {
				t.Errorf("%s / %s: status %d %s, want allowed %v", c.Name, vo.ID, a.status, a.body, allowed)
			}
			if !allowed && (a.status != 403 || a.errorCode() != "FORBIDDEN") {
				t.Errorf("%s / %s: %d %s, want 403 FORBIDDEN", c.Name, vo.ID, a.status, a.body)
			}
		}
		a := r.send("GET", "/api/admin/navigation", "", who)
		listed := slices.Contains(sectionIDs(a), "partitions")
		if a.status != 200 || listed != c.Navigation {
			t.Errorf("%s: navigation %d lists the section %v, want %v", c.Name, a.status, listed, c.Navigation)
		}
	}
}

func TestRefusalsVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Refusals []struct {
			Name   string `json:"name"`
			Method string `json:"method"`
			Path   string `json:"path"`
			Body   any    `json:"body"`
			Expect struct {
				Status int    `json:"status"`
				Error  string `json:"error"`
			} `json:"expect"`
		} `json:"refusals"`
	}
	conformance.ReadJSON(t, "admin/partitions.json", &doc)
	if len(doc.Refusals) == 0 {
		t.Fatal("no refusals in the vector")
	}
	r := startRig(t)
	for _, c := range doc.Refusals {
		body := ""
		if c.Body != nil {
			b, err := json.Marshal(c.Body)
			if err != nil {
				t.Fatal(err)
			}
			body = string(b)
		}
		a := r.send(c.Method, c.Path, body, operatorWithBoth)
		if a.status != c.Expect.Status || (c.Expect.Error != "" && a.errorCode() != c.Expect.Error) {
			t.Errorf("%s: %d %s, want %d %s", c.Name, a.status, a.body, c.Expect.Status, c.Expect.Error)
		}
	}
}
