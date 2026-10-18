package tickets

import (
	"context"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/flowcatalyst/framework/go/clientsettings"
	"github.com/flowcatalyst/framework/go/database"
	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/flowcatalyst/examples/tickets-trial/domain"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/id"
	"github.com/flowcatalyst/framework/go/usecase"
)

// The longest subject, and the longest description, note and comment, which the table's CHECKs repeat.
const (
	SubjectMax = 200
	TextMax    = 4000
)

// The error codes of the use cases (the Java codes, so a client reads the same body from either).
var (
	PrincipalRequired   = usecase.MustErrorCode("PRINCIPAL_REQUIRED")
	Forbidden           = usecase.MustErrorCode("FORBIDDEN")
	NotAService         = usecase.MustErrorCode("NOT_A_SERVICE")
	TicketNotFound      = usecase.MustErrorCode("TICKET_NOT_FOUND")
	SubjectRequired     = usecase.MustErrorCode("SUBJECT_REQUIRED")
	SubjectTooLong      = usecase.MustErrorCode("SUBJECT_TOO_LONG")
	DescriptionTooLong  = usecase.MustErrorCode("DESCRIPTION_TOO_LONG")
	AssigneeRequiredVal = usecase.MustErrorCode("ASSIGNEE_REQUIRED")
	TicketNotAssignable = usecase.MustErrorCode("TICKET_NOT_ASSIGNABLE")
	AlreadyAssigned     = usecase.MustErrorCode("ALREADY_ASSIGNED")
	NoteRequired        = usecase.MustErrorCode("NOTE_REQUIRED")
	NoteTooLong         = usecase.MustErrorCode("NOTE_TOO_LONG")
	IllegalTransition   = usecase.MustErrorCode("ILLEGAL_TRANSITION")
	BodyInvalid         = usecase.MustErrorCode("BODY_INVALID")
	NotYourTicket       = usecase.MustErrorCode("NOT_YOUR_TICKET")
	TicketClosed        = usecase.MustErrorCode("TICKET_CLOSED")
	NotOverdue          = usecase.MustErrorCode("NOT_OVERDUE")
)

func ticketNotFound() *usecase.Error {
	return usecase.NotFound(TicketNotFound, "There is no such ticket.")
}

// Policies are the conflict policies of the use cases that edit an existing record.
type Policies struct {
	Assign, Change, Comment, Flag, Policy usecase.ConflictPolicy
}

// DefaultPolicies are the conflict policies of a deployment: assigning and commenting are safe to repeat on fresh state, so they retry; a
// status change made on a stale view is for the caller to decide again, so it fails (and so does a
// policy edit: the person editing should see what changed). The sweep's flag retries: nobody is
// waiting to decide.
func DefaultPolicies() (Policies, error) {
	retry, err := usecase.Retry(3, 25*time.Millisecond)
	if err != nil {
		return Policies{}, err
	}
	return Policies{Assign: retry, Change: usecase.Fail(), Comment: retry, Flag: retry, Policy: usecase.Fail()}, nil
}

// Deps are what the use cases are made of.
type Deps struct {
	Tickets  Store
	Settings clientsettings.Repository
	Ids      *fw.Ids
	Events   *event.Factory
	Clock    fw.Clock
}

// Service holds the defined use cases. They are the only way data changes, from the HTTP API and
// from the receivers alike, so each checks the permission it needs itself: a receiver holds none and
// runs as the application's service principal instead.
type Service struct {
	OpenTicket    *usecase.Defined[OpenTicketCmd, TicketOpened]
	AssignTicket  *usecase.Defined[AssignTicketCmd, TicketAssigned]
	ChangeStatus  *usecase.Defined[ChangeStatusCmd, TicketStatusChanged]
	AddComment    *usecase.Defined[AddCommentCmd, TicketCommented]
	FlagSLABreach *usecase.Defined[FlagSLABreachCmd, TicketBreached]
	SetSLAPolicy  *usecase.Defined[SetSLAPolicyCmd, SLAPolicyChanged]
}

// NewService defines the six use cases on the runner.
func NewService(r *usecase.Runner, d Deps, p Policies) (*Service, error) {
	open, err := usecase.Define(r, openTicket{d})
	if err != nil {
		return nil, err
	}
	assign, err := usecase.DefineLoaded(r, assignTicket{d}, usecase.WithConflictPolicy(p.Assign))
	if err != nil {
		return nil, err
	}
	change, err := usecase.DefineLoaded(r, changeStatus{d}, usecase.WithConflictPolicy(p.Change))
	if err != nil {
		return nil, err
	}
	comment, err := usecase.DefineLoaded(r, addComment{d}, usecase.WithConflictPolicy(p.Comment))
	if err != nil {
		return nil, err
	}
	flag, err := usecase.DefineLoaded(r, flagSLABreach{d}, usecase.WithConflictPolicy(p.Flag))
	if err != nil {
		return nil, err
	}
	policy, err := usecase.Define(r, setSLAPolicy{d}, usecase.WithConflictPolicy(p.Policy))
	if err != nil {
		return nil, err
	}
	return &Service{OpenTicket: open, AssignTicket: assign, ChangeStatus: change, AddComment: comment, FlagSLABreach: flag, SetSLAPolicy: policy}, nil
}

// ---- authorization: who may run what ---------------------------------------------------------

// requires admits a signed-in person holding the permission.
func requires(cx fw.Context, p fw.Permission) (usecase.Grant, error) {
	if _, ok := cx.Principal(); !ok {
		return usecase.Grant{}, usecase.Authorization(PrincipalRequired, "Sign in to do this.")
	}
	g, err := usecase.Require(cx, p)
	if err != nil {
		return usecase.Grant{}, usecase.Authorization(Forbidden, "You may not do this.")
	}
	return g, nil
}

// serviceOnly admits the application's own service principal: what the platform's receivers run as.
func serviceOnly(cx fw.Context) (usecase.Grant, error) {
	if k, ok := cx.PrincipalKind(); ok && k == fw.PrincipalService {
		return usecase.Public(), nil
	}
	return usecase.Grant{}, usecase.Authorization(NotAService, "Only the application's service does this.")
}

// actor is the acting principal. Every use case that calls it has authorized a signed-in caller first.
func actor(cx fw.Context) string {
	p, _ := cx.Principal()
	return p.String()
}

func parseTicket(raw string) (domain.TicketID, bool) {
	tid, err := id.Parse[domain.TicketKind](raw)
	return tid, err == nil
}

func loadTicket(ctx context.Context, store Store, cx fw.Context, db *database.DB, raw string) (usecase.Loaded[domain.Ticket], bool, error) {
	tid, ok := parseTicket(raw)
	if !ok {
		return usecase.Loaded[domain.Ticket]{}, false, nil
	}
	return store.Find(ctx, db, cx.Client(), tid)
}

// ---- open-ticket -----------------------------------------------------------------------------

type openTicket struct{ Deps }

func (openTicket) Name() usecase.Name { return usecase.MustName("open-ticket") }

func (openTicket) Validate(_ context.Context, _ fw.Context, c OpenTicketCmd) error {
	switch {
	case strings.TrimSpace(c.Subject) == "":
		return usecase.Validation(SubjectRequired, "A subject is required.")
	case utf8.RuneCountInString(c.Subject) > SubjectMax:
		return usecase.Validation(SubjectTooLong, "The subject is too long.")
	case utf8.RuneCountInString(c.Description) > TextMax:
		return usecase.Validation(DescriptionTooLong, "The description is too long.")
	}
	return nil
}

func (openTicket) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[OpenTicketCmd]) (usecase.Grant, error) {
	return requires(cx, OpenTicket)
}

func (u openTicket) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[OpenTicketCmd], db *database.DB) (*usecase.Plan[TicketOpened], error) {
	c := a.Command()
	now := u.Clock.Now()
	// The client's own SLA if it set one, else the default: read once, here.
	current, err := u.Settings.ForClient(ctx, db, cx.Client())
	if err != nil {
		return nil, err
	}
	sla, err := clientsettings.Get(current.Settings(), SLA)
	if err != nil {
		return nil, err
	}
	requester := actor(cx)
	t := domain.NewTicket(id.New[domain.TicketKind](u.Ids), strings.TrimSpace(c.Subject), strings.TrimSpace(c.Description),
		c.Priority, requester, now, now.Add(time.Duration(sla.MinutesFor(c.Priority))*time.Minute))
	ev := TicketOpened{
		Meta: u.Events.Metadata(cx, Opened, t.ID.String()), TicketID: t.ID.String(), Subject: t.Subject,
		Priority: t.Priority, RequesterID: requester, DueAt: t.DueAt,
	}
	return usecase.NewPlan(ev, usecase.Insert(usecase.NewFresh(cx, t), u.Tickets)), nil
}

// ---- assign-ticket ---------------------------------------------------------------------------

type assignTicket struct{ Deps }

func (assignTicket) Name() usecase.Name { return usecase.MustName("assign-ticket") }

func (assignTicket) Validate(_ context.Context, _ fw.Context, c AssignTicketCmd) error {
	if strings.TrimSpace(c.AssigneeID) == "" || utf8.RuneCountInString(c.AssigneeID) > 100 {
		return usecase.Validation(AssigneeRequiredVal, "Name who should work on the ticket.")
	}
	return nil
}

func (assignTicket) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[AssignTicketCmd]) (usecase.Grant, error) {
	return requires(cx, Work)
}

func (u assignTicket) Load(ctx context.Context, cx fw.Context, a usecase.Authorized[AssignTicketCmd], db *database.DB) (usecase.Loaded[domain.Ticket], bool, error) {
	return loadTicket(ctx, u.Tickets, cx, db, a.Command().TicketID)
}

func (assignTicket) WhenMissing() *usecase.Error { return ticketNotFound() }

func (u assignTicket) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[AssignTicketCmd], loaded usecase.Loaded[domain.Ticket], _ *database.DB) (*usecase.Plan[TicketAssigned], error) {
	c, t := a.Command(), loaded.Get()
	if !t.Status.IsActive() {
		return nil, usecase.BusinessRule(TicketNotAssignable, "A resolved or closed ticket cannot be assigned.")
	}
	if c.AssigneeID == t.AssigneeID {
		return nil, usecase.BusinessRule(AlreadyAssigned, "The ticket is already assigned to that person.")
	}
	ev := TicketAssigned{
		Meta: u.Events.Metadata(cx, Assigned, t.ID.String()), TicketID: t.ID.String(), Subject: t.Subject,
		AssigneeID: c.AssigneeID, PreviousAssigneeID: nullable(t.AssigneeID), AssignedBy: actor(cx),
	}
	return usecase.NewPlan(ev, usecase.Update(loaded.With(t.AssignedTo(c.AssigneeID)), u.Tickets)), nil
}

// ---- change-status ---------------------------------------------------------------------------

type changeStatus struct{ Deps }

func (changeStatus) Name() usecase.Name { return usecase.MustName("change-status") }

func (changeStatus) Validate(_ context.Context, _ fw.Context, c ChangeStatusCmd) error {
	needsNote := c.Action == domain.Resolve || c.Action == domain.Reopen
	if needsNote && (c.Note == nil || strings.TrimSpace(*c.Note) == "") {
		if c.Action == domain.Resolve {
			return usecase.Validation(NoteRequired, "Say how it was resolved.")
		}
		return usecase.Validation(NoteRequired, "Say why it is reopened.")
	}
	if c.Note != nil && utf8.RuneCountInString(*c.Note) > TextMax {
		return usecase.Validation(NoteTooLong, "The note is too long.")
	}
	return nil
}

func (changeStatus) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[ChangeStatusCmd]) (usecase.Grant, error) {
	return requires(cx, Work)
}

func (u changeStatus) Load(ctx context.Context, cx fw.Context, a usecase.Authorized[ChangeStatusCmd], db *database.DB) (usecase.Loaded[domain.Ticket], bool, error) {
	return loadTicket(ctx, u.Tickets, cx, db, a.Command().TicketID)
}

func (changeStatus) WhenMissing() *usecase.Error { return ticketNotFound() }

func (u changeStatus) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[ChangeStatusCmd], loaded usecase.Loaded[domain.Ticket], _ *database.DB) (*usecase.Plan[TicketStatusChanged], error) {
	c, t := a.Command(), loaded.Get()
	next, allowed := t.Status.After(c.Action)
	if !allowed {
		return nil, usecase.BusinessRule(IllegalTransition, "A "+string(t.Status)+" ticket cannot be moved by "+string(c.Action)+".").
			WithDetails(map[string]string{"status": string(t.Status), "action": string(c.Action)})
	}
	if c.Action == domain.Start && t.AssigneeID == "" {
		return nil, usecase.BusinessRule(AssigneeRequiredVal, "Assign the ticket before starting work on it.")
	}
	var note *string
	if c.Note != nil {
		stripped := strings.TrimSpace(*c.Note)
		note = &stripped
	}
	noteText := ""
	if note != nil {
		noteText = *note
	}
	updated := t.Moved(next, c.Action, noteText, u.Clock.Now())
	ev := TicketStatusChanged{
		Meta: u.Events.Metadata(cx, StatusChanged, t.ID.String()), TicketID: t.ID.String(), Action: c.Action,
		From: t.Status, To: next, Note: note,
	}
	return usecase.NewPlan(ev, usecase.Update(loaded.With(updated), u.Tickets)), nil
}

// ---- add-comment -----------------------------------------------------------------------------

type addComment struct{ Deps }

func (addComment) Name() usecase.Name { return usecase.MustName("add-comment") }

func (addComment) Validate(_ context.Context, _ fw.Context, c AddCommentCmd) error {
	if strings.TrimSpace(c.Body) == "" || utf8.RuneCountInString(c.Body) > TextMax {
		return usecase.Validation(BodyInvalid, "A comment is 1 to 4000 characters.")
	}
	return nil
}

func (addComment) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[AddCommentCmd]) (usecase.Grant, error) {
	return requires(cx, Comment)
}

func (u addComment) Load(ctx context.Context, cx fw.Context, a usecase.Authorized[AddCommentCmd], db *database.DB) (usecase.Loaded[domain.Ticket], bool, error) {
	return loadTicket(ctx, u.Tickets, cx, db, a.Command().TicketID)
}

func (addComment) WhenMissing() *usecase.Error { return ticketNotFound() }

// AuthorizeLoaded is the record-level rule: a requester may comment only on the tickets they
// opened; whoever works tickets, on any. It needs the ticket, so it runs after the load, before
// Execute (friction F1, solved by the load phase).
func (addComment) AuthorizeLoaded(_ context.Context, cx fw.Context, _ usecase.Authorized[AddCommentCmd], loaded usecase.Loaded[domain.Ticket]) error {
	if cx.HasPermission(Work) || actor(cx) == loaded.Get().RequesterID {
		return nil
	}
	return usecase.Authorization(NotYourTicket, "You may comment only on tickets you opened.")
}

func (u addComment) Execute(_ context.Context, cx fw.Context, a usecase.Authorized[AddCommentCmd], loaded usecase.Loaded[domain.Ticket], _ *database.DB) (*usecase.Plan[TicketCommented], error) {
	t := loaded.Get()
	if t.Status == domain.Closed {
		return nil, usecase.BusinessRule(TicketClosed, "A closed ticket cannot change.")
	}
	author := actor(cx)
	c := domain.Comment{ID: id.New[domain.CommentKind](u.Ids), AuthorID: author, Body: strings.TrimSpace(a.Command().Body), PostedAt: u.Clock.Now()}
	ev := TicketCommented{Meta: u.Events.Metadata(cx, Commented, t.ID.String()), TicketID: t.ID.String(), CommentID: c.ID.String(), AuthorID: author}
	return usecase.NewPlan(ev, usecase.Update(loaded.With(t.WithComment(c)), u.Tickets)), nil
}

// ---- flag-sla-breach -------------------------------------------------------------------------

type flagSLABreach struct{ Deps }

func (flagSLABreach) Name() usecase.Name { return usecase.MustName("flag-sla-breach") }

func (flagSLABreach) Validate(context.Context, fw.Context, FlagSLABreachCmd) error { return nil }

func (flagSLABreach) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[FlagSLABreachCmd]) (usecase.Grant, error) {
	return serviceOnly(cx)
}

func (u flagSLABreach) Load(ctx context.Context, cx fw.Context, a usecase.Authorized[FlagSLABreachCmd], db *database.DB) (usecase.Loaded[domain.Ticket], bool, error) {
	return loadTicket(ctx, u.Tickets, cx, db, a.Command().TicketID)
}

func (flagSLABreach) WhenMissing() *usecase.Error { return ticketNotFound() }

func (u flagSLABreach) Execute(_ context.Context, cx fw.Context, _ usecase.Authorized[FlagSLABreachCmd], loaded usecase.Loaded[domain.Ticket], _ *database.DB) (*usecase.Plan[TicketBreached], error) {
	t, now := loaded.Get(), u.Clock.Now()
	// Idempotent: the sweep may see a ticket twice, and a ticket may have been resolved since the
	// sweep looked.
	if t.BreachedAt != nil || !t.Status.IsActive() || !t.DueAt.Before(now) {
		return nil, usecase.BusinessRule(NotOverdue, "The ticket is not overdue.")
	}
	ev := TicketBreached{
		Meta: u.Events.Metadata(cx, SLABreached, t.ID.String()), TicketID: t.ID.String(), Subject: t.Subject,
		Priority: t.Priority, DueAt: t.DueAt, AssigneeID: nullable(t.AssigneeID),
	}
	return usecase.NewPlan(ev, usecase.Update(loaded.With(t.Breached(now)), u.Tickets)), nil
}

// ---- set-sla-policy --------------------------------------------------------------------------

type setSLAPolicy struct{ Deps }

func (setSLAPolicy) Name() usecase.Name { return usecase.MustName("set-sla-policy") }

func (setSLAPolicy) Validate(_ context.Context, _ fw.Context, c SetSLAPolicyCmd) error {
	if p, bad := c.Minutes.Check(); bad {
		code, err := usecase.ParseErrorCode(p.Code)
		if err != nil {
			return err
		}
		return usecase.Validation(code, p.Message)
	}
	return nil
}

func (setSLAPolicy) Authorize(_ context.Context, cx fw.Context, _ usecase.Valid[SetSLAPolicyCmd]) (usecase.Grant, error) {
	return requires(cx, SLAManage)
}

func (u setSLAPolicy) Execute(ctx context.Context, cx fw.Context, a usecase.Authorized[SetSLAPolicyCmd], db *database.DB) (*usecase.Plan[SLAPolicyChanged], error) {
	m := a.Command().Minutes
	// The client's settings row (or an empty one for a client that never set anything), with the SLA
	// replaced: the framework's aggregate, saved like any other.
	current, err := u.Settings.ForClient(ctx, db, cx.Client())
	if err != nil {
		return nil, err
	}
	saved, err := clientsettings.With(current.Settings(), SLA, m)
	if err != nil {
		return nil, err
	}
	ev := SLAPolicyChanged{
		Meta: u.Events.Metadata(cx, PolicyChanged, saved.ID()), PolicyID: saved.ID(),
		LowMinutes: m.Low, NormalMinutes: m.Normal, HighMinutes: m.High, UrgentMinutes: m.Urgent,
	}
	return usecase.NewPlan(ev, u.Settings.Step(cx, current, saved)), nil
}

// The record-level check is found by an interface assertion at run time, so a signature that drifts
// would silently skip it: this line makes that a compile error (friction G76-3).
var _ usecase.RecordAuthorizer[AddCommentCmd, usecase.Loaded[domain.Ticket]] = addComment{}
