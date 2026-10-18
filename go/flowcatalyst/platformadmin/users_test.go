package platformadmin_test

import (
	"encoding/json/v2"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst/platformadmin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
)

func usersOnly(adm *admin.Declared) []platformadmin.Part {
	return []platformadmin.Part{platformadmin.DeclareUsers(adm)}
}

type usersAnswer struct {
	Status int `json:"status"`
	Body   any `json:"body"`
}

func installUsers(r *rig, answers map[string]usersAnswer) {
	for key, a := range answers {
		method, path, _ := strings.Cut(key, " ")
		text := ""
		if a.Body != nil {
			b, _ := json.Marshal(a.Body)
			text = string(b)
		}
		r.platform.RespondTo(method, path, a.Status, text)
	}
}

// TestUsersVector runs conformance/admin/users.json (admin contract section 12.1).
func TestUsersVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Application string                 `json:"application"`
		Platform    map[string]usersAnswer `json:"platform"`
		Cases       []struct {
			Name        string                 `json:"name"`
			Method      string                 `json:"method"`
			Path        string                 `json:"path"`
			Permissions []string               `json:"permissions"`
			Body        any                    `json:"body"`
			Override    map[string]usersAnswer `json:"override"`
			Expect      struct {
				Status         int            `json:"status"`
				Calls          []string       `json:"calls"`
				PlatformBody   map[string]any `json:"platformBody"`
				AnswerHasLink  *bool          `json:"answerHasLink"`
				Items          []string       `json:"items"`
				NextCursor     *string        `json:"nextCursor"`
				ID             string         `json:"id"`
				Roles          []string       `json:"roles"`
				InvitationSent *bool          `json:"invitationSent"`
				ExpiresAt      string         `json:"expiresAt"`
				Error          string         `json:"error"`
				Message        string         `json:"message"`
			} `json:"expect"`
		} `json:"cases"`
	}
	vectorFile(t, "users.json", &doc)
	if doc.Application != "orders" || len(doc.Cases) != 35 {
		t.Fatalf("the vector changed: %s, %d cases", doc.Application, len(doc.Cases))
	}
	r := startRig(t, rigOptions{declare: usersOnly})
	installUsers(r, doc.Platform)
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			r.platform.Clear()
			if c.Override != nil {
				installUsers(r, c.Override)
				defer installUsers(r, doc.Platform)
			}
			body := ""
			if c.Body != nil {
				b, _ := json.Marshal(c.Body)
				body = string(b)
			}
			a := r.send(c.Method, c.Path, body, append([]string{}, c.Permissions...))
			var calls []string
			var first *string
			for _, q := range r.platform.Requests() {
				if q.Path == "/oauth/token" {
					continue
				}
				calls = append(calls, q.Method+" "+q.Path)
				if first == nil {
					text := q.Body
					first = &text
				}
			}
			if a.status != c.Expect.Status {
				t.Fatalf("status %d, want %d: %s", a.status, c.Expect.Status, a.body)
			}
			if !slices.Equal(calls, c.Expect.Calls) && (len(calls) != 0 || len(c.Expect.Calls) != 0) {
				t.Fatalf("calls %v, want %v", calls, c.Expect.Calls)
			}
			if c.Expect.PlatformBody != nil {
				var sent map[string]any
				if first == nil || json.Unmarshal([]byte(*first), &sent) != nil || !equalJSON(sent, c.Expect.PlatformBody) {
					t.Fatalf("platform body %v, want %v", first, c.Expect.PlatformBody)
				}
			}
			tree := a.tree()
			if c.Expect.Error != "" {
				if tree["error"] != c.Expect.Error || (c.Expect.Message != "" && tree["message"] != c.Expect.Message) {
					t.Fatalf("error %v / %v, want %s / %s", tree["error"], tree["message"], c.Expect.Error, c.Expect.Message)
				}
				if strings.Contains(a.body, "SECRET-LINK") || strings.Contains(a.body, "internal-host") {
					t.Fatalf("an error shows something it must not: %s", a.body)
				}
				return
			}
			if c.Expect.AnswerHasLink != nil {
				has := strings.Contains(a.body, "inviteLink") || strings.Contains(a.body, "SECRET-LINK")
				if has != *c.Expect.AnswerHasLink {
					t.Fatalf("answerHasLink %v, want %v: %s", has, *c.Expect.AnswerHasLink, a.body)
				}
				if strings.Contains(a.body, "LEAK") {
					t.Fatalf("the answer shows a field the platform should never send: %s", a.body)
				}
				if c.Method == "POST" && a.header.Get("Cache-Control") != "no-store" {
					t.Fatalf("Cache-Control %q", a.header.Get("Cache-Control"))
				}
			}
			if tree["items"] != nil {
				keys := []string{}
				for _, item := range tree["items"].([]any) { //nolint:forcetypeassert // the test's own answer
					m := item.(map[string]any) //nolint:forcetypeassert // the test's own answer
					if id, ok := m["id"].(string); ok {
						keys = append(keys, id)
					} else {
						keys = append(keys, m["userId"].(string)) //nolint:forcetypeassert // the test's own answer
					}
				}
				if !slices.Equal(keys, c.Expect.Items) && (len(keys) != 0 || len(c.Expect.Items) != 0) {
					t.Fatalf("items %v, want %v", keys, c.Expect.Items)
				}
				next, has := tree["nextCursor"].(string)
				if (c.Expect.NextCursor == nil) == has || (has && next != *c.Expect.NextCursor) {
					t.Fatalf("nextCursor %q (%v), want %v", next, has, c.Expect.NextCursor)
				}
			}
			if c.Expect.ID != "" && tree["id"] != c.Expect.ID {
				t.Fatalf("id %v, want %s", tree["id"], c.Expect.ID)
			}
			if c.Expect.Roles != nil {
				var roles []string
				for _, x := range tree["roles"].([]any) { //nolint:forcetypeassert // the test's own answer
					roles = append(roles, x.(string)) //nolint:forcetypeassert // the test's own answer
				}
				if !slices.Equal(roles, c.Expect.Roles) {
					t.Fatalf("roles %v, want %v", roles, c.Expect.Roles)
				}
			}
			if c.Expect.InvitationSent != nil && tree["invitationSent"] != *c.Expect.InvitationSent {
				t.Fatalf("invitationSent %v", tree["invitationSent"])
			}
			if c.Expect.ExpiresAt != "" && tree["expiresAt"] != c.Expect.ExpiresAt {
				t.Fatalf("expiresAt %v", tree["expiresAt"])
			}
		})
	}
}

func equalJSON(a, b any) bool {
	x, _ := json.Marshal(a, json.Deterministic(true))
	y, _ := json.Marshal(b, json.Deterministic(true))
	return string(x) == string(y)
}

// TestUsersDocumentMatchesTheSharedVector compares the six operations and the components of the
// section with conformance/openapi/orders-demo-admin.json.
func TestUsersDocumentMatchesTheSharedVector(t *testing.T) {
	t.Parallel()
	app := fw.New("users-doc", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	web := httpfw.Declare(app)
	web.Info("orders-demo", "1")
	sessionapi.Declare(web, true)
	adm := admin.Declare(web, fw.MustApplicationCode("orders-demo"))
	platformadmin.DeclareUsers(adm)
	raw, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatalf("problems: %v", problems)
	}
	var got, want struct {
		Paths      map[string]any `json:"paths"`
		Components struct {
			Schemas map[string]any `json:"schemas"`
		} `json:"components"`
	}
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatal(err)
	}
	var shared struct {
		Paths      map[string]any `json:"paths"`
		Components struct {
			Schemas map[string]any `json:"schemas"`
		} `json:"components"`
	}
	vectorFile(t, "../openapi/orders-demo-admin.json", &shared)
	want = shared
	for _, p := range []string{"/api/admin/users", "/api/admin/users/{id}", "/api/admin/invitations", "/api/admin/invitations/{id}", "/api/admin/invitations/{id}/resend"} {
		if !equalJSON(got.Paths[p], want.Paths[p]) {
			g, _ := json.Marshal(got.Paths[p])
			w, _ := json.Marshal(want.Paths[p])
			t.Errorf("path %s differs\n got %s\nwant %s", p, g, w)
		}
	}
	for _, name := range []string{"AdminUser", "AdminInvitation", "InviteBody", "InviteResult", "ResendBody", "ResendResult", "PageAdminUser", "PageAdminInvitation"} {
		if want.Components.Schemas[name] == nil {
			t.Fatalf("the vector has no %s", name)
		}
		if !equalJSON(got.Components.Schemas[name], want.Components.Schemas[name]) {
			g, _ := json.Marshal(got.Components.Schemas[name])
			w, _ := json.Marshal(want.Components.Schemas[name])
			t.Errorf("schema %s differs\n got %s\nwant %s", name, g, w)
		}
	}
}

func TestAnAuditorCanLookAndNotChange(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{declare: usersOnly})
	r.platform.RespondTo("GET", "/api/applications/orders/users", 200, `{"users":[],"total":0,"page":0,"pageSize":50}`)
	auditor := []string{"orders:admin:console:view", "orders:admin:users:view"}
	if a := r.get("/api/admin/users", auditor); a.status != 200 {
		t.Fatalf("view: %d %s", a.status, a.body)
	}
	invite := `{"email":"x@example.test","name":"X","clientId":"acme","roles":["orders:clerk"]}`
	a := r.send("POST", "/api/admin/users", invite, auditor)
	if a.status != 403 || a.tree()["message"] != "this needs the permission orders:admin:users:invite" {
		t.Fatalf("invite: %d %s", a.status, a.body)
	}
	if a := r.send("DELETE", "/api/admin/invitations/prn_1", "", auditor); a.status != 403 {
		t.Fatalf("revoke: %d", a.status)
	}
	if a := r.send("POST", "/api/admin/invitations/prn_1/resend", "{}", auditor); a.status != 403 {
		t.Fatalf("resend: %d", a.status)
	}
	for _, q := range r.platform.Requests() {
		if q.Method != "GET" && q.Path != "/oauth/token" {
			t.Errorf("a refused change reached the platform: %s %s", q.Method, q.Path)
		}
	}
}

func TestTheInvitationLinkIsInTheAnswerOnlyWhenAskedForAndNeverInALogOrAList(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{declare: usersOnly})
	const link = "https://platform.invalid/set-password?token=SECRET-ONCE"
	r.platform.RespondTo("POST", "/api/applications/orders/users", 201, `{"id":"prn_9","invitationSent":false,"inviteLink":"`+link+`"}`)
	r.platform.RespondTo("GET", "/api/applications/orders/invitations", 200,
		`{"invitations":[{"userId":"prn_9","email":"a@b.c","name":"A","clientId":"c","roles":["orders:clerk"],"status":"PENDING","createdAt":"2026-10-01T00:00:00Z","inviteLink":"`+link+`"}],"total":1,"page":0,"pageSize":50}`)
	held := []string{"orders:admin:*:*"}
	body := func(extra string) string {
		return `{"email":"a@b.c","name":"A","clientId":"acme","roles":["orders:clerk"]` + extra + `}`
	}
	asked := r.send("POST", "/api/admin/users", body(`,"returnInviteLink":true`), held)
	if asked.status != 201 || !strings.Contains(asked.body, link) || asked.header.Get("Cache-Control") != "no-store" {
		t.Fatalf("asked: %d %s %v", asked.status, asked.body, asked.header)
	}
	plain := r.send("POST", "/api/admin/users", body(""), held)
	if plain.status != 201 || strings.Contains(plain.body, "SECRET-ONCE") || strings.Contains(plain.body, "inviteLink") {
		t.Fatalf("not asked: %d %s", plain.status, plain.body)
	}
	if list := r.get("/api/admin/invitations", held); list.status != 200 || strings.Contains(list.body, "SECRET-ONCE") {
		t.Fatalf("list: %d %s", list.status, list.body)
	}
	if s := (platformadmin.InviteResult{ID: "x", InviteLink: new(link)}).String(); strings.Contains(s, "SECRET-ONCE") {
		t.Fatalf("String shows the link: %s", s)
	}
}

func TestPlatformFailuresNameNoHostTokenOrPlatformText(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{declare: usersOnly})
	for _, status := range []int{401, 500, 502, 503} {
		r.platform.RespondTo("GET", "/api/applications/orders/users/prn_1", status, `{"error":"INTERNAL","message":"internal-host-7 Bearer abc"}`)
		a := r.get("/api/admin/users/prn_1", []string{"orders:admin:users:view"})
		if a.status != 502 || a.tree()["error"] != "PLATFORM_ERROR" {
			t.Fatalf("%d: %d %s", status, a.status, a.body)
		}
		for _, leak := range []string{"internal-host", "Bearer", "127.0.0.1"} {
			if strings.Contains(a.body, leak) {
				t.Fatalf("%d: the answer shows %q: %s", status, leak, a.body)
			}
		}
	}
}

func TestTheInvitationFieldsAreCheckedBeforeAnythingIsSent(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{declare: usersOnly})
	held := []string{"orders:admin:users:invite"}
	for _, body := range []string{
		`{"email":"nope","name":"A","clientId":"acme","roles":["orders:clerk"]}`,
		`{"email":"a@b.c","name":" ","clientId":"acme","roles":["orders:clerk"]}`,
		`{"email":"a@b.c","name":"A","clientId":"","roles":["orders:clerk"]}`,
		`{"email":"a@b.c","name":"A","clientId":"acme","roles":[" "]}`,
		`{"email":"a@b.c","name":"A","clientId":"acme","roles":["orders:clerk"],"extra":1}`,
	} {
		if a := r.send("POST", "/api/admin/users", body, held); a.status != 400 {
			t.Errorf("%s: %d %s", body, a.status, a.body)
		}
	}
	if n := r.platformCalls(); n != 0 {
		t.Errorf("the platform was called %d times", n)
	}
}

func TestADisabledPlatformAnswers503ForTheUsersSection(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{declare: usersOnly, env: map[string]string{"FLOWCATALYST_ENABLED": "false"}})
	a := r.get("/api/admin/users", []string{"orders:admin:users:view"})
	if a.status != 503 || a.tree()["error"] != "PLATFORM_NOT_CONFIGURED" {
		t.Fatalf("%d %s", a.status, a.body)
	}
}
