package tickets

import (
	"context"
	"net/http"
	"time"

	"github.com/flowcatalyst/framework/go/clientsettings"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

// ---- wire types ------------------------------------------------------------------------------
//
// The records are the wire types, separate from the aggregate: the OpenAPI document is written from
// them (docs/contracts/openapi.md section 5), and the Java service's web/openapi.json is the document
// for the same API, byte for byte. The order of the fields is the order of the Java record's components.

// CommentView is one comment of a ticket.
type CommentView struct {
	CommentID string    `json:"commentId"`
	AuthorID  string    `json:"authorId"`
	Body      string    `json:"body"`
	PostedAt  time.Time `json:"postedAt"`
}

// TicketView is a ticket with its comments. Overdue is true when the SLA deadline passed while
// somebody still owed an answer.
type TicketView struct {
	ID          string          `json:"id"`
	Subject     string          `json:"subject"`
	Description string          `json:"description"`
	Priority    domain.Priority `json:"priority"`
	Status      domain.Status   `json:"status"`
	Version     int64           `json:"version"`
	RequesterID string          `json:"requesterId"`
	AssigneeID  *string         `json:"assigneeId,omitzero"`
	OpenedAt    time.Time       `json:"openedAt"`
	DueAt       time.Time       `json:"dueAt"`
	Overdue     bool            `json:"overdue"`
	ResolvedAt  *time.Time      `json:"resolvedAt,omitzero"`
	Resolution  *string         `json:"resolution,omitzero"`
	Comments    []CommentView   `json:"comments"`
}

// TicketSummaryView is a ticket in a list.
type TicketSummaryView struct {
	ID          string          `json:"id"`
	Subject     string          `json:"subject"`
	Priority    domain.Priority `json:"priority"`
	Status      domain.Status   `json:"status"`
	RequesterID string          `json:"requesterId"`
	AssigneeID  *string         `json:"assigneeId,omitzero"`
	OpenedAt    time.Time       `json:"openedAt"`
	DueAt       time.Time       `json:"dueAt"`
	Overdue     bool            `json:"overdue"`
	Version     int64           `json:"version"`
}

// isOverdue: flagged by the sweep, or past the deadline and still waiting for an answer.
func isOverdue(status domain.Status, dueAt time.Time, breachedAt *time.Time, now time.Time) bool {
	return breachedAt != nil || (status.IsActive() && dueAt.Before(now))
}

func viewOf(t domain.Ticket, version int64, now time.Time) TicketView {
	v := TicketView{
		ID: t.ID.String(), Subject: t.Subject, Description: t.Description, Priority: t.Priority, Status: t.Status,
		Version: version, RequesterID: t.RequesterID, AssigneeID: nullable(t.AssigneeID), OpenedAt: t.OpenedAt, DueAt: t.DueAt,
		Overdue: isOverdue(t.Status, t.DueAt, t.BreachedAt, now), ResolvedAt: t.ResolvedAt, Resolution: nullable(t.Resolution),
		Comments: make([]CommentView, 0, len(t.Comments)),
	}
	for _, c := range t.Comments {
		v.Comments = append(v.Comments, CommentView{CommentID: c.ID.String(), AuthorID: c.AuthorID, Body: c.Body, PostedAt: c.PostedAt})
	}
	return v
}

// Page is one page of a list: NextCursor goes in as "after" for the next page and is absent on the
// last one.
type Page[T any] struct {
	Items      []T     `json:"items"`
	NextCursor *string `json:"nextCursor,omitzero"`
}

// OpenTicketBody opens a ticket.
type OpenTicketBody struct {
	Subject     string          `json:"subject"`
	Description string          `json:"description"`
	Priority    domain.Priority `json:"priority"`
}

// AssignBody gives a ticket to someone.
type AssignBody struct {
	AssigneeID string `json:"assigneeId"`
}

// TransitionBody moves a ticket along its status machine: Note is the resolution (for resolve) or
// the reason (for reopen).
type TransitionBody struct {
	Action domain.Action `json:"action"`
	Note   *string       `json:"note,omitzero"`
}

// CommentBody comments on a ticket.
type CommentBody struct {
	Body string `json:"body"`
}

// SlaPolicyView is a client's SLA policy; Customised is false while the client has set none and these
// are the defaults, and Version is 0 then.
type SlaPolicyView struct {
	LowMinutes    int32 `json:"lowMinutes"`
	NormalMinutes int32 `json:"normalMinutes"`
	HighMinutes   int32 `json:"highMinutes"`
	UrgentMinutes int32 `json:"urgentMinutes"`
	Customised    bool  `json:"customised"`
	Version       int64 `json:"version"`
}

// SetSlaPolicyBody sets the client's SLA policy.
type SetSlaPolicyBody struct {
	LowMinutes    int32 `json:"lowMinutes"`
	NormalMinutes int32 `json:"normalMinutes"`
	HighMinutes   int32 `json:"highMinutes"`
	UrgentMinutes int32 `json:"urgentMinutes"`
}

var (
	openTicketRules = validate.Of(
		validate.Field("subject", func(b OpenTicketBody) string { return b.Subject }, validate.NotBlank(), validate.MaxLength(SubjectMax)),
		validate.Field("description", func(b OpenTicketBody) string { return b.Description }, validate.MaxLength(TextMax)),
		validate.Field("priority", func(b OpenTicketBody) domain.Priority { return b.Priority },
			validate.OneOf(domain.Low, domain.Normal, domain.High, domain.Urgent)))
	assignRules = validate.Of(
		validate.Field("assigneeId", func(b AssignBody) string { return b.AssigneeID }, validate.NotBlank(), validate.MaxLength(100)))
	transitionRules = validate.Of(
		validate.Field("action", func(b TransitionBody) domain.Action { return b.Action },
			validate.OneOf(domain.Start, domain.Resolve, domain.Reopen, domain.Close)),
		validate.Field("note", func(b TransitionBody) *string { return b.Note }, validate.Ptr(validate.MaxLength(TextMax))))
	commentRules = validate.Of(
		validate.Field("body", func(b CommentBody) string { return b.Body }, validate.NotBlank(), validate.MaxLength(TextMax)))
	setSLARules = validate.Of(
		validate.Field("lowMinutes", func(b SetSlaPolicyBody) int32 { return b.LowMinutes }, validate.Range[int32](1, MinutesMax)),
		validate.Field("normalMinutes", func(b SetSlaPolicyBody) int32 { return b.NormalMinutes }, validate.Range[int32](1, MinutesMax)),
		validate.Field("highMinutes", func(b SetSlaPolicyBody) int32 { return b.HighMinutes }, validate.Range[int32](1, MinutesMax)),
		validate.Field("urgentMinutes", func(b SetSlaPolicyBody) int32 { return b.UrgentMinutes }, validate.Range[int32](1, MinutesMax)))
)

// ---- operations ------------------------------------------------------------------------------

var (
	ticketIDParam = httpfw.Path[string]("ticketId")
	statusParam   = httpfw.Query[domain.Status]("status").Optional()
	assigneeParam = httpfw.Query[string]("assignee").Optional()
	requesterPar  = httpfw.Query[string]("requester").Optional()
	limitParam    = httpfw.Query[int32]("limit").Default(50)
	afterParam    = httpfw.Query[string]("after").Optional()
)

// The tickets API as typed operations: values written once, declared by [DeclareAPI] and bound by
// [API.Bind].
var (
	// ListOp is GET /api/tickets.
	ListOp = httpfw.Get[Page[TicketSummaryView]]("/api/tickets").
		ID("listTickets").Summary("List the client's tickets, newest first").Tag("tickets").
		Query(statusParam, assigneeParam, requesterPar, limitParam, afterParam).Permission(Read).Build()

	// GetOp is GET /api/tickets/{ticketId}.
	GetOp = httpfw.Get[TicketView]("/api/tickets/{ticketId}").
		ID("getTicket").Summary("Read a ticket with its comments").Tag("tickets").
		Path(ticketIDParam).Permission(Read).
		Error(404, "TICKET_NOT_FOUND", "there is no such ticket").Build()

	// OpenOp is POST /api/tickets.
	OpenOp = httpfw.Post[OpenTicketBody, TicketView]("/api/tickets", openTicketRules).
		ID("openTicket").Summary("Open a ticket").Tag("tickets").
		Status(http.StatusCreated).Permission(OpenTicket).Build()

	// AssignOp is POST /api/tickets/{ticketId}/assign.
	AssignOp = httpfw.Post[AssignBody, TicketView]("/api/tickets/{ticketId}/assign", assignRules).
			ID("assignTicket").Summary("Give a ticket to someone").Tag("tickets").
			Path(ticketIDParam).Permission(Work).
			Error(404, "TICKET_NOT_FOUND", "there is no such ticket").
			Error(409, "TICKET_NOT_ASSIGNABLE", "a resolved or closed ticket cannot be assigned").
			Error(409, "ALREADY_ASSIGNED", "the ticket is already assigned to that person").
			Error(409, "STALE_AGGREGATE", "the ticket was changed by someone else; reload it and try again").Build()

	// TransitionOp is POST /api/tickets/{ticketId}/transition.
	TransitionOp = httpfw.Post[TransitionBody, TicketView]("/api/tickets/{ticketId}/transition", transitionRules).
			ID("transitionTicket").Summary("Move a ticket along its status machine").Tag("tickets").
			Path(ticketIDParam).Permission(Work).
			Error(404, "TICKET_NOT_FOUND", "there is no such ticket").
			Error(409, "ILLEGAL_TRANSITION", "the action is not allowed from the ticket's status").
			Error(409, "ASSIGNEE_REQUIRED", "a ticket must be assigned before work starts on it").
			Error(409, "STALE_AGGREGATE", "the ticket was changed by someone else; reload it and try again").Build()

	// CommentOp is POST /api/tickets/{ticketId}/comments.
	CommentOp = httpfw.Post[CommentBody, TicketView]("/api/tickets/{ticketId}/comments", commentRules).
			ID("commentOnTicket").Summary("Comment on a ticket").Tag("tickets").
			Path(ticketIDParam).Permission(Comment).
			Error(403, "NOT_YOUR_TICKET", "a requester may comment only on tickets they opened").
			Error(404, "TICKET_NOT_FOUND", "there is no such ticket").
			Error(409, "TICKET_CLOSED", "a closed ticket cannot change").
			Error(429, "RATE_LIMITED", "too many comments from one person; wait and try again").Build()

	// GetSLAOp is GET /api/sla-policy.
	GetSLAOp = httpfw.Get[SlaPolicyView]("/api/sla-policy").
			ID("getSlaPolicy").Summary("Read the client's SLA policy (the defaults until it sets one)").Tag("sla").
			Permission(Read).Build()

	// SetSLAOp is PUT /api/sla-policy.
	SetSLAOp = httpfw.Put[SetSlaPolicyBody, SlaPolicyView]("/api/sla-policy", setSLARules).
			ID("setSlaPolicy").Summary("Set the client's SLA policy; tickets opened afterwards use it").Tag("sla").
			Permission(SLAManage).
			Error(409, "STALE_AGGREGATE", "the policy was changed by someone else; reload it and try again").Build()
)

// DeclareAPI declares the tickets API for the OpenAPI document: at declaration time, so
// `tickets-trial openapi` needs no database or configuration.
func DeclareAPI(web *httpfw.Declared) {
	web.Info("tickets-trial", "1")
	web.Operations(ListOp, GetOp, OpenOp, AssignOp, TransitionOp, CommentOp, GetSLAOp, SetSLAOp)
	httpfw.NameType[Overview](web, "TicketsOverview")
}

// ---- handlers --------------------------------------------------------------------------------

// API serves the tickets operations: the use cases decide, a failed one is answered with its
// *usecase.Error.
type API struct {
	Service *Service
	DB      *database.DB
	Store   Store
	Clock   fw.Clock
	// CommentLimit is how often one person may comment.
	CommentLimit ratelimit.Policy
}

// Bind registers the eight operations. The comment limiter is built here, in code, with its policy as
// an argument: a limiter is a thing an application can have many of (config-and-code ruling), so it
// is not a setting.
func (a *API) Bind(rt *httpfw.Routes) error {
	limiter, err := ratelimit.NewMemory("ticket-comments", a.CommentLimit)
	if err != nil {
		return err
	}
	perPerson, err := ratelimit.Middleware(limiter, ratelimit.Principal())
	if err != nil {
		return err
	}
	rt.Bind(httpfw.Handle(ListOp, a.list))
	rt.Bind(httpfw.Handle(GetOp, a.get))
	rt.Bind(httpfw.Handle(OpenOp, a.open))
	rt.Bind(httpfw.Handle(AssignOp, a.assign))
	rt.Bind(httpfw.Handle(TransitionOp, a.transition))
	rt.Bind(httpfw.Handle(CommentOp, a.comment, httpfw.With(perPerson)))
	rt.Bind(httpfw.Handle(GetSLAOp, a.getSLA))
	rt.Bind(httpfw.Handle(SetSLAOp, a.setSLA))
	return nil
}

func (a *API) list(ctx context.Context, cx fw.Context, in httpfw.In[httpfw.NoBody]) (Page[TicketSummaryView], error) {
	var f Filter
	if s, ok := statusParam.Value(in.Params); ok {
		f.Status = s
	}
	f.Assignee, _ = assigneeParam.Value(in.Params)
	f.Requester, _ = requesterPar.Value(in.Params)
	f.After, _ = afterParam.Value(in.Params)
	page, err := List(ctx, a.DB, cx.Client(), f, int(limitParam.Value(in.Params)))
	if err != nil {
		return Page[TicketSummaryView]{}, err
	}
	now := a.Clock.Now()
	out := Page[TicketSummaryView]{Items: make([]TicketSummaryView, 0, len(page.Items))}
	for _, s := range page.Items {
		out.Items = append(out.Items, TicketSummaryView{
			ID: s.ID.String(), Subject: s.Subject, Priority: s.Priority, Status: s.Status, RequesterID: s.RequesterID,
			AssigneeID: nullable(s.AssigneeID), OpenedAt: s.OpenedAt, DueAt: s.DueAt,
			Overdue: isOverdue(s.Status, s.DueAt, s.BreachedAt, now), Version: s.Version,
		})
	}
	if page.NextCursor != "" {
		next := page.NextCursor
		out.NextCursor = &next
	}
	return out, nil
}

func (a *API) get(ctx context.Context, cx fw.Context, in httpfw.In[httpfw.NoBody]) (TicketView, error) {
	return a.view(ctx, cx, ticketIDParam.Value(in.Params))
}

func (a *API) open(ctx context.Context, cx fw.Context, in httpfw.In[OpenTicketBody]) (TicketView, error) {
	done, err := a.Service.OpenTicket.Run(ctx, cx, OpenTicketCmd{Subject: in.Body.Subject, Description: in.Body.Description, Priority: in.Body.Priority})
	if err != nil {
		return TicketView{}, err
	}
	view, err := a.view(ctx, cx, done.Event().TicketID)
	if err == nil {
		in.Header.Add("Location", "/api/tickets/"+view.ID)
	}
	return view, err
}

func (a *API) assign(ctx context.Context, cx fw.Context, in httpfw.In[AssignBody]) (TicketView, error) {
	raw := ticketIDParam.Value(in.Params)
	if _, err := a.Service.AssignTicket.Run(ctx, cx, AssignTicketCmd{TicketID: raw, AssigneeID: in.Body.AssigneeID}); err != nil {
		return TicketView{}, err
	}
	return a.view(ctx, cx, raw)
}

func (a *API) transition(ctx context.Context, cx fw.Context, in httpfw.In[TransitionBody]) (TicketView, error) {
	raw := ticketIDParam.Value(in.Params)
	if _, err := a.Service.ChangeStatus.Run(ctx, cx, ChangeStatusCmd{TicketID: raw, Action: in.Body.Action, Note: in.Body.Note}); err != nil {
		return TicketView{}, err
	}
	return a.view(ctx, cx, raw)
}

func (a *API) comment(ctx context.Context, cx fw.Context, in httpfw.In[CommentBody]) (TicketView, error) {
	raw := ticketIDParam.Value(in.Params)
	if _, err := a.Service.AddComment.Run(ctx, cx, AddCommentCmd{TicketID: raw, Body: in.Body.Body}); err != nil {
		return TicketView{}, err
	}
	return a.view(ctx, cx, raw)
}

func (a *API) getSLA(ctx context.Context, cx fw.Context, _ httpfw.In[httpfw.NoBody]) (SlaPolicyView, error) {
	return a.slaView(ctx, cx)
}

func (a *API) setSLA(ctx context.Context, cx fw.Context, in httpfw.In[SetSlaPolicyBody]) (SlaPolicyView, error) {
	b := in.Body
	if _, err := a.Service.SetSLAPolicy.Run(ctx, cx, SetSLAPolicyCmd{Minutes: SlaMinutes{Low: b.LowMinutes, Normal: b.NormalMinutes, High: b.HighMinutes, Urgent: b.UrgentMinutes}}); err != nil {
		return SlaPolicyView{}, err
	}
	return a.slaView(ctx, cx)
}

func (a *API) slaView(ctx context.Context, cx fw.Context) (SlaPolicyView, error) {
	current, err := clientsettings.Repository{}.ForClient(ctx, a.DB, cx.Client())
	if err != nil {
		return SlaPolicyView{}, err
	}
	sla, err := clientsettings.Get(current.Settings(), SLA)
	if err != nil {
		return SlaPolicyView{}, err
	}
	return SlaPolicyView{
		LowMinutes: sla.Low, NormalMinutes: sla.Normal, HighMinutes: sla.High, UrgentMinutes: sla.Urgent,
		Customised: clientsettings.IsSet(current.Settings(), SLA), Version: current.Version(),
	}, nil
}

// view reads a ticket of the caller's client as the wire type; there is none for another client's
// ticket or a malformed id.
func (a *API) view(ctx context.Context, cx fw.Context, raw string) (TicketView, error) {
	tid, err := id.Parse[domain.TicketKind](raw)
	if err != nil {
		return TicketView{}, ticketNotFound()
	}
	loaded, found, err := a.Store.Find(ctx, a.DB, cx.Client(), tid)
	if err != nil {
		return TicketView{}, err
	}
	if !found {
		return TicketView{}, ticketNotFound()
	}
	return viewOf(loaded.Get(), loaded.Version(), a.Clock.Now()), nil
}
