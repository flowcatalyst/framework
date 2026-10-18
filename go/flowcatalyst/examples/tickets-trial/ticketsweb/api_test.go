package ticketsweb

import (
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/ratelimit"
)

// The typed API of the web application with the real sessions, roles and permission mapping and two
// clients, against real PostgreSQL (the Java TicketsApiTest, test by test). Each person holds a session
// cookie of the framework's session store; the client of a request is the one in the person's sign-in.

func str(t *testing.T, m map[string]any, key string) string {
	t.Helper()
	s, ok := m[key].(string)
	if !ok {
		t.Fatalf("%q is not a string in %v", key, m)
	}
	return s
}

func items(t *testing.T, a answer) []map[string]any {
	t.Helper()
	raw, _ := a.json(t)["items"].([]any)
	out := make([]map[string]any, len(raw))
	for i, r := range raw {
		out[i], _ = r.(map[string]any)
	}
	return out
}

func TestATicketIsOpenedAssignedWorkedCommentedResolvedAndClosed(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	ada := s.requester("acme", "prn_ada")
	opened := s.post("/api/tickets", ada, `{"subject":"Printer on fire","description":"smoke","priority":"HIGH"}`)
	if opened.status != 201 {
		t.Fatalf("open: %d %s", opened.status, opened.body)
	}
	view := opened.json(t)
	id := str(t, view, "id")
	if opened.header.Get("Location") != "/api/tickets/"+id || view["status"] != "OPEN" || view["requesterId"] != "prn_ada" || view["overdue"] != false {
		t.Errorf("opened %s location=%q", opened.body, opened.header.Get("Location"))
	}
	if _, present := view["assigneeId"]; present { // absent, not null
		t.Errorf("assigneeId should be absent: %s", opened.body)
	}

	agent := s.agent("acme")
	if got := s.post("/api/tickets/"+id+"/assign", agent, `{"assigneeId":"agent_acme"}`).json(t)["assigneeId"]; got != "agent_acme" {
		t.Errorf("assignee %v", got)
	}
	if got := s.post("/api/tickets/"+id+"/transition", agent, `{"action":"START"}`).json(t)["status"]; got != "IN_PROGRESS" {
		t.Errorf("status %v", got)
	}
	if got, _ := s.post("/api/tickets/"+id+"/comments", ada, `{"body":"Any news?"}`).json(t)["comments"].([]any); len(got) != 1 {
		t.Errorf("comments %v", got)
	}
	if got := s.post("/api/tickets/"+id+"/transition", agent, `{"action":"RESOLVE","note":"Toner replaced"}`).json(t)["resolution"]; got != "Toner replaced" {
		t.Errorf("resolution %v", got)
	}
	if got := s.post("/api/tickets/"+id+"/transition", agent, `{"action":"CLOSE"}`).json(t)["status"]; got != "CLOSED" {
		t.Errorf("status %v", got)
	}

	read := s.get("/api/tickets/"+id, ada).json(t)
	comments, _ := read["comments"].([]any)
	first, _ := comments[0].(map[string]any)
	if read["version"] != float64(6) || first["authorId"] != "prn_ada" {
		t.Errorf("read %v", read)
	}
}

func TestAUseCaseFailureIsItsErrorEnvelopeWithTheStatusItsKindMapsTo(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	agent := s.agent("acme")
	id := s.open(s.requester("acme", "prn_ada"), "Printer", "LOW")

	illegal := s.post("/api/tickets/"+id+"/transition", agent, `{"action":"CLOSE"}`)
	if illegal.status != 409 || illegal.json(t)["error"] != "ILLEGAL_TRANSITION" {
		t.Errorf("illegal: %d %s", illegal.status, illegal.body)
	}
	if got := s.post("/api/tickets/"+id+"/transition", agent, `{"action":"START"}`).json(t)["error"]; got != "ASSIGNEE_REQUIRED" {
		t.Errorf("start: %v", got)
	}
	if got := s.post("/api/tickets/"+id+"/transition", agent, `{"action":"RESOLVE"}`).status; got != 400 {
		t.Errorf("resolve without a note: %d", got)
	}
	missing := s.get("/api/tickets/tkt_nope", agent)
	if missing.status != 404 || missing.json(t)["error"] != "TICKET_NOT_FOUND" {
		t.Errorf("missing: %d %s", missing.status, missing.body)
	}
	// a requester may comment on their own ticket only
	notTheirs := s.post("/api/tickets/"+id+"/comments", s.requester("acme", "prn_bob"), `{"body":"me too"}`)
	if notTheirs.status != 403 || notTheirs.json(t)["error"] != "NOT_YOUR_TICKET" {
		t.Errorf("not theirs: %d %s", notTheirs.status, notTheirs.body)
	}
}

func TestTheBodyIsValidatedBeforeTheUseCaseRuns(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	r := s.post("/api/tickets", s.requester("acme", "prn_ada"), `{"subject":" ","description":"d","priority":"HIGH"}`)
	details, _ := r.json(t)["details"].(map[string]any)
	if r.status != 400 || details["subject"] != "NOT_BLANK: must not be blank" {
		t.Errorf("blank subject: %d %s", r.status, r.body)
	}
	if got := s.post("/api/tickets", s.requester("acme", "prn_ada"), `{"subject":"x","description":"d","priority":"SOON"}`).status; got != 400 {
		t.Errorf("unknown priority: %d", got)
	}
	if got := s.post("/api/tickets/x/transition", s.agent("acme"), `{"action":"EXPLODE"}`).status; got != 400 {
		t.Errorf("unknown action: %d", got)
	}
}

func TestPermissionsAreCheckedPerRoleAndAnonymousIsRefused(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	ada := s.requester("acme", "prn_ada")
	id := s.open(ada, "Printer", "LOW")

	r := s.post("/api/tickets/"+id+"/assign", ada, `{"assigneeId":"prn_ada"}`)
	if r.status != 403 || r.json(t)["error"] != "FORBIDDEN" {
		t.Errorf("requester assigns: %d %s", r.status, r.body)
	}
	if got := s.put("/api/sla-policy", s.agent("acme"), `{"lowMinutes":4,"normalMinutes":3,"highMinutes":2,"urgentMinutes":1}`).status; got != 403 {
		t.Errorf("agent sets the policy: %d", got)
	}
	if got := s.get("/api/admin/tickets/overview", s.agent("acme")).status; got != 403 {
		t.Errorf("agent reads the overview: %d", got)
	}
	if got := s.call("GET", "/api/tickets", "", "Accept", "application/json", "Sec-Fetch-Mode", "cors").status; got != 401 {
		t.Errorf("anonymous: %d", got)
	}
	// a person with no role of this application can do nothing
	if got := s.get("/api/tickets", s.person("acme", "prn_x", "other-app:thing")).status; got != 403 {
		t.Errorf("no role: %d", got)
	}
}

func TestTicketsAreTheClientsOwnWhateverTheIDAndTheListSaysSo(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	id := s.open(s.requester("acme", "prn_ada"), "Acme printer", "LOW")
	s.open(s.requester("globex", "prn_gus"), "Globex printer", "LOW")

	if got := s.get("/api/tickets/"+id, s.agent("globex")).status; got != 404 {
		t.Errorf("read: %d", got)
	}
	if got := s.post("/api/tickets/"+id+"/assign", s.agent("globex"), `{"assigneeId":"agent_globex"}`).status; got != 404 {
		t.Errorf("assign: %d", got)
	}
	if got := s.post("/api/tickets/"+id+"/comments", s.agent("globex"), `{"body":"mine now"}`).status; got != 404 {
		t.Errorf("comment: %d", got)
	}
	acme, globex := items(t, s.get("/api/tickets", s.agent("acme"))), items(t, s.get("/api/tickets", s.agent("globex")))
	if len(acme) != 1 || acme[0]["subject"] != "Acme printer" || len(globex) != 1 || globex[0]["subject"] != "Globex printer" {
		t.Errorf("lists: %v %v", acme, globex)
	}
	// and the session says which client the person is in
	if got := s.get("/auth/session", s.agent("globex")).json(t)["client"]; got != "globex" {
		t.Errorf("session client: %v", got)
	}
}

func TestTheListFiltersAndPagesByCursor(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	ada, agent := s.requester("acme", "prn_ada"), s.agent("acme")
	a := s.open(ada, "A", "LOW")
	b := s.open(ada, "B", "NORMAL")
	c := s.open(s.requester("acme", "prn_bob"), "C", "HIGH")
	s.post("/api/tickets/"+b+"/assign", agent, `{"assigneeId":"agent_acme"}`)

	first := s.get("/api/tickets?limit=2", agent)
	firstItems := items(t, first)
	if len(firstItems) != 2 || firstItems[0]["id"] != c { // newest first
		t.Fatalf("first page: %s", first.body)
	}
	second := s.get("/api/tickets?limit=2&after="+str(t, first.json(t), "nextCursor"), agent)
	secondItems := items(t, second)
	if len(secondItems) != 1 || secondItems[0]["id"] != a {
		t.Errorf("second page: %s", second.body)
	}
	if _, present := second.json(t)["nextCursor"]; present {
		t.Errorf("nextCursor on the last page: %s", second.body)
	}
	if got := len(items(t, s.get("/api/tickets?assignee=agent_acme", agent))); got != 1 {
		t.Errorf("by assignee: %d", got)
	}
	if got := len(items(t, s.get("/api/tickets?requester=prn_ada", agent))); got != 2 {
		t.Errorf("by requester: %d", got)
	}
	if got := len(items(t, s.get("/api/tickets?status=IN_PROGRESS", agent))); got != 0 {
		t.Errorf("by status: %d", got)
	}
	if got := s.get("/api/tickets?status=NOPE", agent).status; got != 400 {
		t.Errorf("a bad status: %d", got)
	}
}

func TestTheSLAPolicyIsPerClientDefaultsUntilSetAndChecked(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	leadAcme := s.lead("acme")
	defaults := s.get("/api/sla-policy", leadAcme).json(t)
	if defaults["customised"] != false || defaults["urgentMinutes"] != float64(60) {
		t.Errorf("defaults %v", defaults)
	}

	set := s.put("/api/sla-policy", leadAcme, `{"lowMinutes":600,"normalMinutes":300,"highMinutes":120,"urgentMinutes":15}`)
	if set.status != 200 || set.json(t)["customised"] != true || set.json(t)["version"] != float64(1) {
		t.Errorf("set: %d %s", set.status, set.body)
	}
	if got := s.put("/api/sla-policy", leadAcme, `{"lowMinutes":600,"normalMinutes":300,"highMinutes":120,"urgentMinutes":20}`).json(t)["version"]; got != float64(2) {
		t.Errorf("second set: %v", got)
	}
	if got := s.get("/api/sla-policy", s.lead("globex")).json(t)["customised"]; got != false {
		t.Errorf("globex: %v", got)
	}

	unordered := s.put("/api/sla-policy", leadAcme, `{"lowMinutes":10,"normalMinutes":20,"highMinutes":30,"urgentMinutes":40}`)
	if unordered.status != 400 || unordered.json(t)["error"] != "POLICY_NOT_ORDERED" {
		t.Errorf("unordered: %d %s", unordered.status, unordered.body)
	}
	rng := s.put("/api/sla-policy", leadAcme, `{"lowMinutes":0,"normalMinutes":20,"highMinutes":30,"urgentMinutes":40}`)
	details, _ := rng.json(t)["details"].(map[string]any)
	if low, _ := details["lowMinutes"].(string); rng.status != 400 || !strings.HasPrefix(low, "OUT_OF_RANGE") {
		t.Errorf("range: %d %s", rng.status, rng.body)
	}
	// the deadline of a ticket opened now comes from the client's policy
	id := s.open(s.requester("acme", "prn_ada"), "Urgent", "URGENT")
	ticket := s.get("/api/tickets/"+id, leadAcme).json(t)
	opened, err1 := time.Parse(time.RFC3339Nano, str(t, ticket, "openedAt"))
	due, err2 := time.Parse(time.RFC3339Nano, str(t, ticket, "dueAt"))
	if err1 != nil || err2 != nil || due.Sub(opened) != 20*time.Minute {
		t.Errorf("deadline %v after %v (%v %v)", due, opened, err1, err2)
	}
}

func TestCommentingIsLimitedPerPersonNotPerClient(t *testing.T) {
	t.Parallel()
	// Comments are limited to 3 in a burst here, so the test can see the limit.
	s := startWeb(t, siteOptions{tuning: &Tuning{CommentLimit: ratelimit.MustPolicy(3, time.Hour, 3), GaugeInterval: 30 * time.Second}})
	ada, bob := s.requester("acme", "prn_ada"), s.requester("acme", "prn_bob")
	adas, bobs := s.open(ada, "Ada's", "LOW"), s.open(bob, "Bob's", "LOW")

	for i := range 3 {
		if got := s.post("/api/tickets/"+adas+"/comments", ada, `{"body":"c`+string(rune('0'+i))+`"}`).status; got != 200 {
			t.Fatalf("comment %d: %d", i, got)
		}
	}
	limited := s.post("/api/tickets/"+adas+"/comments", ada, `{"body":"one too many"}`)
	if limited.status != 429 || limited.json(t)["error"] != "RATE_LIMITED" || limited.header.Get("Retry-After") == "" {
		t.Errorf("limited: %d %s %v", limited.status, limited.body, limited.header)
	}
	// another person is not affected, and reading is not limited
	if got := s.post("/api/tickets/"+bobs+"/comments", bob, `{"body":"hello"}`).status; got != 200 {
		t.Errorf("bob: %d", got)
	}
	read := s.get("/api/tickets/"+adas, ada)
	if comments, _ := read.json(t)["comments"].([]any); read.status != 200 || len(comments) != 3 {
		t.Errorf("read: %d %s", read.status, read.body)
	}
}

func TestTheAdminOverviewCountsTheClientsTicketsAndTheNavigationListsItsSection(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	admin := s.person("acme", "prn_admin", "tickets-trial:admin")
	ada := s.requester("acme", "prn_ada")
	done := s.open(ada, "A", "LOW")
	s.open(ada, "B", "LOW")
	s.open(s.requester("globex", "prn_gus"), "C", "LOW")
	agent := s.agent("acme")
	s.post("/api/tickets/"+done+"/assign", agent, `{"assigneeId":"agent_acme"}`)
	s.post("/api/tickets/"+done+"/transition", agent, `{"action":"START"}`)

	overview := s.get("/api/admin/tickets/overview", admin)
	if overview.json(t)["active"] != float64(2) ||
		!strings.Contains(overview.body, `{"status":"IN_PROGRESS","count":1`) || !strings.Contains(overview.body, `{"status":"OPEN","count":1`) {
		t.Errorf("overview: %d %s", overview.status, overview.body)
	}
	nav := s.get("/api/admin/navigation", admin).body
	if !strings.Contains(nav, `"id":"tickets"`) || !strings.Contains(nav, `"id":"config"`) {
		t.Errorf("navigation: %s", nav)
	}
}
