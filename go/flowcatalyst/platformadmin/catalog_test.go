package platformadmin_test

import (
	"encoding/json/v2"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst/platformadmin"
)

func vectorFile(t *testing.T, name string, into any) {
	t.Helper()
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "conformance", "admin", name))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(raw, into); err != nil {
		t.Fatal(err)
	}
}

// TestPlatformReadsVector runs conformance/admin/platform-reads.json (admin contract section 11):
// what the event types and roles sections answer for given platform answers.
func TestPlatformReadsVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Application string         `json:"application"`
		Platform    map[string]any `json:"platform"`
		Cases       []struct {
			Name        string   `json:"name"`
			Path        string   `json:"path"`
			Permissions []string `json:"permissions"`
			Override    *struct {
				Path   string `json:"path"`
				Status int    `json:"status"`
				Body   any    `json:"body"`
			} `json:"override"`
			Expect struct {
				Status         int      `json:"status"`
				PlatformCalled bool     `json:"platformCalled"`
				Items          []string `json:"items"`
				NextCursor     *string  `json:"nextCursor"`
				Code           string   `json:"code"`
				Versions       []string `json:"versions"`
				Name           string   `json:"name"`
				Permissions    []string `json:"permissions"`
				Error          string   `json:"error"`
				Message        string   `json:"message"`
			} `json:"expect"`
		} `json:"cases"`
	}
	vectorFile(t, "platform-reads.json", &doc)
	if doc.Application != "orders" || len(doc.Cases) != 27 {
		t.Fatalf("the vector changed: application %s, %d cases", doc.Application, len(doc.Cases))
	}
	r := startRig(t, rigOptions{})
	reset := func() {
		for path, body := range doc.Platform {
			text, _ := json.Marshal(body)
			r.platform.RespondGet(path, 200, string(text))
		}
	}
	reset()
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			if c.Override != nil {
				text, _ := json.Marshal(c.Override.Body)
				r.platform.RespondGet(c.Override.Path, c.Override.Status, string(text))
				defer reset()
			}
			before := r.platformCalls()
			a := r.get(c.Path, append([]string{}, c.Permissions...))
			called := r.platformCalls() > before
			if a.status != c.Expect.Status {
				t.Fatalf("status %d, want %d: %s", a.status, c.Expect.Status, a.body)
			}
			if called != c.Expect.PlatformCalled {
				t.Fatalf("platform called: %v, want %v", called, c.Expect.PlatformCalled)
			}
			tree := a.tree()
			if c.Expect.Status != 200 {
				if tree["error"] != c.Expect.Error {
					t.Fatalf("error %v, want %s", tree["error"], c.Expect.Error)
				}
				if c.Expect.Message != "" && tree["message"] != c.Expect.Message {
					t.Fatalf("message %q, want %q", tree["message"], c.Expect.Message)
				}
				if c.Expect.Status == 502 {
					for _, leak := range []string{"127.0.0.1", "boom", "forbidden", "Bearer"} {
						if strings.Contains(a.body, leak) {
							t.Fatalf("the answer shows %q: %s", leak, a.body)
						}
					}
				}
				return
			}
			switch {
			case tree["items"] != nil:
				var keys []string
				for _, item := range tree["items"].([]any) { //nolint:forcetypeassert // the test's own answer
					m := item.(map[string]any) //nolint:forcetypeassert // the test's own answer
					if code, ok := m["code"].(string); ok {
						keys = append(keys, code)
					} else {
						keys = append(keys, m["name"].(string)) //nolint:forcetypeassert // the test's own answer
					}
				}
				if !slices.Equal(keys, c.Expect.Items) && (len(keys) != 0 || len(c.Expect.Items) != 0) {
					t.Fatalf("items %v, want %v", keys, c.Expect.Items)
				}
				next, has := tree["nextCursor"].(string)
				switch {
				case c.Expect.NextCursor == nil && has:
					t.Fatalf("unexpected nextCursor %q", next)
				case c.Expect.NextCursor != nil && next != *c.Expect.NextCursor:
					t.Fatalf("nextCursor %q, want %q", next, *c.Expect.NextCursor)
				}
			case tree["eventType"] != nil:
				if got := tree["eventType"].(map[string]any)["code"]; got != c.Expect.Code { //nolint:forcetypeassert // the test's own answer
					t.Fatalf("code %v, want %s", got, c.Expect.Code)
				}
				var versions []string
				for _, v := range tree["versions"].([]any) { //nolint:forcetypeassert // the test's own answer
					versions = append(versions, v.(map[string]any)["version"].(string)) //nolint:forcetypeassert // the test's own answer
				}
				if !slices.Equal(versions, c.Expect.Versions) && (len(versions) != 0 || len(c.Expect.Versions) != 0) {
					t.Fatalf("versions %v, want %v", versions, c.Expect.Versions)
				}
			default:
				if got := tree["role"].(map[string]any)["name"]; got != c.Expect.Name { //nolint:forcetypeassert // the test's own answer
					t.Fatalf("name %v, want %s", got, c.Expect.Name)
				}
				var perms []string
				for _, p := range tree["permissions"].([]any) { //nolint:forcetypeassert // the test's own answer
					perms = append(perms, p.(string)) //nolint:forcetypeassert // the test's own answer
				}
				if !slices.Equal(perms, c.Expect.Permissions) {
					t.Fatalf("permissions %v, want %v", perms, c.Expect.Permissions)
				}
			}
		})
	}
}

func TestADisabledPlatformAnswers503ForEverySection(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_ENABLED": "false"}})
	for _, path := range []string{"/api/admin/event-types", "/api/admin/event-types/et_1", "/api/admin/roles", "/api/admin/roles/orders:clerk"} {
		a := r.get(path, []string{"orders:admin:*:*"})
		if a.status != 503 || a.tree()["error"] != "PLATFORM_NOT_CONFIGURED" {
			t.Errorf("%s: %d %s", path, a.status, a.body)
		}
	}
	if n := r.platformCalls(); n != 0 {
		t.Errorf("the platform was called %d times", n)
	}
}

func TestTheSectionsMustBeForTheConfiguredApplication(t *testing.T) {
	t.Parallel()
	_, err := tryRig(t, rigOptions{
		env: map[string]string{"FLOWCATALYST_APPLICATION": "billing"},
	})
	if err == nil || !strings.Contains(err.Error(), "for application 'orders' but flowcatalyst.application is 'billing'") {
		t.Fatalf("got %v", err)
	}
}

func TestTheEventTypeAndRoleFiltersAreSentToThePlatformToo(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{})
	r.platform.RespondGet("/api/event-types", 200, `{"items":[]}`)
	r.platform.RespondGet("/api/roles", 200, `{"roles":[]}`)
	if a := r.get("/api/admin/event-types?status=ARCHIVED&subdomain=sales&aggregate=order", []string{"orders:admin:event-types:view"}); a.status != 200 {
		t.Fatal(a.body)
	}
	var sent string
	for _, q := range r.platform.Requests() {
		if q.Path == "/api/event-types" {
			sent = q.Query
		}
	}
	for _, want := range []string{"application=orders", "status=ARCHIVED", "subdomain=sales", "aggregate=order"} {
		if !strings.Contains(sent, want) {
			t.Errorf("the platform was asked %q, which lacks %s", sent, want)
		}
	}
}

func TestTheServiceAccountIsTheCaller(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{})
	r.platform.RespondGet("/api/roles", 200, `{"roles":[]}`)
	if a := r.get("/api/admin/roles", []string{"orders:admin:roles:view"}); a.status != 200 {
		t.Fatal(a.body)
	}
	if r.platform.TokenRequests() != 1 {
		t.Errorf("tokens: %d", r.platform.TokenRequests())
	}
	for _, q := range r.platform.Requests() {
		if q.Path == "/api/roles" && !strings.HasPrefix(q.Header.Get("Authorization"), "Bearer ") {
			t.Errorf("the platform call has no bearer token")
		}
	}
}

func TestAdminPermissionsAreExportedForTheDefinitions(t *testing.T) {
	t.Parallel()
	var got []string
	r := startRig(t, rigOptions{declare: func(adm *admin.Declared) []platformadmin.Part {
		c := platformadmin.DeclareCatalog(adm)
		for _, p := range admin.Permissions(adm.Sections()) {
			got = append(got, p.String())
		}
		return []platformadmin.Part{c}
	}})
	_ = r
	want := []string{"orders:admin:console:view", "orders:admin:event-types:view", "orders:admin:roles:view"}
	if !slices.Equal(got, want) {
		t.Fatalf("got %v, want %v", got, want)
	}
}
