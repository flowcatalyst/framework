package flowcatalyst

import (
	"context"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"strings"

	fcsync "github.com/flowcatalyst/flowcatalyst/pkg/fcsdk/sync"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// SyncMode says whether a sync sends anything.
type SyncMode int

const (
	// SyncApply sends the definitions to the platform.
	SyncApply SyncMode = iota
	// SyncDryRun validates and reports what would be sent; no request is made.
	SyncDryRun
)

// Category is a kind of definition, in the order sync sends them.
type Category int

// The categories, in sync order: roles, event types, connections, dispatch pools, subscriptions,
// processes, scheduled jobs and documentation.
const (
	CategoryRoles Category = iota + 1
	CategoryEventTypes
	CategoryConnections
	CategoryDispatchPools
	CategorySubscriptions
	CategoryProcesses
	CategoryScheduledJobs
	CategoryDocs
)

func (c Category) String() string {
	switch c {
	case CategoryRoles:
		return "ROLES"
	case CategoryEventTypes:
		return "EVENT_TYPES"
	case CategoryConnections:
		return "CONNECTIONS"
	case CategoryDispatchPools:
		return "DISPATCH_POOLS"
	case CategorySubscriptions:
		return "SUBSCRIPTIONS"
	case CategoryProcesses:
		return "PROCESSES"
	case CategoryScheduledJobs:
		return "SCHEDULED_JOBS"
	case CategoryDocs:
		return "DOCS"
	}
	return "UNKNOWN"
}

// Outcome is what happened to one category.
type Outcome int

const (
	// OutcomeSynced means the category was sent and accepted.
	OutcomeSynced Outcome = iota + 1
	// OutcomeWouldSync means a dry run would have sent it.
	OutcomeWouldSync
	// OutcomeSkipped means there was nothing to send.
	OutcomeSkipped
	// OutcomeFailed means sending it failed.
	OutcomeFailed
)

func (o Outcome) String() string {
	switch o {
	case OutcomeSynced:
		return "SYNCED"
	case OutcomeWouldSync:
		return "WOULD_SYNC"
	case OutcomeSkipped:
		return "SKIPPED"
	case OutcomeFailed:
		return "FAILED"
	}
	return "UNKNOWN"
}

// CategoryResult is the result for one category.
type CategoryResult struct {
	Category Category
	Outcome  Outcome
	// Sent is the number of definitions of the category.
	Sent                      int
	Created, Updated, Deleted int
	Codes                     []string
	Message                   string
}

// Describe is the line `sync` prints: the category and outcome padded to 15 and 10 columns, then
// the counts (Synced), the codes a dry run would send (WouldSync) and the message.
func (r CategoryResult) Describe() string {
	var sb strings.Builder
	fmt.Fprintf(&sb, "%-15s %-10s", r.Category, r.Outcome)
	switch r.Outcome {
	case OutcomeSynced:
		fmt.Fprintf(&sb, " sent=%d created=%d updated=%d deleted=%d", r.Sent, r.Created, r.Updated, r.Deleted)
	case OutcomeWouldSync:
		fmt.Fprintf(&sb, " would send %d: %s", r.Sent, strings.Join(r.Codes, ", "))
	case OutcomeSkipped, OutcomeFailed:
	}
	if r.Message != "" {
		sb.WriteString(" - " + r.Message)
	}
	return strings.TrimRight(sb.String(), " \t\n\r")
}

// SyncReport is the result of a sync: one result per category, in sync order.
type SyncReport struct {
	Mode       SyncMode
	Categories []CategoryResult
}

// Of returns the result of a category.
func (r SyncReport) Of(c Category) (CategoryResult, bool) {
	for _, x := range r.Categories {
		if x.Category == c {
			return x, true
		}
	}
	return CategoryResult{}, false
}

// HasFailures reports whether any category failed.
func (r SyncReport) HasFailures() bool {
	for _, x := range r.Categories {
		if x.Outcome == OutcomeFailed {
			return true
		}
	}
	return false
}

// Describe returns the report's lines.
func (r SyncReport) Describe() []string {
	out := make([]string, len(r.Categories))
	for i, c := range r.Categories {
		out[i] = c.Describe()
	}
	return out
}

// SyncError is returned by a sync in which a category failed, after all of them were attempted.
type SyncError struct{ Report SyncReport }

func (e *SyncError) Error() string {
	var failed []string
	for _, c := range e.Report.Categories {
		if c.Outcome == OutcomeFailed {
			failed = append(failed, fmt.Sprintf("%s (%s)", c.Category, c.Message))
		}
	}
	return "definition sync failed: " + strings.Join(failed, "; ")
}

type step struct {
	category Category
	codes    []string
	flag     func(*fcsync.Options)
	pick     func(*fcsync.Result) *fcsync.CategoryResult
}

func (p *Platform) steps() []step {
	c := p.catalog
	codes := func(n int, f func(i int) string) []string {
		out := make([]string, n)
		for i := range n {
			out[i] = f(i)
		}
		return out
	}
	return []step{
		{
			CategoryRoles, codes(len(c.roles), func(i int) string { return c.roles[i].Name }),
			func(o *fcsync.Options) { o.SyncRoles = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.Roles },
		},
		{
			CategoryEventTypes, codes(len(c.eventTypes), func(i int) string { return c.eventTypes[i].Code }),
			func(o *fcsync.Options) { o.SyncEventTypes = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.EventTypes },
		},
		{
			CategoryConnections, codes(len(c.connections), func(i int) string { return c.connections[i].Code }),
			func(o *fcsync.Options) { o.SyncConnections = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.Connections },
		},
		{
			CategoryDispatchPools, codes(len(c.pools), func(i int) string { return c.pools[i].Code }),
			func(o *fcsync.Options) { o.SyncDispatchPools = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.DispatchPools },
		},
		{
			CategorySubscriptions, codes(len(c.subscriptions), func(i int) string { return c.subscriptions[i].Code }),
			func(o *fcsync.Options) { o.SyncSubscriptions = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.Subscriptions },
		},
		{
			CategoryProcesses, codes(len(c.processes), func(i int) string { return c.processes[i].Code }),
			func(o *fcsync.Options) { o.SyncProcesses = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.Processes },
		},
		{
			CategoryScheduledJobs, codes(len(c.jobs), func(i int) string { return c.jobs[i].Code }),
			func(o *fcsync.Options) { o.SyncScheduledJobs = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.ScheduledJobs },
		},
		{
			CategoryDocs, codes(len(c.docs), func(i int) string { return c.docs[i].Slug }),
			func(o *fcsync.Options) { o.SyncDocs = true }, func(r *fcsync.Result) *fcsync.CategoryResult { return r.Docs },
		},
	}
}

// Sync pushes the definitions to the platform (flowcatalyst contract section 2): one SDK call per
// category, in the order roles, event types, connections, dispatch pools, subscriptions,
// processes, scheduled jobs, docs, so one category's failure does not stop the others. A dry run
// validates (the catalog was validated at Build) and reports what would be sent; it makes no
// request. It returns a [*SyncError], with the report, when any category failed after all were
// attempted. Sync is idempotent; removing a definition from code does not delete it on the
// platform (the framework never sets the SDK's remove-unlisted flag: deletions are an operator
// action). Permissions travel only inside roles; a declared permission no role grants is a
// warning.
func (p *Platform) Sync(ctx context.Context, mode SyncMode) (SyncReport, error) {
	if !p.client.Enabled() {
		return SyncReport{}, ErrDisabled
	}
	p.warnAboutUnusedPermissions()
	set := p.definitionSet()
	sy := fcsync.NewSynchronizer(p.client.sdk)
	report := SyncReport{Mode: mode}
	for _, st := range p.steps() {
		var res CategoryResult
		switch {
		case len(st.codes) == 0:
			res = CategoryResult{Category: st.category, Outcome: OutcomeSkipped}
		case mode == SyncDryRun:
			res = CategoryResult{Category: st.category, Outcome: OutcomeWouldSync, Sent: len(st.codes), Codes: st.codes}
		default:
			var opts fcsync.Options
			st.flag(&opts)
			out := sy.Sync(ctx, set, opts)
			res = fromSDK(st, st.pick(out))
		}
		p.log.Info("sync " + res.Describe())
		report.Categories = append(report.Categories, res)
	}
	if report.HasFailures() {
		return report, &SyncError{Report: report}
	}
	return report, nil
}

func fromSDK(st step, r *fcsync.CategoryResult) CategoryResult {
	if r == nil {
		return CategoryResult{Category: st.category, Outcome: OutcomeSkipped}
	}
	res := CategoryResult{
		Category: st.category, Sent: len(st.codes), Created: int(r.Created), Updated: int(r.Updated),
		Deleted: int(r.Deleted), Codes: r.SyncedCodes,
	}
	if r.IsError() {
		res.Outcome, res.Message = OutcomeFailed, r.Error
		return res
	}
	res.Outcome = OutcomeSynced
	return res
}

func (p *Platform) warnAboutUnusedPermissions() {
	granted := map[string]bool{}
	for _, r := range p.catalog.roles {
		for _, perm := range r.Permissions {
			granted[strings.ToLower(perm)] = true
		}
	}
	for _, perm := range p.catalog.permissions {
		if !granted[perm.Code] {
			p.log.Warn(fmt.Sprintf("sync: permission '%s' is declared but no role grants it; permissions reach the platform only through roles, so it is not sent", perm.Code))
		}
	}
}

// definitionSet maps the catalog onto the SDK's definition set.
func (p *Platform) definitionSet() *fcsync.DefinitionSet {
	c := p.catalog
	set := fcsync.ForApplication(p.client.app.String())
	for _, r := range c.roles {
		set.AddRole(fcsync.RoleDefinition{Name: r.Name, DisplayName: r.DisplayName, Description: r.Description, Permissions: r.Permissions})
	}
	for _, e := range c.eventTypes {
		set.AddEventType(fcsync.EventTypeDefinition{Code: e.Code, Name: e.Name, Description: e.Description})
	}
	for _, x := range c.connections {
		set.AddConnection(fcsync.ConnectionDefinition{Code: x.Code, Name: x.Name, Description: x.Description})
	}
	for _, s := range c.subscriptions {
		set.AddSubscription(p.subscription(s))
	}
	for _, d := range c.pools {
		def := fcsync.DispatchPoolDefinition{Code: d.Code, Name: d.Name, Description: d.Description, Concurrency: uint32(max(d.Concurrency, 0))} //nolint:gosec // validated non-negative
		if d.RateLimit > 0 {
			rl := uint32(d.RateLimit) //nolint:gosec // validated non-negative
			def.RateLimit = &rl
		}
		set.AddDispatchPool(def)
	}
	for _, x := range c.processes {
		set.AddProcess(fcsync.ProcessDefinition{Code: x.Code, Name: x.Name, Description: x.Description, Body: x.Body, DiagramType: x.DiagramType, Tags: x.Tags})
	}
	for _, j := range c.jobs {
		set.AddScheduledJob(p.scheduledJob(j))
	}
	for _, d := range c.docs {
		set.AddDoc(fcsync.DocDefinition{Slug: d.Slug, Title: d.Title, Content: d.Content})
	}
	return set
}

func (p *Platform) subscription(s Subscription) fcsync.SubscriptionDefinition {
	def := fcsync.SubscriptionDefinition{
		Code: s.Code, Name: s.Name, Description: s.Description, Target: p.resolveTarget(s.Target),
		ConnectionCode: s.ConnectionCode, SharedConnection: s.SharedConnection, DispatchPoolCode: s.DispatchPoolCode,
		Mode: string(s.Mode), DataOnly: s.DataOnly,
	}
	if s.MaxRetries > 0 {
		n := uint32(s.MaxRetries) //nolint:gosec // validated non-negative
		def.MaxRetries = &n
	}
	if s.TimeoutSeconds > 0 {
		n := uint32(s.TimeoutSeconds) //nolint:gosec // validated non-negative
		def.TimeoutSeconds = &n
	}
	for _, et := range s.EventTypes {
		def.EventTypes = append(def.EventTypes, fcsync.SubscriptionBinding{EventTypeCode: et})
	}
	return def
}

// resolveTarget resolves a path target against flowcatalyst.subscription-target-base-url (the SDK
// has no such setting): the base without a trailing slash, then the path.
func (p *Platform) resolveTarget(target string) string {
	target = strings.TrimSpace(target)
	if strings.HasPrefix(target, "/") && p.targetBase != "" {
		return strings.TrimRight(p.targetBase, "/") + target
	}
	return target
}

// jobTarget is where the platform delivers a job's firings. The platform has no default for it (a job
// with no target fails every firing with "No target URL configured for job"), so a job that names
// none is delivered to this service's own scheduled-job receiver, {subscription-target-base-url}{receivers.path}/scheduled-jobs;
// a path target is resolved against the base like a subscription's. With no base configured the
// job's own absolute URL, or none, is sent as declared.
func (p *Platform) jobTarget(target string) string {
	target = strings.TrimSpace(target)
	if p.targetBase == "" {
		return target
	}
	if target == "" {
		if !p.recv.hasJobs() || !p.s.rEnabled.Get(p.b) {
			return ""
		}
		target = p.s.rPath.Get(p.b) + "/scheduled-jobs"
	}
	return p.resolveTarget(target)
}

func (p *Platform) scheduledJob(j ScheduledJob) fcsync.ScheduledJobDefinition {
	def := fcsync.ScheduledJobDefinition{
		Code: j.Code, Name: j.Name, Description: j.Description, Crons: j.Crons, Timezone: j.Timezone,
		Concurrent: j.Concurrent, TracksCompletion: j.TracksCompletion, TargetURL: p.jobTarget(j.TargetURL),
	}
	if j.Payload != nil {
		if b, err := javafmt.MarshalJSON(j.Payload); err == nil {
			def.Payload = b
		} else {
			p.log.Warn(fmt.Sprintf("sync: the payload of scheduled job '%s' could not be encoded as JSON; it is not sent", j.Code), slog.String("type", fmt.Sprintf("%T", j.Payload)))
		}
	}
	if j.TimeoutSeconds > 0 {
		n := int32(j.TimeoutSeconds) //nolint:gosec // validated, and a timeout in seconds fits
		def.TimeoutSeconds = &n
	}
	if j.DeliveryMaxAttempts > 0 {
		n := int32(j.DeliveryMaxAttempts) //nolint:gosec // validated
		def.DeliveryMaxAttempts = &n
	}
	return def
}

// syncCommand is the `sync [--dry-run]` command (flowcatalyst contract section 2): exit 0 on
// success, 1 when a category failed, 2 for a bad argument or a disabled module.
func (p *Platform) syncCommand(ctx context.Context, args []string, out io.Writer) int {
	mode := SyncApply
	for _, a := range args {
		if a != "--dry-run" {
			fmt.Fprintln(out, "unknown argument: "+a)
			fmt.Fprintln(out, "usage: sync [--dry-run]")
			return 2
		}
		mode = SyncDryRun
	}
	if !p.client.Enabled() {
		fmt.Fprintln(out, "flowcatalyst is disabled (flowcatalyst.enabled=false); nothing to sync")
		return 2
	}
	report, err := p.Sync(ctx, mode)
	for _, line := range report.Describe() {
		fmt.Fprintln(out, line)
	}
	var se *SyncError
	switch {
	case errors.As(err, &se):
		fmt.Fprintln(out, "sync failed")
		return 1
	case err != nil:
		fmt.Fprintln(out, "sync failed: "+err.Error())
		return 1
	case mode == SyncDryRun:
		fmt.Fprintln(out, "dry run: nothing was sent")
	default:
		fmt.Fprintln(out, "sync complete")
	}
	return 0
}
