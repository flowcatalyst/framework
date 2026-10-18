package platformadmin

import (
	"context"
	"fmt"
	"net/http"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

const (
	// dataLimit is the longest event payload shown, in characters.
	dataLimit = 20_000
	// bodyLimit is the longest subscriber response shown, in characters.
	bodyLimit = 4_000
)

var statusPattern = regexp.MustCompile(`^[A-Z_]{1,40}$`)

// Event is an event in a list.
type Event struct {
	ID            string  `json:"id"`
	Type          string  `json:"type"`
	Source        string  `json:"source"`
	Subject       *string `json:"subject,omitzero"`
	Time          string  `json:"time"`
	MessageGroup  *string `json:"messageGroup,omitzero"`
	CorrelationID *string `json:"correlationId,omitzero"`
	ClientID      *string `json:"clientId,omitzero"`
}

// ContextEntry is one key and value of an event's context data.
type ContextEntry struct {
	Key   string `json:"key"`
	Value string `json:"value"`
}

// DispatchJob is the delivery of an event (or a task) to a subscriber's endpoint. The target may
// belong to another application's subscription: the job is this application's because its code is
// this application's event type.
type DispatchJob struct {
	ID             string  `json:"id"`
	EventID        *string `json:"eventId,omitzero"`
	SubscriptionID *string `json:"subscriptionId,omitzero"`
	ClientID       *string `json:"clientId,omitzero"`
	Code           string  `json:"code"`
	Status         string  `json:"status"`
	Kind           string  `json:"kind"`
	TargetURL      string  `json:"targetUrl"`
	Mode           string  `json:"mode"`
	MessageGroup   *string `json:"messageGroup,omitzero"`
	Descriptor     *string `json:"descriptor,omitzero"`
	CreatedAt      string  `json:"createdAt"`
	LastAttemptAt  *string `json:"lastAttemptAt,omitzero"`
	CompletedAt    *string `json:"completedAt,omitzero"`
	AttemptCount   int32   `json:"attemptCount"`
}

// EventDetail is an event with its payload (JSON text cut at 20 000 characters) and the jobs that
// deliver it.
type EventDetail struct {
	Event         Event          `json:"event"`
	Data          *string        `json:"data,omitzero"`
	DataTruncated bool           `json:"dataTruncated"`
	Context       []ContextEntry `json:"context"`
	CausationID   *string        `json:"causationId,omitzero"`
	DispatchJobs  []DispatchJob  `json:"dispatchJobs"`
}

// Attempt is one delivery attempt; the response body is cut at 4 000 characters.
type Attempt struct {
	AttemptNumber         int32   `json:"attemptNumber"`
	AttemptedAt           string  `json:"attemptedAt"`
	CompletedAt           *string `json:"completedAt,omitzero"`
	DurationMillis        *int64  `json:"durationMillis,omitzero"`
	ResponseCode          *int32  `json:"responseCode,omitzero"`
	ResponseBody          *string `json:"responseBody,omitzero"`
	ResponseBodyTruncated bool    `json:"responseBodyTruncated"`
	Success               bool    `json:"success"`
	ErrorMessage          *string `json:"errorMessage,omitzero"`
	ErrorType             *string `json:"errorType,omitzero"`
}

// DispatchJobDetail is a dispatch job with its delivery attempts.
type DispatchJobDetail struct {
	Job            DispatchJob `json:"job"`
	Protocol       *string     `json:"protocol,omitzero"`
	TimeoutSeconds *int32      `json:"timeoutSeconds,omitzero"`
	MaxRetries     *int32      `json:"maxRetries,omitzero"`
	RetryStrategy  *string     `json:"retryStrategy,omitzero"`
	LastError      *string     `json:"lastError,omitzero"`
	Attempts       []Attempt   `json:"attempts"`
}

var (
	evType         = httpfw.Query[string]("type").Optional()
	evSubject      = httpfw.Query[string]("subject").Optional()
	evCorrelation  = httpfw.Query[string]("correlationId").Optional()
	actClient      = httpfw.Query[string]("clientId").Optional()
	actSince       = httpfw.Query[string]("since").Optional()
	actUntil       = httpfw.Query[string]("until").Optional()
	jobStatus      = httpfw.Query[string]("status").Optional()
	jobCode        = httpfw.Query[string]("code").Optional()
	jobMsgGroup    = httpfw.Query[string]("messageGroup").Optional()
	activityIDPath = httpfw.Path[string]("id")
)

// ActivityView is the permission that opens the activity section: {app}:admin:activity:view.
func ActivityView(application fw.ApplicationCode) string {
	return application.String() + ":admin:activity:view"
}

// Activity is the activity section (admin contract section 12.2): the application's own events and
// the dispatch jobs that deliver them, with each job's attempts, read-only, behind
// {app}:admin:activity:view. Only this application's records are returned, whatever the platform
// sends: an event whose type does not start with {app}:, or a job whose code's first segment is not
// the application's, is dropped from lists and is a 404 by id (the attempts of a job are asked for
// only after its code has been checked).
type Activity struct {
	application fw.ApplicationCode

	listEvents httpfw.Operation[httpfw.NoBody, admin.Page[Event]]
	getEvent   httpfw.Operation[httpfw.NoBody, EventDetail]
	listJobs   httpfw.Operation[httpfw.NoBody, admin.Page[DispatchJob]]
	getJob     httpfw.Operation[httpfw.NoBody, DispatchJobDetail]
}

// DeclareActivity registers the activity section with the admin registry and declares its
// operations. Call it at declaration time, after [admin.Declare].
func DeclareActivity(adm *admin.Declared) *Activity {
	app := adm.Application()
	web := adm.Web()
	httpfw.NameType[Event](web, "AdminEvent")
	httpfw.NameType[ContextEntry](web, "AdminContextEntry")
	httpfw.NameType[DispatchJob](web, "AdminDispatchJob")
	httpfw.NameType[EventDetail](web, "AdminEventDetail")
	httpfw.NameType[Attempt](web, "AdminAttempt")
	httpfw.NameType[DispatchJobDetail](web, "AdminDispatchJobDetail")
	httpfw.NameType[admin.Page[Event]](web, "PageAdminEvent")
	httpfw.NameType[admin.Page[DispatchJob]](web, "PageAdminDispatchJob")
	view := permission(ActivityView(app))
	a := &Activity{application: app}
	a.listEvents = platformErrors(httpfw.Get[admin.Page[Event]]("/api/admin/events").
		ID("listAdminEvents").Summary("The application's own events, newest first").Tag("admin").
		Query(evType, evSubject, evCorrelation, actClient, actSince, actUntil, limitQuery, afterQuery).Permission(view).
		Error(http.StatusBadRequest, "INVALID_CURSOR", "the cursor is not one this server gave").
		Error(http.StatusBadRequest, "INVALID_TIMESTAMP", "since and until are RFC 3339 times")).Build()
	a.getEvent = platformErrors(httpfw.Get[EventDetail]("/api/admin/events/{id}").
		ID("getAdminEvent").Summary("One event with its payload and the dispatch jobs that deliver it").Tag("admin").
		Path(activityIDPath).Permission(view).
		Error(http.StatusNotFound, "EVENT_NOT_FOUND", "there is no such event of this application")).Build()
	a.listJobs = platformErrors(httpfw.Get[admin.Page[DispatchJob]]("/api/admin/dispatch-jobs").
		ID("listAdminDispatchJobs").Summary("The dispatch jobs of the application's own events, newest first").Tag("admin").
		Query(jobStatus, jobCode, actClient, jobMsgGroup, actSince, actUntil, limitQuery, afterQuery).Permission(view).
		Error(http.StatusBadRequest, "INVALID_CURSOR", "the cursor is not one this server gave").
		Error(http.StatusBadRequest, "INVALID_TIMESTAMP", "since and until are RFC 3339 times").
		Error(http.StatusBadRequest, "INVALID_STATUS", "the status is an upper-case word")).Build()
	a.getJob = platformErrors(httpfw.Get[DispatchJobDetail]("/api/admin/dispatch-jobs/{id}").
		ID("getAdminDispatchJob").Summary("One dispatch job with its delivery attempts").Tag("admin").
		Path(activityIDPath).Permission(view).
		Error(http.StatusNotFound, "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job of this application")).Build()
	adm.Section(admin.NewSection("activity", "Activity", "/admin/activity", ActivityView(app)).
		WithIcon("i-lucide-activity").
		WithDescription("This application's events and the dispatch jobs that deliver them.").
		Serving(a.listEvents, a.getEvent, a.listJobs, a.getJob))
	return a
}

func eventView(e flowcatalyst.EventInfo) Event {
	return Event{
		ID: e.ID, Type: e.Type, Source: e.Source, Subject: optional(e.Subject), Time: e.Time,
		MessageGroup: optional(e.MessageGroup), CorrelationID: optional(e.CorrelationID), ClientID: optional(e.ClientID),
	}
}

func jobView(j flowcatalyst.JobInfo) DispatchJob {
	return DispatchJob{
		ID: j.ID, EventID: optional(j.EventID), SubscriptionID: optional(j.SubscriptionID), ClientID: optional(j.ClientID),
		Code: j.Code, Status: j.Status, Kind: j.Kind, TargetURL: withoutSecrets(j.TargetURL), Mode: j.Mode,
		MessageGroup: optional(j.MessageGroup), Descriptor: optional(j.Descriptor), CreatedAt: j.CreatedAt,
		LastAttemptAt: optional(j.LastAttemptAt), CompletedAt: optional(j.CompletedAt),
		AttemptCount: int32(j.AttemptCount), //nolint:gosec // an attempt count fits
	}
}

func ownsEvent(own string, e flowcatalyst.EventInfo) bool { return strings.HasPrefix(e.Type, own+":") }

func ownsJob(own string, j flowcatalyst.JobInfo) bool {
	first, _, _ := strings.Cut(j.Code, ":")
	return first == own
}

// withoutSecrets is the target URL without user info, query or fragment (they often carry the
// subscriber's credential); the path is kept.
func withoutSecrets(target string) string {
	if i := strings.IndexAny(target, "?#"); i >= 0 {
		target = target[:i]
	}
	if scheme := strings.Index(target, "://"); scheme >= 0 {
		rest := target[scheme+3:]
		at := strings.Index(rest, "@")
		slash := strings.Index(rest, "/")
		if at >= 0 && (slash < 0 || at < slash) {
			target = target[:scheme+3] + rest[at+1:]
		}
	}
	return target
}

// cut shortens text to at most limit UTF-16 code units (as Java counts characters), never inside a
// surrogate pair; the second result says whether anything was cut.
func cut(text string, limit int) (string, bool) {
	units := 0
	for i, r := range text {
		width := 1
		if r >= 0x10000 {
			width = 2
		}
		if units+width > limit {
			return text[:i], true
		}
		units += width
	}
	return text, false
}

// blank is the text of an optional query parameter: "" when absent or blank.
func blank(p httpfw.OptionalQuery[string], in httpfw.Params) string {
	v, _ := p.Value(in)
	if strings.TrimSpace(v) == "" {
		return ""
	}
	return v
}

// rfc3339 checks a since or until time; blank is none.
func rfc3339(p httpfw.OptionalQuery[string], in httpfw.Params) (string, error) {
	v := blank(p, in)
	if v == "" {
		return "", nil
	}
	for _, layout := range []string{time.RFC3339Nano, "2006-01-02T15:04Z07:00"} {
		if _, err := time.Parse(layout, v); err == nil {
			return v, nil
		}
	}
	return "", httpfw.NewError(http.StatusBadRequest, "INVALID_TIMESTAMP", "since and until are RFC 3339 times, such as 2026-10-16T08:00:00Z")
}

func nextOffset(more bool, offset, limit int) *string {
	if !more {
		return nil
	}
	next := strconv.Itoa(offset + limit)
	return &next
}

func (a *Activity) bind(rt *httpfw.Routes, cl caller) error {
	if cl.client.Enabled() {
		app, err := cl.client.Application()
		if err != nil {
			return err
		}
		if app != a.application {
			return fmt.Errorf("the platform activity section is for application '%s' but flowcatalyst.application is '%s'", a.application, app)
		}
	}
	own := a.application.String()

	rt.Bind(httpfw.Handle(a.listEvents, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[Event], error) {
		limit := clampLimit(limitQuery.Value(in.Params))
		after, hasAfter := afterQuery.Value(in.Params)
		offset, err := cursor(after, hasAfter)
		if err != nil {
			return admin.Page[Event]{}, err
		}
		typ := blank(evType, in.Params)
		since, err := rfc3339(actSince, in.Params)
		if err != nil {
			return admin.Page[Event]{}, err
		}
		until, err := rfc3339(actUntil, in.Params)
		if err != nil {
			return admin.Page[Event]{}, err
		}
		if typ != "" && !strings.HasPrefix(typ, own+":") {
			// Another application's type matches nothing here; the platform is not asked.
			return admin.Page[Event]{Items: []Event{}}, nil
		}
		found, err := run(ctx, cl, "", "", func() ([]flowcatalyst.EventInfo, error) {
			return cl.client.Events(ctx, flowcatalyst.EventQuery{
				Type: typ, Subject: blank(evSubject, in.Params), CorrelationID: blank(evCorrelation, in.Params),
				ClientID: blank(actClient, in.Params), Since: since, Until: until, Limit: limit + 1, Offset: offset,
			})
		})
		if err != nil {
			return admin.Page[Event]{}, err
		}
		items := []Event{}
		for _, e := range found[:min(limit, len(found))] {
			if ownsEvent(own, e) {
				items = append(items, eventView(e))
			}
		}
		return admin.Page[Event]{Items: items, NextCursor: nextOffset(len(found) > limit, offset, limit)}, nil
	}))

	rt.Bind(httpfw.Handle(a.getEvent, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (EventDetail, error) {
		id, err := pathID(activityIDPath.Value(in.Params), "EVENT_NOT_FOUND", "there is no such event")
		if err != nil {
			return EventDetail{}, err
		}
		detail, err := run(ctx, cl, "EVENT_NOT_FOUND", "there is no such event", func() (flowcatalyst.EventDetail, error) {
			return cl.client.Event(ctx, id)
		})
		if err != nil {
			return EventDetail{}, err
		}
		if !ownsEvent(own, detail.Event) {
			return EventDetail{}, httpfw.NewError(http.StatusNotFound, "EVENT_NOT_FOUND", "there is no such event")
		}
		jobs, err := run(ctx, cl, "", "", func() ([]flowcatalyst.JobInfo, error) { return cl.client.DispatchJobsOfEvent(ctx, id) })
		if err != nil {
			return EventDetail{}, err
		}
		out := EventDetail{Event: eventView(detail.Event), Context: []ContextEntry{}, DispatchJobs: []DispatchJob{}, CausationID: optional(detail.CausationID)}
		for _, j := range jobs {
			if ownsJob(own, j) {
				out.DispatchJobs = append(out.DispatchJobs, jobView(j))
			}
		}
		for _, c := range detail.Context {
			out.Context = append(out.Context, ContextEntry(c))
		}
		if detail.Data != "" {
			data, truncated := cut(detail.Data, dataLimit)
			out.Data, out.DataTruncated = &data, truncated
		}
		return out, nil
	}))

	rt.Bind(httpfw.Handle(a.listJobs, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[DispatchJob], error) {
		limit := clampLimit(limitQuery.Value(in.Params))
		after, hasAfter := afterQuery.Value(in.Params)
		offset, err := cursor(after, hasAfter)
		if err != nil {
			return admin.Page[DispatchJob]{}, err
		}
		code := blank(jobCode, in.Params)
		status := blank(jobStatus, in.Params)
		if status != "" && !statusPattern.MatchString(status) {
			return admin.Page[DispatchJob]{}, httpfw.NewError(http.StatusBadRequest, "INVALID_STATUS", "the status is an upper-case word, such as COMPLETED")
		}
		since, err := rfc3339(actSince, in.Params)
		if err != nil {
			return admin.Page[DispatchJob]{}, err
		}
		until, err := rfc3339(actUntil, in.Params)
		if err != nil {
			return admin.Page[DispatchJob]{}, err
		}
		if code != "" && !strings.HasPrefix(code, own+":") {
			return admin.Page[DispatchJob]{Items: []DispatchJob{}}, nil
		}
		var statuses []string
		if status != "" {
			statuses = []string{status}
		}
		found, err := run(ctx, cl, "", "", func() ([]flowcatalyst.JobInfo, error) {
			return cl.client.DispatchJobs(ctx, flowcatalyst.JobQuery{
				Statuses: statuses, Code: code, ClientID: blank(actClient, in.Params), MessageGroup: blank(jobMsgGroup, in.Params),
				Since: since, Until: until, Limit: limit + 1, Offset: offset,
			})
		})
		if err != nil {
			return admin.Page[DispatchJob]{}, err
		}
		items := []DispatchJob{}
		for _, j := range found[:min(limit, len(found))] {
			if ownsJob(own, j) {
				items = append(items, jobView(j))
			}
		}
		return admin.Page[DispatchJob]{Items: items, NextCursor: nextOffset(len(found) > limit, offset, limit)}, nil
	}))

	rt.Bind(httpfw.Handle(a.getJob, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (DispatchJobDetail, error) {
		id, err := pathID(activityIDPath.Value(in.Params), "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job")
		if err != nil {
			return DispatchJobDetail{}, err
		}
		found, err := run(ctx, cl, "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job", func() (flowcatalyst.JobInfo, error) {
			return cl.client.DispatchJob(ctx, id)
		})
		if err != nil {
			return DispatchJobDetail{}, err
		}
		if !ownsJob(own, found) {
			// Not even its attempts are asked for.
			return DispatchJobDetail{}, httpfw.NewError(http.StatusNotFound, "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job")
		}
		attempts, err := run(ctx, cl, "DISPATCH_JOB_NOT_FOUND", "there is no such dispatch job", func() ([]flowcatalyst.AttemptInfo, error) {
			return cl.client.DispatchJobAttempts(ctx, id)
		})
		if err != nil {
			return DispatchJobDetail{}, err
		}
		out := DispatchJobDetail{
			Job: jobView(found), Protocol: optional(found.Protocol), RetryStrategy: optional(found.RetryStrategy),
			LastError: optional(found.LastError), Attempts: []Attempt{},
		}
		out.TimeoutSeconds, out.MaxRetries = int32Of(found.TimeoutSeconds), int32Of(found.MaxRetries)
		for _, at := range attempts {
			v := Attempt{
				AttemptNumber: int32(at.AttemptNumber), //nolint:gosec // an attempt number fits
				AttemptedAt:   at.AttemptedAt, CompletedAt: optional(at.CompletedAt), DurationMillis: at.DurationMillis,
				ResponseCode: int32Of(at.ResponseCode), Success: at.Success,
				ErrorMessage: optional(at.ErrorMessage), ErrorType: optional(at.ErrorType),
			}
			if at.ResponseBody != "" {
				body, truncated := cut(at.ResponseBody, bodyLimit)
				v.ResponseBody, v.ResponseBodyTruncated = &body, truncated
			}
			out.Attempts = append(out.Attempts, v)
		}
		return out, nil
	}))
	return nil
}

func int32Of(n *int) *int32 {
	if n == nil {
		return nil
	}
	v := int32(*n) //nolint:gosec // a timeout or a response code fits
	return &v
}
