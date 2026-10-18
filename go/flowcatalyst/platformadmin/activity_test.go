package platformadmin_test

import (
	"encoding/json/v2"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
	"github.com/flowcatalyst/framework/go/flowcatalyst/platformadmin"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/sessionapi"
)

const activityView = "orders:admin:activity:view"

func activityRig(t *testing.T) *rig {
	t.Helper()
	return startRig(t, rigOptions{declare: func(adm *admin.Declared) []platformadmin.Part {
		return []platformadmin.Part{platformadmin.DeclareActivity(adm)}
	}})
}

type activityAnswer struct {
	Status int `json:"status"`
	Body   any `json:"body"`
}

func installActivity(p *fakeplatform.Platform, routes map[string]activityAnswer) {
	for route, a := range routes {
		method, path, _ := strings.Cut(route, " ")
		text := ""
		if a.Body != nil && a.Body != "" {
			b, _ := json.Marshal(a.Body)
			text = string(b)
		}
		p.RespondTo(method, path, a.Status, text)
	}
}

// TestActivityVector runs conformance/admin/activity.json (admin contract section 12.2).
func TestActivityVector(t *testing.T) {
	t.Parallel()
	var doc struct {
		Application string                    `json:"application"`
		Platform    map[string]activityAnswer `json:"platform"`
		Cases       []struct {
			Name        string                    `json:"name"`
			Method      string                    `json:"method"`
			Path        string                    `json:"path"`
			Permissions []string                  `json:"permissions"`
			Override    map[string]activityAnswer `json:"override"`
			Expect      struct {
				Status               int      `json:"status"`
				Calls                []string `json:"calls"`
				Error                string   `json:"error"`
				Message              string   `json:"message"`
				Items                []string `json:"items"`
				NextCursor           *string  `json:"nextCursor"`
				ID                   string   `json:"id"`
				DataTruncated        bool     `json:"dataTruncated"`
				DispatchJobs         []string `json:"dispatchJobs"`
				TargetURL            string   `json:"targetUrl"`
				AttemptBodyLengths   []*int   `json:"attemptBodyLengths"`
				AttemptBodyTruncated []bool   `json:"attemptBodyTruncated"`
				AnswerHasLink        bool     `json:"answerHasLink"`
			} `json:"expect"`
		} `json:"cases"`
	}
	vectorFile(t, "activity.json", &doc)
	if doc.Application != "orders" || len(doc.Cases) != 20 {
		t.Fatalf("the vector changed: %s, %d cases", doc.Application, len(doc.Cases))
	}
	r := activityRig(t)
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			installActivity(r.platform, doc.Platform)
			installActivity(r.platform, c.Override)
			r.platform.Clear()
			a := r.send(c.Method, c.Path, "", append([]string{}, c.Permissions...))
			var calls []string
			for _, q := range r.platform.Requests() {
				if strings.HasPrefix(q.Path, "/api/") {
					calls = append(calls, q.Method+" "+q.Path)
				}
			}
			if a.status != c.Expect.Status {
				t.Fatalf("status %d, want %d: %s", a.status, c.Expect.Status, a.body)
			}
			if !slices.Equal(calls, c.Expect.Calls) && (len(calls) != 0 || len(c.Expect.Calls) != 0) {
				t.Fatalf("calls %v, want %v", calls, c.Expect.Calls)
			}
			tree := a.tree()
			if c.Expect.Status >= 400 {
				if tree["error"] != c.Expect.Error || tree["message"] != c.Expect.Message {
					t.Fatalf("got %s, want %s: %s", tree["error"], c.Expect.Error, a.body)
				}
				for _, leak := range []string{"127.0.0.1", "boom", "URL-SECRET", "LEAK"} {
					if strings.Contains(a.body, leak) {
						t.Fatalf("the answer shows %q", leak)
					}
				}
				return
			}
			for _, leak := range []string{"URL-SECRET", "PAYLOAD-LEAK", "sa_LEAK", "user:pw", "frag"} {
				if strings.Contains(a.body, leak) {
					t.Fatalf("the answer shows %q: %s", leak, a.body)
				}
			}
			switch {
			case tree["items"] != nil:
				ids := []string{}
				for _, item := range tree["items"].([]any) { //nolint:forcetypeassert // the test's own answer
					ids = append(ids, item.(map[string]any)["id"].(string)) //nolint:forcetypeassert // the test's own answer
				}
				if !slices.Equal(ids, c.Expect.Items) && (len(ids) != 0 || len(c.Expect.Items) != 0) {
					t.Fatalf("items %v, want %v", ids, c.Expect.Items)
				}
				next, has := tree["nextCursor"].(string)
				if has != (c.Expect.NextCursor != nil) || (has && next != *c.Expect.NextCursor) {
					t.Fatalf("nextCursor %q (%v), want %v", next, has, c.Expect.NextCursor)
				}
			case tree["event"] != nil:
				if tree["event"].(map[string]any)["id"] != c.Expect.ID || tree["dataTruncated"] != c.Expect.DataTruncated { //nolint:forcetypeassert // the test's own answer
					t.Fatalf("got %s", a.body)
				}
				jobs := []string{}
				for _, j := range tree["dispatchJobs"].([]any) { //nolint:forcetypeassert // the test's own answer
					jobs = append(jobs, j.(map[string]any)["id"].(string)) //nolint:forcetypeassert // the test's own answer
				}
				if !slices.Equal(jobs, c.Expect.DispatchJobs) {
					t.Fatalf("jobs %v, want %v", jobs, c.Expect.DispatchJobs)
				}
			case tree["job"] != nil:
				job := tree["job"].(map[string]any) //nolint:forcetypeassert // the test's own answer
				if job["id"] != c.Expect.ID || job["targetUrl"] != c.Expect.TargetURL {
					t.Fatalf("got %s", a.body)
				}
				var lengths []*int
				var cuts []bool
				for _, at := range tree["attempts"].([]any) { //nolint:forcetypeassert // the test's own answer
					m := at.(map[string]any) //nolint:forcetypeassert // the test's own answer
					if body, ok := m["responseBody"].(string); ok {
						n := len([]rune(body))
						lengths = append(lengths, &n)
					} else {
						lengths = append(lengths, nil)
					}
					cuts = append(cuts, m["responseBodyTruncated"] == true)
				}
				got, _ := json.Marshal(lengths)
				want, _ := json.Marshal(c.Expect.AttemptBodyLengths)
				if string(got) != string(want) || !slices.Equal(cuts, c.Expect.AttemptBodyTruncated) {
					t.Fatalf("attempt bodies %s %v, want %s %v", got, cuts, want, c.Expect.AttemptBodyTruncated)
				}
			}
		})
	}
}

func TestActivityDocumentMatchesTheSharedVector(t *testing.T) {
	t.Parallel()
	app := fw.New("activity-doc", fw.WithEnvironment(map[string]string{}), fw.WithConfigDir(t.TempDir()))
	web := httpfw.Declare(app)
	web.Info("orders-demo", "1")
	sessionapi.Declare(web, true)
	adm := admin.Declare(web, fw.MustApplicationCode("orders-demo"))
	platformadmin.DeclareActivity(adm)
	raw, problems := web.OpenAPI()
	if len(problems) > 0 {
		t.Fatal(problems)
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
	vec, err := os.ReadFile(filepath.Join("..", "..", "..", "conformance", "openapi", "orders-demo-admin.json"))
	if err != nil {
		t.Fatal(err)
	}
	if err := json.Unmarshal(vec, &want); err != nil {
		t.Fatal(err)
	}
	same := func(a, b any) bool {
		x, _ := json.Marshal(a, json.Deterministic(true))
		y, _ := json.Marshal(b, json.Deterministic(true))
		return string(x) == string(y)
	}
	for _, p := range []string{"/api/admin/events", "/api/admin/events/{id}", "/api/admin/dispatch-jobs", "/api/admin/dispatch-jobs/{id}"} {
		if !same(got.Paths[p], want.Paths[p]) {
			g, _ := json.Marshal(got.Paths[p])
			w, _ := json.Marshal(want.Paths[p])
			t.Errorf("path %s\n got %s\nwant %s", p, g, w)
		}
	}
	for _, n := range []string{"AdminEvent", "AdminContextEntry", "AdminDispatchJob", "AdminEventDetail", "AdminAttempt", "AdminDispatchJobDetail", "PageAdminEvent", "PageAdminDispatchJob"} {
		if want.Components.Schemas[n] == nil || !same(got.Components.Schemas[n], want.Components.Schemas[n]) {
			g, _ := json.Marshal(got.Components.Schemas[n])
			w, _ := json.Marshal(want.Components.Schemas[n])
			t.Errorf("schema %s\n got %s\nwant %s", n, g, w)
		}
	}
}

func jobJSON(id, code, target string) string {
	return `{"id":"` + id + `","eventId":"ev_1","code":"` + code + `","status":"COMPLETED","kind":"EVENT","targetUrl":"` + target + `","mode":"IMMEDIATE","createdAt":"2026-10-16T08:00:02.000Z","attemptCount":1}`
}

func TestAJobsTargetLosesUserInfoQueryAndFragmentButKeepsItsPath(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	r.platform.RespondGet("/api/dispatch-jobs/dj_1", 200, jobJSON("dj_1", "orders:a:b:c", "https://u:p@host.example:8443/hooks/x?token=S#f"))
	r.platform.RespondGet("/api/dispatch-jobs/dj_1/attempts", 200, `[]`)
	a := r.get("/api/admin/dispatch-jobs/dj_1", []string{activityView})
	if got := a.tree()["job"].(map[string]any)["targetUrl"]; got != "https://host.example:8443/hooks/x" { //nolint:forcetypeassert // the test's own answer
		t.Fatalf("got %v", got)
	}
}

func TestAnotherApplicationsJobIsNotFoundAndItsAttemptsAreNotAsked(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	r.platform.RespondGet("/api/dispatch-jobs/dj_x", 200, jobJSON("dj_x", "billing:a:b:c", "https://h/x"))
	a := r.get("/api/admin/dispatch-jobs/dj_x", []string{activityView})
	if a.status != 404 || a.tree()["error"] != "DISPATCH_JOB_NOT_FOUND" {
		t.Fatalf("%d %s", a.status, a.body)
	}
	for _, q := range r.platform.Requests() {
		if strings.HasSuffix(q.Path, "/attempts") {
			t.Fatal("the attempts were asked for")
		}
	}
}

func TestACodeWithoutAColonIsTheWholeFirstSegment(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	r.platform.RespondGet("/api/dispatch-jobs", 200, "["+jobJSON("a", "orders", "https://h/")+","+jobJSON("b", "orders-x:y", "https://h/")+"]")
	a := r.get("/api/admin/dispatch-jobs", []string{activityView})
	items := a.tree()["items"].([]any)                             //nolint:forcetypeassert // the test's own answer
	if len(items) != 1 || items[0].(map[string]any)["id"] != "a" { //nolint:forcetypeassert // the test's own answer
		t.Fatalf("%s", a.body)
	}
}

func TestPayloadAndResponseBodiesAreCutByCharactersNotInsideASurrogatePair(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	emoji := strings.Repeat("\U0001F600", 3000) // 6000 UTF-16 units
	data, _ := json.Marshal(emoji)
	r.platform.RespondGet("/api/events/ev_1", 200, `{"id":"ev_1","type":"orders:a:b:c","source":"s","time":"t","data":`+string(data)+`}`)
	r.platform.RespondGet("/api/dispatch-jobs/event/ev_1", 200, `[]`)
	a := r.get("/api/admin/events/ev_1", []string{activityView})
	tree := a.tree()
	if tree["dataTruncated"] != false {
		t.Fatalf("6000 units are under the limit: %s", a.body[:80])
	}
	long, _ := json.Marshal(strings.Repeat("\U0001F600", 10_001)) // 20 002 units
	r.platform.RespondGet("/api/events/ev_1", 200, `{"id":"ev_1","type":"orders:a:b:c","source":"s","time":"t","data":`+string(long)+`}`)
	tree = r.get("/api/admin/events/ev_1", []string{activityView}).tree()
	cutData, _ := tree["data"].(string)
	if tree["dataTruncated"] != true || len([]rune(cutData)) != 10_000 {
		t.Fatalf("truncated %v, %d characters", tree["dataTruncated"], len([]rune(cutData)))
	}
	r.platform.RespondGet("/api/dispatch-jobs/dj_1", 200, jobJSON("dj_1", "orders:a:b:c", "https://h/"))
	body, _ := json.Marshal(strings.Repeat("x", 4001))
	r.platform.RespondGet("/api/dispatch-jobs/dj_1/attempts", 200, `[{"attemptNumber":1,"attemptedAt":"t","responseBody":`+string(body)+`,"success":false}]`)
	tree = r.get("/api/admin/dispatch-jobs/dj_1", []string{activityView}).tree()
	attempt := tree["attempts"].([]any)[0].(map[string]any)                                        //nolint:forcetypeassert // the test's own answer
	if attempt["responseBodyTruncated"] != true || len(attempt["responseBody"].(string)) != 4000 { //nolint:forcetypeassert // the test's own answer
		t.Fatalf("%v", attempt)
	}
}

func TestTimesCursorsAndStatusesAreCheckedBeforeThePlatformIsAsked(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	for target, code := range map[string]string{
		"/api/admin/events?after=x":                 "INVALID_CURSOR",
		"/api/admin/events?after=-1":                "INVALID_CURSOR",
		"/api/admin/events?until=2026-13-01":        "INVALID_TIMESTAMP",
		"/api/admin/dispatch-jobs?since=nope":       "INVALID_TIMESTAMP",
		"/api/admin/dispatch-jobs?status=completed": "INVALID_STATUS",
		"/api/admin/dispatch-jobs?after=1.5":        "INVALID_CURSOR",
	} {
		a := r.get(target, []string{activityView})
		if a.status != 400 || a.tree()["error"] != code {
			t.Errorf("%s: %d %s", target, a.status, a.body)
		}
	}
	if n := r.platformCalls(); n != 0 {
		t.Errorf("the platform was asked %d times", n)
	}
}

func TestOnlyTheActivityPermissionOpensTheActivitySection(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	for _, path := range []string{"/api/admin/events", "/api/admin/events/ev_1", "/api/admin/dispatch-jobs", "/api/admin/dispatch-jobs/dj_1"} {
		for _, held := range [][]string{{}, {"orders:admin:users:view"}, {"orders:admin:console:view"}} {
			a := r.get(path, held)
			if a.status != 403 || a.tree()["message"] != "this needs the permission "+activityView {
				t.Errorf("%s %v: %d %s", path, held, a.status, a.body)
			}
		}
		if a := r.get(path, nil); a.status != 401 {
			t.Errorf("%s unauthenticated: %d", path, a.status)
		}
	}
	if n := r.platformCalls(); n != 0 {
		t.Errorf("the platform was asked %d times", n)
	}
}

func TestAPlatformThatIsDisabledAnswers503(t *testing.T) {
	t.Parallel()
	r := startRig(t, rigOptions{
		env: map[string]string{"FLOWCATALYST_ENABLED": "false"},
		declare: func(adm *admin.Declared) []platformadmin.Part {
			return []platformadmin.Part{platformadmin.DeclareActivity(adm)}
		},
	})
	a := r.get("/api/admin/events", []string{activityView})
	if a.status != 503 || a.tree()["error"] != "PLATFORM_NOT_CONFIGURED" {
		t.Fatalf("%d %s", a.status, a.body)
	}
}

func TestTheFiltersAndPageReachThePlatform(t *testing.T) {
	t.Parallel()
	r := activityRig(t)
	r.platform.RespondGet("/api/events", 200, `[]`)
	r.platform.RespondGet("/api/dispatch-jobs", 200, `[]`)
	r.get("/api/admin/events?type=orders:a:b:c&subject=s&correlationId=c&clientId=clt_1&limit=10&after=20", []string{activityView})
	r.get("/api/admin/dispatch-jobs?status=FAILED&code=orders:a:b:c&messageGroup=g&limit=5", []string{activityView})
	var events, jobs string
	for _, q := range r.platform.Requests() {
		switch q.Path {
		case "/api/events":
			events = q.Query
		case "/api/dispatch-jobs":
			jobs = q.Query
		}
	}
	for _, want := range []string{"applications=orders", "type=orders%3Aa%3Ab%3Ac", "subject=s", "correlationId=c", "clientId=clt_1", "limit=11", "offset=20"} {
		if !strings.Contains(events, want) {
			t.Errorf("events were asked %q, lacking %s", events, want)
		}
	}
	for _, want := range []string{"applications=orders", "statuses=FAILED", "code=orders", "messageGroup=g", "limit=6", "offset=0"} {
		if !strings.Contains(jobs, want) {
			t.Errorf("jobs were asked %q, lacking %s", jobs, want)
		}
	}
}
