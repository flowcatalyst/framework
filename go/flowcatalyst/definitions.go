package flowcatalyst

import (
	"fmt"
	"net/url"
	"regexp"
	"slices"
	"strings"
	"sync"

	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
)

// The definitions an application declares for the platform (flowcatalyst contract section 2).
// Plain values with fluent With... copies; [Definitions] collects them, [Build] validates them and
// [Platform.Sync] sends them.

// EventType is an event type the application publishes: {application}:{domain}:{aggregate}:{action}.
type EventType struct {
	Code, Name, Description string
}

// Permission is a permission of the application. Permissions reach the platform only inside the
// roles that grant them.
type Permission struct {
	Code, Description string
}

// Role is a role of the application: Name without the "app:" prefix (the platform adds it) and the
// codes of the permissions it grants.
type Role struct {
	Name        string
	Permissions []string
	DisplayName string
	Description string
}

// NewRole starts a role.
func NewRole(name string, permissions ...string) Role {
	return Role{Name: name, Permissions: slices.Clone(permissions)}
}

// WithDisplayName returns a copy with a display name.
func (r Role) WithDisplayName(s string) Role { r.DisplayName = s; return r }

// WithDescription returns a copy with a description.
func (r Role) WithDescription(s string) Role { r.Description = s; return r }

// DeliveryMode is how a subscription delivers.
type DeliveryMode string

// The delivery modes.
const (
	// DeliverImmediate delivers each message at once.
	DeliverImmediate DeliveryMode = "IMMEDIATE"
	// DeliverBlockOnError holds a message group's later messages while one fails.
	DeliverBlockOnError DeliveryMode = "BLOCK_ON_ERROR"
)

// Subscription delivers event types to a target: an absolute http(s) URL, or a path that resolves
// against flowcatalyst.subscription-target-base-url.
type Subscription struct {
	Code, Name, Description, Target string
	EventTypes                      []string
	ConnectionCode                  string
	SharedConnection                bool
	DispatchPoolCode                string
	Mode                            DeliveryMode
	// MaxRetries and TimeoutSeconds are positive, or 0 for the platform's default.
	MaxRetries     int
	TimeoutSeconds int
	DataOnly       bool
}

// NewSubscription starts a subscription to the event types.
func NewSubscription(code, name, target string, eventTypes ...string) Subscription {
	return Subscription{Code: code, Name: name, Target: target, EventTypes: slices.Clone(eventTypes)}
}

// ViaConnection returns a copy delivering through this application's connection.
func (s Subscription) ViaConnection(code string) Subscription {
	s.ConnectionCode, s.SharedConnection = code, false
	return s
}

// ViaSharedConnection returns a copy delivering through a shared (application-less) connection.
func (s Subscription) ViaSharedConnection(code string) Subscription {
	s.ConnectionCode, s.SharedConnection = code, true
	return s
}

// InPool returns a copy dispatched in a pool.
func (s Subscription) InPool(code string) Subscription { s.DispatchPoolCode = code; return s }

// WithMode returns a copy with a delivery mode.
func (s Subscription) WithMode(m DeliveryMode) Subscription { s.Mode = m; return s }

// WithDescription returns a copy with a description.
func (s Subscription) WithDescription(d string) Subscription { s.Description = d; return s }

// Connection is a connection of the application, so a subscription's connection code resolves.
type Connection struct {
	Code, Name, Description string
}

// DispatchPool is a dispatch pool: RateLimit (per minute) and Concurrency are positive, or 0 for
// none.
type DispatchPool struct {
	Code, Name, Description string
	RateLimit, Concurrency  int
}

// ScheduledJob is a scheduled job. Crons have six fields, seconds first (a five-field cron never
// fires). Payload is anything JSON-encodable.
type ScheduledJob struct {
	Code, Name, Description string
	Crons                   []string
	Timezone                string
	Payload                 any
	Concurrent              bool
	TracksCompletion        bool
	// TimeoutSeconds and DeliveryMaxAttempts are positive, or 0 for the platform's default.
	TimeoutSeconds      int
	DeliveryMaxAttempts int
	TargetURL           string
}

// NewScheduledJob starts a job with its cron expressions.
func NewScheduledJob(code, name string, crons ...string) ScheduledJob {
	return ScheduledJob{Code: code, Name: name, Crons: slices.Clone(crons)}
}

// Tracking returns a copy that tracks completion (the platform waits for the report).
func (j ScheduledJob) Tracking(v bool) ScheduledJob { j.TracksCompletion = v; return j }

// InZone returns a copy evaluated in a time zone.
func (j ScheduledJob) InZone(tz string) ScheduledJob { j.Timezone = tz; return j }

// AllowingConcurrent returns a copy that lets a new firing start while the previous one runs.
func (j ScheduledJob) AllowingConcurrent(v bool) ScheduledJob { j.Concurrent = v; return j }

// WithPayload returns a copy that sends payload (any JSON value) with every firing.
func (j ScheduledJob) WithPayload(payload any) ScheduledJob { j.Payload = payload; return j }

// Targeting returns a copy delivered to an absolute http(s) URL instead of the platform's default
// for the application.
func (j ScheduledJob) Targeting(url string) ScheduledJob { j.TargetURL = url; return j }

// WithTimeout returns a copy with a positive per-firing timeout in seconds (0 is the platform's
// default).
func (j ScheduledJob) WithTimeout(seconds int) ScheduledJob { j.TimeoutSeconds = seconds; return j }

// WithDeliveryAttempts returns a copy with a positive number of delivery attempts (0 is the
// platform's default).
func (j ScheduledJob) WithDeliveryAttempts(n int) ScheduledJob { j.DeliveryMaxAttempts = n; return j }

// Process is a documented process: {application}:{subdomain}:{process}.
type Process struct {
	Code, Name, Description, Body, DiagramType string
	Tags                                       []string
}

// Doc is a documentation page: a kebab-case slug and its content.
type Doc struct {
	Slug, Content, Title string
}

type contribution struct {
	module string
	def    any
}

// Definitions collects the definitions of an application, contributed by its modules while it is
// declared. [Declared.Definitions] gives one for a contributing module (named in problems).
type Definitions struct {
	reg    *registry
	module string
}

type registry struct {
	mu   sync.Mutex
	list []contribution
}

func (r *registry) add(module string, def any) {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.list = append(r.list, contribution{module: module, def: def})
}

func (r *registry) all() []contribution {
	r.mu.Lock()
	defer r.mu.Unlock()
	return slices.Clone(r.list)
}

// Definitions returns the collector for the contributing module (a module id such as "orders",
// named in the problems Build reports). Validation needs the application code, which is
// configuration, so it happens in [Build], not here.
func (d *Declared) Definitions(module string) *Definitions {
	return &Definitions{reg: d.reg, module: module}
}

// EventType declares an event type.
func (d *Definitions) EventType(code, name, description string) *Definitions {
	d.reg.add(d.module, EventType{Code: code, Name: name, Description: description})
	return d
}

// EventTypeOf declares the event type of a typed constant (event.MustType).
func (d *Definitions) EventTypeOf(t event.Type, name, description string) *Definitions {
	return d.EventType(t.String(), name, description)
}

// Permission declares a permission.
func (d *Definitions) Permission(code, description string) *Definitions {
	d.reg.add(d.module, Permission{Code: code, Description: description})
	return d
}

// Role declares a role.
func (d *Definitions) Role(r Role) *Definitions { d.reg.add(d.module, r); return d }

// Subscription declares a subscription.
func (d *Definitions) Subscription(s Subscription) *Definitions { d.reg.add(d.module, s); return d }

// Connection declares a connection.
func (d *Definitions) Connection(code, name string) *Definitions {
	d.reg.add(d.module, Connection{Code: code, Name: name})
	return d
}

// DispatchPool declares a dispatch pool.
func (d *Definitions) DispatchPool(p DispatchPool) *Definitions { d.reg.add(d.module, p); return d }

// ScheduledJob declares a scheduled job.
func (d *Definitions) ScheduledJob(j ScheduledJob) *Definitions { d.reg.add(d.module, j); return d }

// Process declares a process.
func (d *Definitions) Process(p Process) *Definitions { d.reg.add(d.module, p); return d }

// Docs declares a documentation page.
func (d *Definitions) Docs(slug, content string) *Definitions {
	d.reg.add(d.module, Doc{Slug: slug, Content: content})
	return d
}

// Catalog is the validated set of definitions: codes unique across modules, in contribution order.
type Catalog struct {
	eventTypes    []EventType
	permissions   []Permission
	roles         []Role
	connections   []Connection
	subscriptions []Subscription
	pools         []DispatchPool
	jobs          []ScheduledJob
	processes     []Process
	docs          []Doc
}

// EventTypes returns the event types.
func (c *Catalog) EventTypes() []EventType { return slices.Clone(c.eventTypes) }

// Permissions returns the permissions.
func (c *Catalog) Permissions() []Permission { return slices.Clone(c.permissions) }

// Roles returns the roles.
func (c *Catalog) Roles() []Role { return slices.Clone(c.roles) }

// Connections returns the connections.
func (c *Catalog) Connections() []Connection { return slices.Clone(c.connections) }

// Subscriptions returns the subscriptions.
func (c *Catalog) Subscriptions() []Subscription { return slices.Clone(c.subscriptions) }

// DispatchPools returns the dispatch pools.
func (c *Catalog) DispatchPools() []DispatchPool { return slices.Clone(c.pools) }

// ScheduledJobs returns the scheduled jobs.
func (c *Catalog) ScheduledJobs() []ScheduledJob { return slices.Clone(c.jobs) }

// Processes returns the processes.
func (c *Catalog) Processes() []Process { return slices.Clone(c.processes) }

// Docs returns the documentation pages.
func (c *Catalog) Docs() []Doc { return slices.Clone(c.docs) }

var (
	segmentPattern = regexp.MustCompile(`^[a-z0-9][a-z0-9_-]*$`)
	codePattern    = regexp.MustCompile(`^[a-z][a-z0-9_-]*$`)
	slugPattern    = regexp.MustCompile(`^[a-z0-9][a-z0-9-]*$`)
)

// buildCatalog validates the contributions against the application code. The rules are stricter
// than the platform's, as the Java module's are: lowercase [a-z0-9][a-z0-9_-]* segments; event
// type, permission and process codes start with the application code; role names carry no "app:"
// prefix; cron has 6 fields; subscriptions name at least one event type; codes are unique across
// modules. Every problem is reported, one per issue, naming the contributing module.
func buildCatalog(list []contribution, app fw.ApplicationCode, targetBase bool) (*Catalog, []fw.Problem) {
	v := &validation{app: app.String(), targetBase: targetBase, first: map[string]string{}, cat: &Catalog{}}
	for _, c := range list {
		v.check(c.module, c.def)
	}
	if len(v.problems) > 0 {
		return nil, v.problems
	}
	return v.cat, nil
}

type validation struct {
	app        string
	targetBase bool
	problems   []fw.Problem
	first      map[string]string
	cat        *Catalog
}

func (v *validation) problem(module, key, msg string) {
	v.problems = append(v.problems, fw.Problem{Module: module, Key: key, Message: msg})
}

func (v *validation) unique(module, what, key string) bool {
	id := what + "/" + key
	if first, dup := v.first[id]; dup {
		v.problem(module, key, "duplicate "+what+", already declared by module '"+first+"'")
		return false
	}
	v.first[id] = module
	return true
}

func (v *validation) check(module string, def any) {
	switch d := def.(type) {
	case EventType:
		v.fourPart(module, "event type", d.Code, true)
		v.nonBlank(module, d.Code, "name", d.Name)
		if v.unique(module, "event type", d.Code) {
			v.cat.eventTypes = append(v.cat.eventTypes, d)
		}
	case Permission:
		v.fourPart(module, "permission", d.Code, true)
		if v.unique(module, "permission", d.Code) {
			v.cat.permissions = append(v.cat.permissions, d)
		}
	case Role:
		switch {
		case strings.Contains(d.Name, ":"):
			v.problem(module, d.Name, "role name must not include the application prefix; the platform adds '"+v.app+":'")
		case !codePattern.MatchString(d.Name):
			v.problem(module, d.Name, "role name must match "+codePattern.String())
		}
		for _, p := range d.Permissions {
			v.fourPart(module, "permission of role '"+d.Name+"'", p, false)
		}
		if v.unique(module, "role", d.Name) {
			v.cat.roles = append(v.cat.roles, d)
		}
	case Connection:
		v.code(module, "connection", d.Code)
		v.nonBlank(module, d.Code, "name", d.Name)
		if v.unique(module, "connection", d.Code) {
			v.cat.connections = append(v.cat.connections, d)
		}
	case Subscription:
		v.subscription(module, d)
	case DispatchPool:
		v.code(module, "dispatch pool", d.Code)
		v.nonBlank(module, d.Code, "name", d.Name)
		v.positive(module, d.Code, "rateLimit", d.RateLimit)
		v.positive(module, d.Code, "concurrency", d.Concurrency)
		if v.unique(module, "dispatch pool", d.Code) {
			v.cat.pools = append(v.cat.pools, d)
		}
	case ScheduledJob:
		v.scheduledJob(module, d)
	case Process:
		parts := strings.Split(d.Code, ":")
		switch {
		case len(parts) != 3 || !allSegments(parts):
			v.problem(module, d.Code, "process code must be {application}:{subdomain}:{process}")
		case parts[0] != v.app:
			v.problem(module, d.Code, "process code must start with the application '"+v.app+"'")
		}
		v.nonBlank(module, d.Code, "name", d.Name)
		if v.unique(module, "process", d.Code) {
			v.cat.processes = append(v.cat.processes, d)
		}
	case Doc:
		if !slugPattern.MatchString(d.Slug) {
			v.problem(module, d.Slug, "documentation slug must be kebab-case, matching "+slugPattern.String())
		}
		v.nonBlank(module, d.Slug, "content", d.Content)
		if v.unique(module, "documentation page", d.Slug) {
			v.cat.docs = append(v.cat.docs, d)
		}
	}
}

func (v *validation) subscription(module string, s Subscription) {
	v.code(module, "subscription", s.Code)
	v.nonBlank(module, s.Code, "name", s.Name)
	if len(s.EventTypes) == 0 {
		v.problem(module, s.Code, "a subscription needs at least one event type")
	}
	for _, et := range s.EventTypes {
		v.fourPart(module, "event type of subscription '"+s.Code+"'", et, false)
	}
	if s.SharedConnection && s.ConnectionCode == "" {
		v.problem(module, s.Code, "sharedConnection needs a connection code")
	}
	if s.ConnectionCode != "" {
		v.code(module, "connection of subscription '"+s.Code+"'", s.ConnectionCode)
	}
	if s.DispatchPoolCode != "" {
		v.code(module, "dispatch pool of subscription '"+s.Code+"'", s.DispatchPoolCode)
	}
	v.positive(module, s.Code, "maxRetries", s.MaxRetries)
	v.positive(module, s.Code, "timeoutSeconds", s.TimeoutSeconds)
	if s.Mode != "" && s.Mode != DeliverImmediate && s.Mode != DeliverBlockOnError {
		v.problem(module, s.Code, "mode must be IMMEDIATE or BLOCK_ON_ERROR")
	}
	target := strings.TrimSpace(s.Target)
	switch {
	case target == "":
		v.problem(module, s.Code, "target is blank")
	case strings.HasPrefix(target, "/"):
		if !v.targetBase {
			v.problem(module, s.Code, "target '"+target+"' is a path; set flowcatalyst.subscription-target-base-url or use an absolute URL")
		}
	case !absoluteHTTP(target):
		v.problem(module, s.Code, "target must be an absolute http(s) URL or a path starting with '/'")
	}
	if v.unique(module, "subscription", s.Code) {
		v.cat.subscriptions = append(v.cat.subscriptions, s)
	}
}

func (v *validation) scheduledJob(module string, j ScheduledJob) {
	v.code(module, "scheduled job", j.Code)
	v.nonBlank(module, j.Code, "name", j.Name)
	if len(j.Crons) == 0 {
		v.problem(module, j.Code, "a scheduled job needs at least one cron expression")
	}
	for _, cron := range j.Crons {
		if len(strings.Fields(cron)) != 6 {
			v.problem(module, j.Code, fmt.Sprintf("cron '%s' must have 6 fields, seconds first (a 5-field cron never fires)", cron))
		}
	}
	v.positive(module, j.Code, "timeoutSeconds", j.TimeoutSeconds)
	v.positive(module, j.Code, "deliveryMaxAttempts", j.DeliveryMaxAttempts)
	if j.TargetURL != "" && !absoluteHTTP(strings.TrimSpace(j.TargetURL)) {
		v.problem(module, j.Code, "targetUrl must be an absolute http(s) URL")
	}
	if v.unique(module, "scheduled job", j.Code) {
		v.cat.jobs = append(v.cat.jobs, j)
	}
}

func (v *validation) fourPart(module, what, value string, own bool) {
	parts := strings.Split(value, ":")
	switch {
	case len(parts) != 4 || !allSegments(parts):
		v.problem(module, value, what+" code must be {application}:{domain}:{aggregate}:{action}, lowercase, matching "+segmentPattern.String()+" in each part")
	case own && parts[0] != v.app:
		v.problem(module, value, what+" code must start with the application '"+v.app+"'")
	}
}

func allSegments(parts []string) bool {
	for _, p := range parts {
		if !segmentPattern.MatchString(p) {
			return false
		}
	}
	return true
}

func (v *validation) code(module, what, value string) {
	if !codePattern.MatchString(value) {
		v.problem(module, value, what+" code must match "+codePattern.String())
	}
}

func (v *validation) nonBlank(module, key, field, value string) {
	if strings.TrimSpace(value) == "" {
		v.problem(module, key, field+" is blank")
	}
}

func (v *validation) positive(module, key, field string, n int) {
	if n < 0 {
		v.problem(module, key, field+" must be positive")
	}
}

func absoluteHTTP(value string) bool {
	u, err := url.Parse(value)
	if err != nil {
		return false
	}
	return (u.Scheme == "http" || u.Scheme == "https") && u.Hostname() != ""
}
