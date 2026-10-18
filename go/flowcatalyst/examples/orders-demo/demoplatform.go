package main

import (
	"encoding/json/v2"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"sync"

	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
)

// The stand-in for the platform that answers the reads and writes the demo's admin pages make (the
// Java DemoPlatform and DemoDirectory): event types and roles of orders-demo, plus a few of other
// applications that must never show; the application's users and invitations, which change when an
// invitation is created, resent or revoked; and its events and dispatch jobs, with another
// application's in the data so the pages must never show them. Nothing here reaches a real platform,
// and the "links" are made-up addresses on a name that does not exist.

type demoUser struct {
	id, email, name string
	active          bool
	clientID        string
	roles           []string
	invitation      string // PENDING, EXPIRED or ""
	created         string
}

type demoPlatform struct {
	fake *fakeplatform.Platform

	mu      sync.Mutex
	users   []*demoUser
	nextID  int
	refusal int // 0 = normal, else the status every route answers
}

var (
	userPath       = regexp.MustCompile(`^/api/applications/orders-demo/users/([^/]+)$`)
	resendPath     = regexp.MustCompile(`^/api/applications/orders-demo/invitations/([^/]+)/resend$`)
	invitationPath = regexp.MustCompile(`^/api/applications/orders-demo/invitations/([^/]+)$`)
	eventPath      = regexp.MustCompile(`^/api/events/([^/]+)$`)
	jobPath        = regexp.MustCompile(`^/api/dispatch-jobs/([^/]+)$`)
	attemptsPath   = regexp.MustCompile(`^/api/dispatch-jobs/([^/]+)/attempts$`)
	byEventPath    = regexp.MustCompile(`^/api/dispatch-jobs/event/([^/]+)$`)
	demoRoles      = []string{"orders-demo:admin", "orders-demo:clerk", "orders-demo:viewer"}
)

// startDemoPlatform starts the fake with the demo's catalogue and directory.
func startDemoPlatform() *demoPlatform {
	d := &demoPlatform{fake: fakeplatform.Start()}
	d.Reset()
	f := d.fake
	f.RouteMatching("GET", "/api/applications/orders-demo/users", d.listUsers)
	f.RouteMatching("POST", "/api/applications/orders-demo/users", d.invite)
	f.RouteMatching("GET", "/api/applications/orders-demo/users/[^/]+", d.getUser)
	f.RouteMatching("GET", "/api/applications/orders-demo/invitations", d.listInvitations)
	f.RouteMatching("POST", "/api/applications/orders-demo/invitations/[^/]+/resend", d.resend)
	f.RouteMatching("DELETE", "/api/applications/orders-demo/invitations/[^/]+", d.revoke)
	f.RouteMatching("GET", "/api/events", d.listEvents)
	f.RouteMatching("GET", "/api/events/[^/]+", d.getEvent)
	f.RouteMatching("GET", "/api/dispatch-jobs", d.listJobs)
	f.RouteMatching("GET", "/api/dispatch-jobs/event/[^/]+", d.jobsOfEvent)
	f.RouteMatching("GET", "/api/dispatch-jobs/[^/]+/attempts", d.attempts)
	f.RouteMatching("GET", "/api/dispatch-jobs/[^/]+", d.getJob)
	d.Reads(http.StatusOK)
	return d
}

// Environment points the application at the fake as application orders-demo.
func (d *demoPlatform) Environment() map[string]string {
	return map[string]string{
		"FLOWCATALYST_ENABLED":       "true",
		"FLOWCATALYST_BASE_URL":      d.fake.BaseURL(),
		"FLOWCATALYST_APPLICATION":   "orders-demo",
		"FLOWCATALYST_CLIENT_ID":     fakeplatform.ClientID,
		"FLOWCATALYST_CLIENT_SECRET": fakeplatform.ClientSecret,
	}
}

// Close stops the fake.
func (d *demoPlatform) Close() { d.fake.Close() }

// Reads sets how the fake answers the admin pages' reads: 200 for the demo's catalogue, anything
// else for an error answer on every read and write.
func (d *demoPlatform) Reads(status int) {
	d.mu.Lock()
	d.refusal = 0
	if status != http.StatusOK {
		d.refusal = status
	}
	d.mu.Unlock()
	if status != http.StatusOK {
		for _, path := range []string{"/api/event-types", "/api/event-types/et_3", "/api/roles", "/api/roles/orders-demo:clerk"} {
			d.fake.RespondGet(path, status, `{"error":"refused"}`)
		}
		return
	}
	items := []string{
		eventType("et_3", "orders-demo:sales:order:placed", "Order placed", "orders-demo", "CURRENT", true),
		eventType("et_4", "orders-demo:sales:order:cancelled", "Order cancelled", "orders-demo", "CURRENT", false),
		eventType("et_5", "orders-demo:stock:item:counted", "Item counted", "orders-demo", "ARCHIVED", false),
		eventType("et_6", "orders-demo:sales:order:paid", "Order paid", "orders-demo", "CURRENT", false),
		eventType("et_7", "orders-demo:sales:order:shipped", "Order shipped", "orders-demo", "CURRENT", false),
		eventType("et_8", "orders-demo:sales:order:refunded", "Order refunded", "orders-demo", "CURRENT", false),
		eventType("et_10", "orders-demo:sales:order:returned", "Order returned", "orders-demo", "CURRENT", false),
		eventType("et_11", "orders-demo:stock:item:received", "Item received", "orders-demo", "CURRENT", false),
		eventType("et_12", "orders-demo:stock:item:adjusted", "Item adjusted", "orders-demo", "CURRENT", false),
		eventType("et_9", "billing:invoice:invoice:issued", "Invoice issued", "billing", "CURRENT", false),
	}
	d.fake.RespondGet("/api/event-types", 200, `{"items":[`+strings.Join(items, ",")+`]}`)
	d.fake.RespondGet("/api/event-types/et_3", 200, eventType("et_3", "orders-demo:sales:order:placed", "Order placed", "orders-demo", "CURRENT", true))
	d.fake.RespondGet("/api/roles", 200, `{"roles":[`+
		role("rol_1", "orders-demo:admin", "Administrator", "orders-demo:admin:config:view", "orders-demo:admin:console:view")+","+
		role("rol_2", "orders-demo:clerk", "Clerk", "orders-demo:order:read", "orders-demo:order:manage")+","+
		role("rol_9", "billing:admin", "Billing administrator", "billing:invoice:invoice:issue")+`],"total":3}`)
	d.fake.RespondGet("/api/roles/orders-demo:clerk", 200, role("rol_2", "orders-demo:clerk", "Clerk", "orders-demo:order:read", "orders-demo:order:manage"))
}

func quoted(s string) string {
	b, _ := json.Marshal(s)
	return string(b)
}

func quotedList(values []string) string {
	out := make([]string, len(values))
	for i, v := range values {
		out[i] = quoted(v)
	}
	return "[" + strings.Join(out, ",") + "]"
}

func eventType(id, code, name, application, status string, versions bool) string {
	p := strings.Split(code, ":")
	specs := "[]"
	if versions {
		specs = `[{"version":"1.0","status":"CURRENT","schema":{"type":"object","properties":{"orderId":{"type":"string"}}}}]`
	}
	return `{"id":` + quoted(id) + `,"code":` + quoted(code) + `,"name":` + quoted(name) + `,"description":` + quoted(name+" (demo).") +
		`,"application":` + quoted(application) + `,"subdomain":` + quoted(p[1]) + `,"aggregate":` + quoted(p[2]) +
		`,"eventName":` + quoted(p[3]) + `,"status":` + quoted(status) + `,"specVersions":` + specs + `}`
}

func role(id, name, display string, permissions ...string) string {
	application, _, _ := strings.Cut(name, ":")
	return `{"id":` + quoted(id) + `,"name":` + quoted(name) + `,"applicationCode":` + quoted(application) + `,"displayName":` + quoted(display) +
		`,"source":"SDK","clientManaged":false,"permissions":` + quotedList(permissions) + `}`
}

// Reset puts the users and invitations back to where they started.
func (d *demoPlatform) Reset() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.users, d.nextID = nil, 100
	d.users = append(d.users,
		&demoUser{"prn_ada", "ada@example.test", "Ada Lovelace", true, "clt_acme", []string{"orders-demo:admin", "billing:admin"}, "", "2026-09-01T08:00:00.000Z"},
		&demoUser{"prn_grace", "grace@example.test", "Grace Hopper", true, "clt_acme", []string{"orders-demo:clerk"}, "", "2026-09-02T08:00:00.000Z"},
		&demoUser{"prn_linus", "linus@example.test", "Linus Torvalds", true, "clt_acme", []string{"orders-demo:clerk"}, "PENDING", "2026-09-03T08:00:00.000Z"},
		&demoUser{"prn_margaret", "margaret@example.test", "Margaret Hamilton", true, "clt_globex", []string{"orders-demo:viewer"}, "EXPIRED", "2026-08-03T08:00:00.000Z"},
	)
	for i := 1; i <= 8; i++ {
		client := "clt_acme"
		if i%2 == 0 {
			client = "clt_globex"
		}
		d.users = append(d.users, &demoUser{
			fmt.Sprintf("prn_user%d", i), fmt.Sprintf("user%d@example.test", i), fmt.Sprintf("Demo User %d", i), true, client,
			[]string{"orders-demo:viewer"},
			"", fmt.Sprintf("2026-09-1%dT08:00:00.000Z", i%10),
		})
	}
	d.users[len(d.users)-1].active = false
}

func reply(status int, body string) fakeplatform.Reply {
	return fakeplatform.Reply{Status: status, Body: body}
}

func failure(status int, code, message string) fakeplatform.Reply {
	return reply(status, `{"error":`+quoted(code)+`,"message":`+quoted(strings.ReplaceAll(message, `"`, "'"))+`}`)
}

// refused is the answer every route gives while the suite asks for a refusing platform; nil otherwise.
func (d *demoPlatform) refused() *fakeplatform.Reply {
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.refusal == 0 {
		return nil
	}
	r := failure(d.refusal, "FORBIDDEN", "refused on purpose")
	return &r
}

func queryOf(r fakeplatform.Recorded) map[string]string {
	out := map[string]string{}
	values, _ := url.ParseQuery(r.Query)
	for k, v := range values {
		if len(v) > 0 {
			out[k] = v[0]
		}
	}
	return out
}

func userJSON(u *demoUser) string {
	s := `{"id":` + quoted(u.id) + `,"email":` + quoted(u.email) + `,"name":` + quoted(u.name) + `,"active":` + strconv.FormatBool(u.active) +
		`,"clientId":` + quoted(u.clientID) + `,"idpType":"INTERNAL","roles":` + quotedList(u.roles)
	if u.invitation != "" {
		s += `,"invitation":` + quoted(u.invitation)
	}
	s += `,"createdAt":` + quoted(u.created)
	if u.invitation == "" {
		s += `,"lastLoginAt":"2026-10-10T09:00:00.000Z"`
	}
	return s + "}"
}

func (d *demoPlatform) listUsers(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	q := queryOf(r)
	needle := strings.ToLower(q["q"])
	var roles []string
	if v, ok := q["roles"]; ok {
		roles = strings.Split(v, ",")
	}
	var match []*demoUser
	for _, u := range d.users {
		if needle != "" && !strings.Contains(strings.ToLower(u.name+" "+u.email), needle) {
			continue
		}
		if v, ok := q["active"]; ok && u.active != (v == "true") {
			continue
		}
		if v, ok := q["clientId"]; ok && u.clientID != v {
			continue
		}
		if len(roles) > 0 && !slices.ContainsFunc(u.roles, func(r string) bool { return slices.Contains(roles, r) }) {
			continue
		}
		match = append(match, u)
	}
	size := atoiOr(q["pageSize"], 50)
	page := atoiOr(q["page"], 0)
	var out []string
	for i := page * size; i < min(len(match), (page+1)*size); i++ {
		out = append(out, userJSON(match[i]))
	}
	return reply(200, `{"users":[`+strings.Join(out, ",")+`],"total":`+strconv.Itoa(len(match))+`,"page":`+strconv.Itoa(page)+`,"pageSize":`+strconv.Itoa(size)+`}`)
}

func atoiOr(s string, fallback int) int {
	if n, err := strconv.Atoi(s); err == nil {
		return n
	}
	return fallback
}

func (d *demoPlatform) getUser(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	id := userPath.FindStringSubmatch(r.Path)[1]
	for _, u := range d.users {
		if u.id == id {
			return reply(200, userJSON(u))
		}
	}
	return failure(404, "User_NOT_FOUND", "User not found")
}

func waiting(u *demoUser) bool { return u.active && u.invitation != "" }

func invitationJSON(u *demoUser) string {
	s := `{"userId":` + quoted(u.id) + `,"email":` + quoted(u.email) + `,"name":` + quoted(u.name) + `,"clientId":` + quoted(u.clientID) +
		`,"roles":` + quotedList(u.roles) + `,"status":` + quoted(u.invitation) + `,"createdAt":` + quoted(u.created)
	if u.invitation == "PENDING" {
		s += `,"expiresAt":"2026-10-30T08:00:00.000Z"`
	}
	return s + "}"
}

func (d *demoPlatform) listInvitations(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	q := queryOf(r)
	var out []string
	for _, u := range d.users {
		if status, ok := q["status"]; waiting(u) && (!ok || status == u.invitation) {
			out = append(out, invitationJSON(u))
		}
	}
	return reply(200, `{"invitations":[`+strings.Join(out, ",")+`],"total":`+strconv.Itoa(len(out))+`,"page":0,"pageSize":50}`)
}

func (d *demoPlatform) invite(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	body := r.JSON()
	var roles []string
	for _, v := range anyList(body["roles"]) {
		role, _ := v.(string)
		if !strings.Contains(role, ":") {
			role = "orders-demo:" + role
		}
		roles = append(roles, role)
	}
	clientRef, _ := body["clientId"].(string)
	var client string
	switch clientRef {
	case "acme", "clt_acme":
		client = "clt_acme"
	case "globex", "clt_globex":
		client = "clt_globex"
	default:
		return failure(403, "FORBIDDEN", "client is not available to this application")
	}
	if len(roles) == 0 {
		return failure(400, "ROLES_REQUIRED", "at least one role of orders-demo is required")
	}
	for _, role := range roles {
		if !strings.HasPrefix(role, "orders-demo:") {
			return failure(403, "ROLE_APP_FORBIDDEN", "role '"+role+"' belongs to another application")
		}
		if !slices.Contains(demoRoles, role) {
			return failure(400, "UNKNOWN_ROLE", "'"+role+"' is not a role of orders-demo")
		}
	}
	email, _ := body["email"].(string)
	email = strings.ToLower(email)
	for _, u := range d.users {
		if u.email == email {
			return failure(409, "EMAIL_EXISTS", "a user with this email already exists")
		}
	}
	name, _ := body["name"].(string)
	added := &demoUser{fmt.Sprintf("prn_new%d", d.nextID), email, name, true, client, roles, "PENDING", "2026-10-16T08:00:00.000Z"}
	d.nextID++
	d.users = append(d.users, added)
	link, _ := body["returnInviteLink"].(bool)
	send := true
	if v, ok := body["sendInvitation"].(bool); ok {
		send = v
	}
	s := `{"id":` + quoted(added.id)
	if link {
		s += `,"inviteLink":"https://platform.invalid/set-password?token=demo-` + added.id + `"`
	}
	return reply(201, s+`,"invitationSent":`+strconv.FormatBool(send && !link)+`}`)
}

func anyList(v any) []any {
	l, _ := v.([]any)
	return l
}

func (d *demoPlatform) resend(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	id := resendPath.FindStringSubmatch(r.Path)[1]
	for _, u := range d.users {
		if u.id != id || !waiting(u) {
			continue
		}
		body := r.JSON()
		link, _ := body["returnInviteLink"].(bool)
		send := true
		if v, ok := body["sendInvitation"].(bool); ok {
			send = v
		}
		if !link && !send {
			return failure(400, "INVITATION_DELIVERY_REQUIRED", "a resend needs sendInvitation or returnInviteLink")
		}
		u.invitation = "PENDING"
		s := "{"
		if link {
			s += `"inviteLink":"https://platform.invalid/set-password?token=demo-again-` + u.id + `",`
		}
		return reply(200, s+`"expiresAt":"2026-10-30T08:00:00.000Z"}`)
	}
	return failure(404, "Invitation_NOT_FOUND", "Invitation not found")
}

func (d *demoPlatform) revoke(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	d.mu.Lock()
	defer d.mu.Unlock()
	id := invitationPath.FindStringSubmatch(r.Path)[1]
	for _, u := range d.users {
		if u.id == id && waiting(u) {
			u.active, u.invitation = false, ""
			return reply(204, "")
		}
	}
	return failure(404, "Invitation_NOT_FOUND", "Invitation not found")
}

// ---- events and dispatch jobs --------------------------------------------------------------------

var demoEvents = [][3]string{
	{"ev_01", "orders-demo:sales:order:placed", "o-1001"},
	{"ev_02", "orders-demo:sales:order:paid", "o-1001"},
	{"ev_03", "orders-demo:sales:order:shipped", "o-1001"},
	{"ev_04", "orders-demo:sales:order:placed", "o-1002"},
	{"ev_05", "orders-demo:sales:order:cancelled", "o-1002"},
	{"ev_06", "orders-demo:stock:item:counted", "sku-1"},
	{"ev_07", "orders-demo:sales:order:placed", "o-1003"},
	{"ev_08", "orders-demo:sales:order:paid", "o-1003"},
	{"ev_09", "orders-demo:sales:order:placed", "o-1004"},
	{"ev_10", "orders-demo:sales:order:placed", "o-1005"},
	{"ev_x1", "billing:invoice:invoice:issued", "inv-1"},
}

// eventNumber is the number in an event id, the way the Java directory reads it: every non-digit
// counts as a zero digit.
func eventNumber(id string) int {
	digits := []byte(id)
	for i, c := range digits {
		if c < '0' || c > '9' {
			digits[i] = '0'
		}
	}
	n, _ := strconv.Atoi(string(digits))
	return n
}

func eventJSON(e [3]string) string {
	p := strings.Split(e[1], ":")
	return `{"id":` + quoted(e[0]) + `,"type":` + quoted(e[1]) + `,"source":` + quoted(p[0]+":"+p[1]) + `,"subject":` + quoted(e[2]) +
		fmt.Sprintf(`,"time":"2026-10-15T10:%02d:00.000Z"`, eventNumber(e[0])%60) +
		`,"application":` + quoted(p[0]) + `,"subdomain":` + quoted(p[1]) + `,"aggregate":` + quoted(p[2]) + `,"correlationId":` + quoted("corr-"+e[2]) + `}`
}

func (d *demoPlatform) listEvents(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	q := queryOf(r)
	var all []string
	for _, e := range slices.Backward(demoEvents) {
		if v, ok := q["type"]; ok && v != e[1] {
			continue
		}
		if v, ok := q["subject"]; ok && !strings.Contains(e[2], v) {
			continue
		}
		if v, ok := q["applications"]; ok && !strings.HasPrefix(e[1], v+":") {
			continue
		}
		all = append(all, eventJSON(e))
	}
	return reply(200, window(all, q))
}

func window(all []string, q map[string]string) string {
	offset, limit := atoiOr(q["offset"], 0), atoiOr(q["limit"], 50)
	return "[" + strings.Join(all[min(offset, len(all)):min(len(all), offset+limit)], ",") + "]"
}

func (d *demoPlatform) getEvent(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	id := eventPath.FindStringSubmatch(r.Path)[1]
	for _, e := range demoEvents {
		if e[0] == id {
			read := eventJSON(e)
			return reply(200, read[:len(read)-1]+`,"data":{"orderId":`+quoted(e[2])+`,"note":"demo payload"},"contextData":[{"key":"channel","value":"web"}],"causationId":`+quoted("cause-"+e[0])+`}`)
		}
	}
	return failure(404, "Event_NOT_FOUND", "Event not found")
}

// demoJob is the job of an event: the second event of each order fails on another application's endpoint.
func demoJob(e [3]string) (id, jsonText, eventID, status string) {
	failed := e[0] == "ev_02" || e[0] == "ev_08"
	status = "COMPLETED"
	switch {
	case failed:
		status = "FAILED"
	case e[0] == "ev_10":
		status = "PENDING"
	}
	id = "dj_" + e[0][3:]
	attempts := 1
	if failed {
		attempts = 2
	}
	lastError := ""
	if failed {
		lastError = `,"lastError":"HTTP 500"`
	}
	jsonText = `{"id":` + quoted(id) + `,"eventId":` + quoted(e[0]) + `,"subscriptionId":"sub_billing","code":` + quoted(e[1]) + `,"status":` + quoted(status) +
		`,"kind":"EVENT","targetUrl":"https://billing.example.test/hooks/orders?token=demo-secret","mode":"IMMEDIATE","descriptor":"Billing sync",` +
		`"createdAt":"2026-10-15T10:30:00.000Z","updatedAt":"2026-10-15T10:31:00.000Z","application":` + quoted(strings.Split(e[1], ":")[0]) +
		`,"attemptCount":` + strconv.Itoa(attempts) + `,"protocol":"HTTP_WEBHOOK","timeoutSeconds":30,"maxRetries":3,"retryStrategy":"EXPONENTIAL"` + lastError + `}`
	return id, jsonText, e[0], status
}

func (d *demoPlatform) listJobs(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	q := queryOf(r)
	var all []string
	for _, e := range slices.Backward(demoEvents) {
		_, text, _, status := demoJob(e)
		if v, ok := q["statuses"]; ok && !strings.Contains(v, status) {
			continue
		}
		if v, ok := q["code"]; ok && e[1] != v {
			continue
		}
		if v, ok := q["applications"]; ok && !strings.HasPrefix(e[1], v+":") {
			continue
		}
		all = append(all, text)
	}
	return reply(200, window(all, q))
}

func (d *demoPlatform) jobsOfEvent(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	id := byEventPath.FindStringSubmatch(r.Path)[1]
	var out []string
	for _, e := range demoEvents {
		if e[0] == id {
			_, text, _, _ := demoJob(e)
			out = append(out, text)
		}
	}
	return reply(200, "["+strings.Join(out, ",")+"]")
}

func (d *demoPlatform) getJob(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	id := jobPath.FindStringSubmatch(r.Path)[1]
	for _, e := range demoEvents {
		if jobID, text, _, _ := demoJob(e); jobID == id {
			return reply(200, text)
		}
	}
	return failure(404, "DispatchJob_NOT_FOUND", "DispatchJob not found")
}

func (d *demoPlatform) attempts(r fakeplatform.Recorded) fakeplatform.Reply {
	if no := d.refused(); no != nil {
		return *no
	}
	id := attemptsPath.FindStringSubmatch(r.Path)[1]
	for _, e := range demoEvents {
		jobID, _, _, status := demoJob(e)
		if jobID != id {
			continue
		}
		failed := status == "FAILED"
		duration, code, body := 120, 200, "ok"
		if failed {
			duration, code, body = 912, 500, "upstream exploded"
		}
		first := fmt.Sprintf(`{"attemptNumber":1,"attemptedAt":"2026-10-15T10:30:01.000Z","completedAt":"2026-10-15T10:30:02.000Z","durationMillis":%d,"responseCode":%d,"responseBody":%s,"success":%t`,
			duration, code, quoted(body), !failed)
		if failed {
			first += `,"errorMessage":"HTTP 500","errorType":"HTTP_ERROR"`
		}
		first += "}"
		out := first
		if failed {
			out += `,{"attemptNumber":2,"attemptedAt":"2026-10-15T10:31:01.000Z","durationMillis":30000,"success":false,"errorMessage":"timed out","errorType":"TIMEOUT"}`
		}
		return reply(200, "["+out+"]")
	}
	return failure(404, "DispatchJob_NOT_FOUND", "DispatchJob not found")
}
