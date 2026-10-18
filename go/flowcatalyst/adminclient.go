package flowcatalyst

import (
	"context"
	"strconv"
	"strings"
)

// The reads and writes behind the admin pages (admin contract sections 11 and 12). They go over plain
// HTTP with the service account's token, like the Java client's interim path (the module's fcsdk
// version has no methods for the application-user and scoped-read routes). Every method is for THIS
// application: the application code in a path is always the configured one. A disabled client fails
// every method with [ErrDisabled].

func (c *PlatformClient) admin() (*platformHTTP, error) {
	if !c.enabled {
		return nil, ErrDisabled
	}
	return c.http, nil
}

func (c *PlatformClient) appPath() string { return "/api/applications/" + pathSegment(c.app.String()) }

// EventTypes lists the application's event types: GET /api/event-types. Empty filters are not sent.
func (c *PlatformClient) EventTypes(ctx context.Context, status, subdomain, aggregate string) ([]EventTypeInfo, error) {
	h, err := c.admin()
	if err != nil {
		return nil, err
	}
	q := query{}.add("application", c.app.String()).add("subdomain", subdomain).add("aggregate", aggregate).add("status", status)
	raw, err := h.exchange(ctx, "GET", "/api/event-types", q, nil, plainKind)
	if err != nil {
		return nil, err
	}
	return parseEventTypes(raw)
}

// EventType reads one event type with its specification versions: GET /api/event-types/{id}.
func (c *PlatformClient) EventType(ctx context.Context, id string) (EventTypeInfo, error) {
	h, err := c.admin()
	if err != nil {
		return EventTypeInfo{}, err
	}
	raw, err := h.read(ctx, "/api/event-types/"+pathSegment(id))
	if err != nil {
		return EventTypeInfo{}, err
	}
	return parseEventType(raw)
}

// Roles lists roles: GET /api/roles. The platform answers with every role it holds, not only this
// application's; callers keep their own.
func (c *PlatformClient) Roles(ctx context.Context) ([]RoleInfo, error) {
	h, err := c.admin()
	if err != nil {
		return nil, err
	}
	raw, err := h.read(ctx, "/api/roles")
	if err != nil {
		return nil, err
	}
	return parseRoles(raw)
}

// Role reads one role by its full name, {application}:{role}: GET /api/roles/{name}.
func (c *PlatformClient) Role(ctx context.Context, name string) (RoleInfo, error) {
	h, err := c.admin()
	if err != nil {
		return RoleInfo{}, err
	}
	raw, err := h.read(ctx, "/api/roles/"+pathSegment(name))
	if err != nil {
		return RoleInfo{}, err
	}
	return parseRole(raw)
}

// Users lists the application's users: GET /api/applications/{app}/users.
func (c *PlatformClient) Users(ctx context.Context, qy UserQuery) (UserPage, error) {
	h, err := c.admin()
	if err != nil {
		return UserPage{}, err
	}
	q := query{}.add("q", qy.Q).add("active", qy.Active).add("clientId", qy.ClientID).add("roles", strings.Join(qy.Roles, ","))
	q = q.add("page", strconv.Itoa(qy.Page)).add("pageSize", strconv.Itoa(qy.PageSize)).add("sortField", qy.SortField).add("sortOrder", qy.SortOrder)
	raw, err := h.call(ctx, "GET", c.appPath()+"/users", q, nil)
	if err != nil {
		return UserPage{}, err
	}
	return parseUsers(raw)
}

// User reads one of the application's users: GET /api/applications/{app}/users/{id}. A platform
// that has no such user of the application answers [KindNotFound].
func (c *PlatformClient) User(ctx context.Context, id string) (UserInfo, error) {
	h, err := c.admin()
	if err != nil {
		return UserInfo{}, err
	}
	raw, err := h.call(ctx, "GET", c.appPath()+"/users/"+pathSegment(id), nil, nil)
	if err != nil {
		return UserInfo{}, err
	}
	return parseUser(raw)
}

type inviteBody struct {
	Email            string   `json:"email"`
	Name             string   `json:"name"`
	ClientID         string   `json:"clientId"`
	Roles            []string `json:"roles"`
	SendInvitation   bool     `json:"sendInvitation"`
	ReturnInviteLink bool     `json:"returnInviteLink"`
}

// Invite invites a user holding the application's roles: POST /api/applications/{app}/users. The
// result may hold the invitation link: hand it to the person who asked and to nobody else. A refusal
// the platform explained (EMAIL_EXISTS, ROLE_APP_FORBIDDEN, ...) is a [KindRejected] error carrying
// its code and message.
func (c *PlatformClient) Invite(ctx context.Context, req InviteRequest) (InviteResult, error) {
	h, err := c.admin()
	if err != nil {
		return InviteResult{}, err
	}
	raw, err := h.call(ctx, "POST", c.appPath()+"/users", nil, inviteBody(req))
	if err != nil {
		return InviteResult{}, err
	}
	return parseInvite(raw)
}

// Invitations lists the application's outstanding invitations: GET /api/applications/{app}/invitations.
// status is PENDING, EXPIRED or empty for both. The platform never sends a link here.
func (c *PlatformClient) Invitations(ctx context.Context, status string, page, pageSize int) (InvitationPage, error) {
	h, err := c.admin()
	if err != nil {
		return InvitationPage{}, err
	}
	q := query{}.add("status", status).add("page", strconv.Itoa(page)).add("pageSize", strconv.Itoa(pageSize))
	raw, err := h.call(ctx, "GET", c.appPath()+"/invitations", q, nil)
	if err != nil {
		return InvitationPage{}, err
	}
	return parseInvitations(raw)
}

type resendBody struct {
	SendInvitation   bool `json:"sendInvitation"`
	ReturnInviteLink bool `json:"returnInviteLink"`
}

// ResendInvitation sends an invitation again: POST /api/applications/{app}/invitations/{id}/resend.
// The earlier link stops working. The result may hold the link: hand it to the person who asked and
// to nobody else.
func (c *PlatformClient) ResendInvitation(ctx context.Context, userID string, sendInvitation, returnInviteLink bool) (ResendResult, error) {
	h, err := c.admin()
	if err != nil {
		return ResendResult{}, err
	}
	raw, err := h.call(ctx, "POST", c.appPath()+"/invitations/"+pathSegment(userID)+"/resend", nil,
		resendBody{SendInvitation: sendInvitation, ReturnInviteLink: returnInviteLink})
	if err != nil {
		return ResendResult{}, err
	}
	return parseResend(raw)
}

// RevokeInvitation revokes an invitation: DELETE /api/applications/{app}/invitations/{id}. The
// platform deactivates the user and deletes the invitation links.
func (c *PlatformClient) RevokeInvitation(ctx context.Context, userID string) error {
	h, err := c.admin()
	if err != nil {
		return err
	}
	_, err = h.call(ctx, "DELETE", c.appPath()+"/invitations/"+pathSegment(userID), nil, nil)
	return err
}

// Events lists events, newest first: GET /api/events. The platform confines an application service
// account to its own application's events; applications= is sent as well.
func (c *PlatformClient) Events(ctx context.Context, qy EventQuery) ([]EventInfo, error) {
	h, err := c.admin()
	if err != nil {
		return nil, err
	}
	q := query{}.add("applications", c.app.String()).add("type", qy.Type).add("subject", qy.Subject).add("correlationId", qy.CorrelationID)
	q = q.add("clientId", qy.ClientID).add("since", qy.Since).add("until", qy.Until)
	q = q.add("limit", strconv.Itoa(qy.Limit)).add("offset", strconv.Itoa(qy.Offset))
	raw, err := h.call(ctx, "GET", "/api/events", q, nil)
	if err != nil {
		return nil, err
	}
	return parseEvents(raw)
}

// Event reads one event with its payload: GET /api/events/{id}.
func (c *PlatformClient) Event(ctx context.Context, id string) (EventDetail, error) {
	h, err := c.admin()
	if err != nil {
		return EventDetail{}, err
	}
	raw, err := h.call(ctx, "GET", "/api/events/"+pathSegment(id), nil, nil)
	if err != nil {
		return EventDetail{}, err
	}
	return parseEventDetail(raw)
}

// DispatchJobs lists dispatch jobs, newest first: GET /api/dispatch-jobs.
func (c *PlatformClient) DispatchJobs(ctx context.Context, qy JobQuery) ([]JobInfo, error) {
	h, err := c.admin()
	if err != nil {
		return nil, err
	}
	q := query{}.add("applications", c.app.String()).add("statuses", strings.Join(qy.Statuses, ",")).add("code", qy.Code)
	q = q.add("clientId", qy.ClientID).add("messageGroup", qy.MessageGroup).add("since", qy.Since).add("until", qy.Until)
	q = q.add("limit", strconv.Itoa(qy.Limit)).add("offset", strconv.Itoa(qy.Offset))
	raw, err := h.call(ctx, "GET", "/api/dispatch-jobs", q, nil)
	if err != nil {
		return nil, err
	}
	return parseJobs(raw)
}

// DispatchJob reads one dispatch job: GET /api/dispatch-jobs/{id}.
func (c *PlatformClient) DispatchJob(ctx context.Context, id string) (JobInfo, error) {
	h, err := c.admin()
	if err != nil {
		return JobInfo{}, err
	}
	raw, err := h.call(ctx, "GET", "/api/dispatch-jobs/"+pathSegment(id), nil, nil)
	if err != nil {
		return JobInfo{}, err
	}
	return parseJob(raw)
}

// DispatchJobAttempts reads a dispatch job's delivery attempts: GET /api/dispatch-jobs/{id}/attempts.
func (c *PlatformClient) DispatchJobAttempts(ctx context.Context, id string) ([]AttemptInfo, error) {
	h, err := c.admin()
	if err != nil {
		return nil, err
	}
	raw, err := h.call(ctx, "GET", "/api/dispatch-jobs/"+pathSegment(id)+"/attempts", nil, nil)
	if err != nil {
		return nil, err
	}
	return parseAttempts(raw)
}

// DispatchJobsOfEvent lists the dispatch jobs an event raised: GET /api/dispatch-jobs/event/{eventId}.
func (c *PlatformClient) DispatchJobsOfEvent(ctx context.Context, eventID string) ([]JobInfo, error) {
	h, err := c.admin()
	if err != nil {
		return nil, err
	}
	raw, err := h.call(ctx, "GET", "/api/dispatch-jobs/event/"+pathSegment(eventID), nil, nil)
	if err != nil {
		return nil, err
	}
	return parseJobs(raw)
}
