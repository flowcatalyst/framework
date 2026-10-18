package platformadmin

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/httpfw/validate"
)

// InvitationStatus says whether an invitation can still be used.
type InvitationStatus string

// The invitation statuses.
const (
	// InvitationPending means a live link exists.
	InvitationPending InvitationStatus = "PENDING"
	// InvitationExpired means the link lapsed: resend it.
	InvitationExpired InvitationStatus = "EXPIRED"
)

// EnumValues implements openapi.EnumValuer.
func (InvitationStatus) EnumValues() []string { return []string{"PENDING", "EXPIRED"} }

// User is one of the application's users.
type User struct {
	ID          string   `json:"id"`
	Email       string   `json:"email"`
	Name        string   `json:"name"`
	Active      bool     `json:"active"`
	ClientID    string   `json:"clientId"`
	IdpType     string   `json:"idpType"`
	Roles       []string `json:"roles"`
	Invitation  *string  `json:"invitation,omitzero"`
	LastLoginAt *string  `json:"lastLoginAt,omitzero"`
	CreatedAt   string   `json:"createdAt"`
}

// Invitation is an outstanding invitation. There is no link here: it is shown once, to the person who
// asked.
type Invitation struct {
	UserID    string           `json:"userId"`
	Email     string           `json:"email"`
	Name      string           `json:"name"`
	ClientID  string           `json:"clientId"`
	Roles     []string         `json:"roles"`
	Status    InvitationStatus `json:"status"`
	CreatedAt string           `json:"createdAt"`
	ExpiresAt *string          `json:"expiresAt,omitzero"`
}

// InviteBody is an invitation to send. ClientID is an id or the client's identifier; SendInvitation
// defaults to true and ReturnInviteLink to false.
type InviteBody struct {
	Email            string   `json:"email"`
	Name             string   `json:"name"`
	ClientID         string   `json:"clientId"`
	Roles            []string `json:"roles"`
	SendInvitation   *bool    `json:"sendInvitation,omitzero"`
	ReturnInviteLink *bool    `json:"returnInviteLink,omitzero"`
}

// InviteResult is the answer to an invitation. InviteLink is a credential: it is in the answer only
// when asked for, the answer is not cached, and it is shown once and not stored or logged.
type InviteResult struct {
	ID             string  `json:"id"`
	InvitationSent bool    `json:"invitationSent"`
	InviteLink     *string `json:"inviteLink,omitzero"`
}

// String withholds the link.
func (r InviteResult) String() string {
	link := "none"
	if r.InviteLink != nil {
		link = "withheld"
	}
	return fmt.Sprintf("InviteResult[id=%s, invitationSent=%v, inviteLink=%s]", r.ID, r.InvitationSent, link)
}

// ResendBody is a resend; ReturnInviteLink defaults to false.
type ResendBody struct {
	ReturnInviteLink *bool `json:"returnInviteLink,omitzero"`
}

// ResendResult is the answer to a resend. InviteLink is a credential, as in [InviteResult].
type ResendResult struct {
	ExpiresAt  *string `json:"expiresAt,omitzero"`
	InviteLink *string `json:"inviteLink,omitzero"`
}

// String withholds the link.
func (r ResendResult) String() string {
	link := "none"
	if r.InviteLink != nil {
		link = "withheld"
	}
	expires := ""
	if r.ExpiresAt != nil {
		expires = *r.ExpiresAt
	}
	return "ResendResult[expiresAt=" + expires + ", inviteLink=" + link + "]"
}

var (
	inviteRules = validate.Of(
		validate.Field("email", func(b InviteBody) string { return b.Email },
			validate.NotBlank(), validate.MaxLength(254), validate.Pattern(`[^@\s]+@[^@\s]+\.[^@\s]+`)),
		validate.Field("name", func(b InviteBody) string { return b.Name }, validate.NotBlank(), validate.MaxLength(200)),
		validate.Field("clientId", func(b InviteBody) string { return b.ClientID }, validate.NotBlank(), validate.MaxLength(100)),
		validate.Field("roles", func(b InviteBody) []string { return b.Roles },
			validate.Size[string](1, 50), validate.Each(validate.NotBlank()), validate.Each(validate.MaxLength(100))))
	resendRules = validate.None[ResendBody]()

	usersActive   = httpfw.Query[bool]("active").Optional()
	usersClient   = httpfw.Query[string]("clientId").Optional()
	usersRoles    = httpfw.Query[string]("roles").Optional()
	invitesStatus = httpfw.Query[InvitationStatus]("status").Optional()
)

// Users is the users section (admin contract section 12.1): the users who hold this application's
// roles, their pending invitations, and inviting, resending and revoking, over the platform's
// application-user routes, called as the application's own service account after the framework has
// checked the signed-in user's permission.
//
// Permissions: {app}:admin:users:view (users, invitations), :invite, :resend, :revoke. The application
// code in every platform path is the configured one; ids that could change a path are a 404 without a
// call; roles of another application are refused here too. An invitation link is a credential: it is
// in an answer only when the caller asked for it, with Cache-Control: no-store, and in no log and no
// list.
type Users struct {
	application fw.ApplicationCode

	listUsers       httpfw.Operation[httpfw.NoBody, admin.Page[User]]
	getUser         httpfw.Operation[httpfw.NoBody, User]
	invite          httpfw.Operation[InviteBody, InviteResult]
	listInvitations httpfw.Operation[httpfw.NoBody, admin.Page[Invitation]]
	resend          httpfw.Operation[ResendBody, ResendResult]
	revoke          httpfw.Operation[httpfw.NoBody, httpfw.NoBody]
}

// UsersView is the permission that opens the users section and reads users and invitations.
func UsersView(application fw.ApplicationCode) string {
	return application.String() + ":admin:users:view"
}

// UsersInvite is the permission to invite a user.
func UsersInvite(application fw.ApplicationCode) string {
	return application.String() + ":admin:users:invite"
}

// UsersResend is the permission to resend an invitation.
func UsersResend(application fw.ApplicationCode) string {
	return application.String() + ":admin:users:resend"
}

// UsersRevoke is the permission to revoke an invitation.
func UsersRevoke(application fw.ApplicationCode) string {
	return application.String() + ":admin:users:revoke"
}

// DeclareUsers registers the users section with the admin registry and declares its operations. Call
// it at declaration time, after [admin.Declare].
func DeclareUsers(adm *admin.Declared) *Users {
	app := adm.Application()
	web := adm.Web()
	httpfw.NameType[User](web, "AdminUser")
	httpfw.NameType[Invitation](web, "AdminInvitation")
	httpfw.NameType[InviteBody](web, "InviteBody")
	httpfw.NameType[InviteResult](web, "InviteResult")
	httpfw.NameType[ResendBody](web, "ResendBody")
	httpfw.NameType[ResendResult](web, "ResendResult")
	httpfw.NameType[admin.Page[User]](web, "PageAdminUser")
	httpfw.NameType[admin.Page[Invitation]](web, "PageAdminInvitation")
	view, invite, resend, revoke := permission(UsersView(app)), permission(UsersInvite(app)), permission(UsersResend(app)), permission(UsersRevoke(app))
	u := &Users{application: app}
	u.listUsers = platformErrors(httpfw.Get[admin.Page[User]]("/api/admin/users").
		ID("listAdminUsers").Summary("The users who hold this application's roles").Tag("admin").
		Query(textQuery, usersActive, usersClient, usersRoles, limitQuery, afterQuery).Permission(view).
		Error(http.StatusBadRequest, "INVALID_CURSOR", "the cursor is not one this server gave")).Build()
	u.getUser = platformErrors(httpfw.Get[User]("/api/admin/users/{id}").
		ID("getAdminUser").Summary("One user of this application").Tag("admin").
		Path(idPath).Permission(view).
		Error(http.StatusNotFound, "USER_NOT_FOUND", "there is no such user of this application")).Build()
	u.invite = platformErrors(httpfw.Post[InviteBody, InviteResult]("/api/admin/users", inviteRules).
		ID("inviteAdminUser").Summary("Invite a user into this application with its roles").Tag("admin").
		Status(http.StatusCreated).Permission(invite).
		Error(http.StatusBadRequest, "ROLES_REQUIRED", "give at least one of the application's roles").
		Error(http.StatusBadRequest, "UNKNOWN_ROLE", "a role is not a role of this application").
		Error(http.StatusForbidden, "ROLE_APP_FORBIDDEN", "a role belongs to another application").
		Error(http.StatusForbidden, "CLIENT_NOT_AVAILABLE", "the client is not available to this application").
		Error(http.StatusConflict, "EMAIL_EXISTS", "a user with this email already exists")).Build()
	u.listInvitations = platformErrors(httpfw.Get[admin.Page[Invitation]]("/api/admin/invitations").
		ID("listAdminInvitations").Summary("The invitations that have not been accepted").Tag("admin").
		Query(invitesStatus, limitQuery, afterQuery).Permission(view).
		Error(http.StatusBadRequest, "INVALID_CURSOR", "the cursor is not one this server gave")).Build()
	u.resend = platformErrors(httpfw.Post[ResendBody, ResendResult]("/api/admin/invitations/{id}/resend", resendRules).
		ID("resendAdminInvitation").Summary("Send an invitation again; the earlier link stops working").Tag("admin").
		Path(idPath).Permission(resend).
		Error(http.StatusNotFound, "INVITATION_NOT_FOUND", "there is no such invitation of this application").
		Error(http.StatusBadRequest, "INVITATION_DELIVERY_REQUIRED", "the invitation has nowhere to go").
		Error(http.StatusBadGateway, "INVITE_DELIVERY_FAILED", "the invitation could not be delivered; try again")).Build()
	u.revoke = platformErrors(httpfw.Delete[httpfw.NoBody]("/api/admin/invitations/{id}").
		ID("revokeAdminInvitation").Summary("Revoke an invitation: the user is deactivated and the links stop working").Tag("admin").
		Path(idPath).Permission(revoke).
		Error(http.StatusNotFound, "INVITATION_NOT_FOUND", "there is no such invitation of this application")).Build()
	adm.Section(admin.NewSection("users", "Users", "/admin/users", UsersView(app)).
		WithIcon("i-lucide-users").
		WithDescription("The people who hold this application's roles, and their invitations.").
		Serving(u.listUsers, u.getUser, u.invite, u.listInvitations, u.resend, u.revoke))
	return u
}

// mayGrant says a role is the application's or a short name the platform will resolve: app:role, or
// a name without a colon.
func mayGrant(own, role string) bool {
	return !strings.Contains(role, ":") || strings.HasPrefix(role, own+":")
}

func ownRoleList(own string, wanted []string) []string {
	out := []string{}
	for _, r := range wanted {
		if mayGrant(own, r) {
			out = append(out, r)
		}
	}
	return out
}

func csvList(text string) []string {
	out := []string{}
	for part := range strings.SplitSeq(text, ",") {
		if strings.TrimSpace(part) != "" {
			out = append(out, strings.TrimSpace(part))
		}
	}
	return out
}

func ownOnly(own string, roles []string) []string {
	out := []string{}
	for _, r := range roles {
		if strings.HasPrefix(r, own+":") {
			out = append(out, r)
		}
	}
	return out
}

func userView(own string, u flowcatalyst.UserInfo) User {
	return User{
		ID: u.ID, Email: u.Email, Name: u.Name, Active: u.Active, ClientID: u.ClientID, IdpType: u.IdpType,
		Roles: ownOnly(own, u.Roles), Invitation: optional(u.Invitation), LastLoginAt: optional(u.LastLoginAt), CreatedAt: u.CreatedAt,
	}
}

func invitationView(own string, i flowcatalyst.InvitationInfo) Invitation {
	status := InvitationPending
	if i.Status == "EXPIRED" {
		status = InvitationExpired
	}
	return Invitation{
		UserID: i.UserID, Email: i.Email, Name: i.Name, ClientID: i.ClientID, Roles: ownOnly(own, i.Roles),
		Status: status, CreatedAt: i.CreatedAt, ExpiresAt: optional(i.ExpiresAt),
	}
}

// nextCursor is the next platform page number as text, nil on the last page.
func nextCursor(page, limit, total int) *string {
	if int64(page+1)*int64(limit) < int64(total) {
		next := strconv.Itoa(page + 1)
		return &next
	}
	return nil
}

func noStore(h http.Header) { h.Set("Cache-Control", "no-store") }

// bind binds the six operations.
func (u *Users) bind(rt *httpfw.Routes, cl caller) error {
	if cl.client.Enabled() {
		app, err := cl.client.Application()
		if err != nil {
			return err
		}
		if app != u.application {
			return fmt.Errorf("the platform admin sections are for application '%s' but flowcatalyst.application is '%s'", u.application, app)
		}
	}
	own := u.application.String()

	rt.Bind(httpfw.Handle(u.listUsers, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[User], error) {
		limit := clampLimit(limitQuery.Value(in.Params))
		after, hasAfter := afterQuery.Value(in.Params)
		page, err := cursor(after, hasAfter)
		if err != nil {
			return admin.Page[User]{}, err
		}
		rolesText, _ := usersRoles.Value(in.Params)
		wanted := csvList(rolesText)
		roles := ownRoleList(own, wanted)
		if len(wanted) > 0 && len(roles) == 0 {
			// Only the application's own roles can narrow its list; a foreign name matches nothing.
			return admin.Page[User]{Items: []User{}}, nil
		}
		q, _ := textQuery.Value(in.Params)
		clientID, _ := usersClient.Value(in.Params)
		query := flowcatalyst.UserQuery{Roles: roles, Page: page, PageSize: limit}
		if strings.TrimSpace(q) != "" {
			query.Q = q
		}
		if strings.TrimSpace(clientID) != "" {
			query.ClientID = clientID
		}
		if active, ok := usersActive.Value(in.Params); ok {
			query.Active = strconv.FormatBool(active)
		}
		found, err := run(ctx, cl, "", "", func() (flowcatalyst.UserPage, error) { return cl.client.Users(ctx, query) })
		if err != nil {
			return admin.Page[User]{}, err
		}
		items := []User{}
		for _, x := range found.Users {
			items = append(items, userView(own, x))
		}
		return admin.Page[User]{Items: items, NextCursor: nextCursor(page, limit, found.Total)}, nil
	}))

	rt.Bind(httpfw.Handle(u.getUser, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (User, error) {
		id, err := pathID(idPath.Value(in.Params), "USER_NOT_FOUND", "there is no such user")
		if err != nil {
			return User{}, err
		}
		found, err := run(ctx, cl, "USER_NOT_FOUND", "there is no such user", func() (flowcatalyst.UserInfo, error) {
			return cl.client.User(ctx, id)
		})
		if err != nil {
			return User{}, err
		}
		return userView(own, found), nil
	}))

	rt.Bind(httpfw.Handle(u.invite, func(ctx context.Context, _ fw.Context, in httpfw.In[InviteBody]) (InviteResult, error) {
		body := in.Body
		for _, role := range body.Roles {
			if !mayGrant(own, role) {
				return InviteResult{}, httpfw.NewError(http.StatusForbidden, "ROLE_APP_FORBIDDEN",
					"a role belongs to another application: roles must be this application's")
			}
		}
		link := body.ReturnInviteLink != nil && *body.ReturnInviteLink
		send := body.SendInvitation == nil || *body.SendInvitation
		request := flowcatalyst.InviteRequest{
			Email: strings.TrimSpace(body.Email), Name: strings.TrimSpace(body.Name), ClientID: strings.TrimSpace(body.ClientID),
			Roles: append([]string{}, body.Roles...), SendInvitation: send, ReturnInviteLink: link,
		}
		made, err := write(ctx, cl, "", "", func() (flowcatalyst.InviteResult, error) { return cl.client.Invite(ctx, request) })
		if err != nil {
			return InviteResult{}, err
		}
		// The link goes to the person who asked for it, and only when they asked (whatever the platform sent).
		out := InviteResult{ID: made.ID, InvitationSent: made.InvitationSent}
		if link {
			out.InviteLink = optional(made.InviteLink)
		}
		noStore(in.Header)
		return out, nil
	}))

	rt.Bind(httpfw.Handle(u.listInvitations, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[Invitation], error) {
		limit := clampLimit(limitQuery.Value(in.Params))
		after, hasAfter := afterQuery.Value(in.Params)
		page, err := cursor(after, hasAfter)
		if err != nil {
			return admin.Page[Invitation]{}, err
		}
		status, _ := invitesStatus.Value(in.Params)
		found, err := run(ctx, cl, "", "", func() (flowcatalyst.InvitationPage, error) {
			return cl.client.Invitations(ctx, string(status), page, limit)
		})
		if err != nil {
			return admin.Page[Invitation]{}, err
		}
		items := []Invitation{}
		for _, x := range found.Invitations {
			items = append(items, invitationView(own, x))
		}
		return admin.Page[Invitation]{Items: items, NextCursor: nextCursor(page, limit, found.Total)}, nil
	}))

	rt.Bind(httpfw.Handle(u.resend, func(ctx context.Context, _ fw.Context, in httpfw.In[ResendBody]) (ResendResult, error) {
		id, err := pathID(idPath.Value(in.Params), "INVITATION_NOT_FOUND", "there is no such invitation")
		if err != nil {
			return ResendResult{}, err
		}
		link := in.Body.ReturnInviteLink != nil && *in.Body.ReturnInviteLink
		sent, err := write(ctx, cl, "INVITATION_NOT_FOUND", "there is no such invitation", func() (flowcatalyst.ResendResult, error) {
			return cl.client.ResendInvitation(ctx, id, !link, link)
		})
		if err != nil {
			return ResendResult{}, err
		}
		out := ResendResult{ExpiresAt: optional(sent.ExpiresAt)}
		if link {
			out.InviteLink = optional(sent.InviteLink)
		}
		noStore(in.Header)
		return out, nil
	}))

	rt.Bind(httpfw.Handle(u.revoke, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (httpfw.NoBody, error) {
		id, err := pathID(idPath.Value(in.Params), "INVITATION_NOT_FOUND", "there is no such invitation")
		if err != nil {
			return httpfw.NoBody{}, err
		}
		_, err = write(ctx, cl, "INVITATION_NOT_FOUND", "there is no such invitation", func() (bool, error) {
			return true, cl.client.RevokeInvitation(ctx, id)
		})
		return httpfw.NoBody{}, err
	}))
	return nil
}
