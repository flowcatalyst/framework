package platformadmin

import (
	"context"
	"fmt"
	"net/http"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/admin"
	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// EventTypeStatus says whether an event type is in use.
type EventTypeStatus string

// The statuses.
const (
	// EventTypeCurrent is in use.
	EventTypeCurrent EventTypeStatus = "CURRENT"
	// EventTypeArchived is archived: no new events.
	EventTypeArchived EventTypeStatus = "ARCHIVED"
)

// EnumValues implements openapi.EnumValuer.
func (EventTypeStatus) EnumValues() []string { return []string{"CURRENT", "ARCHIVED"} }

// EventType is an event type in a list.
type EventType struct {
	ID           string          `json:"id"`
	Code         string          `json:"code"`
	Name         string          `json:"name"`
	Description  *string         `json:"description,omitzero"`
	Subdomain    string          `json:"subdomain"`
	Aggregate    string          `json:"aggregate"`
	Event        string          `json:"event"`
	Status       EventTypeStatus `json:"status"`
	VersionCount int32           `json:"versionCount"`
}

// SpecVersion is one version of an event type's payload specification; Schema is the schema as JSON
// text, absent when none.
type SpecVersion struct {
	Version string  `json:"version"`
	Status  string  `json:"status"`
	Schema  *string `json:"schema,omitzero"`
}

// EventTypeDetail is an event type with its specification versions.
type EventTypeDetail struct {
	EventType EventType     `json:"eventType"`
	Versions  []SpecVersion `json:"versions"`
}

// Role is a role in a list.
type Role struct {
	ID              string  `json:"id"`
	Name            string  `json:"name"`
	DisplayName     string  `json:"displayName"`
	Description     *string `json:"description,omitzero"`
	Source          string  `json:"source"`
	ClientManaged   bool    `json:"clientManaged"`
	PermissionCount int32   `json:"permissionCount"`
}

// RoleDetail is a role with the permissions it grants, sorted.
type RoleDetail struct {
	Role        Role     `json:"role"`
	Permissions []string `json:"permissions"`
}

var (
	etStatus    = httpfw.Query[EventTypeStatus]("status").Optional()
	etSubdomain = httpfw.Query[string]("subdomain").Optional()
	etAggregate = httpfw.Query[string]("aggregate").Optional()
	textQuery   = httpfw.Query[string]("q").Optional()
	limitQuery  = httpfw.Query[int32]("limit").Default(50)
	afterQuery  = httpfw.Query[string]("after").Optional()
	roleSource  = httpfw.Query[string]("source").Optional()
	idPath      = httpfw.Path[string]("id")
	namePath    = httpfw.Path[string]("name")
)

// Catalog is the event types and roles sections (admin contract section 11): read-only, behind
// {app}:admin:event-types:view and {app}:admin:roles:view. Only the application's own event types and
// roles are ever returned, whatever the platform sends; asking for another application's record
// answers 404.
type Catalog struct {
	application fw.ApplicationCode

	listEventTypes httpfw.Operation[httpfw.NoBody, admin.Page[EventType]]
	getEventType   httpfw.Operation[httpfw.NoBody, EventTypeDetail]
	listRoles      httpfw.Operation[httpfw.NoBody, admin.Page[Role]]
	getRole        httpfw.Operation[httpfw.NoBody, RoleDetail]
}

// EventTypesView is the permission that opens the event types section: {app}:admin:event-types:view.
func EventTypesView(application fw.ApplicationCode) string {
	return application.String() + ":admin:event-types:view"
}

// RolesView is the permission that opens the roles section: {app}:admin:roles:view.
func RolesView(application fw.ApplicationCode) string {
	return application.String() + ":admin:roles:view"
}

func permission(text string) fw.Permission {
	p, err := fw.ParsePermission(text)
	if err != nil {
		panic("platformadmin: " + err.Error()) // built from an application code, which cannot be malformed
	}
	return p
}

// platformErrors are the errors every platform-backed operation documents.
func platformErrors[B, R any](b *httpfw.Builder[B, R]) *httpfw.Builder[B, R] {
	return b.Error(http.StatusBadGateway, "PLATFORM_ERROR", "the platform refused or could not be reached").
		Error(http.StatusServiceUnavailable, "PLATFORM_NOT_CONFIGURED", "the application has no platform connection")
}

// DeclareCatalog registers the event types and roles sections with the admin registry, in that
// order, and declares their operations. Call it at declaration time, after [admin.Declare].
func DeclareCatalog(adm *admin.Declared) *Catalog {
	app := adm.Application()
	web := adm.Web()
	httpfw.NameType[EventType](web, "AdminEventType")
	httpfw.NameType[SpecVersion](web, "AdminSpecVersion")
	httpfw.NameType[EventTypeDetail](web, "AdminEventTypeDetail")
	httpfw.NameType[Role](web, "AdminRole")
	httpfw.NameType[RoleDetail](web, "AdminRoleDetail")
	httpfw.NameType[admin.Page[EventType]](web, "PageAdminEventType")
	httpfw.NameType[admin.Page[Role]](web, "PageAdminRole")
	etView, rolesView := permission(EventTypesView(app)), permission(RolesView(app))
	c := &Catalog{application: app}
	c.listEventTypes = platformErrors(httpfw.Get[admin.Page[EventType]]("/api/admin/event-types").
		ID("listAdminEventTypes").Summary("The application's event types, as the platform holds them").Tag("admin").
		Query(etStatus, etSubdomain, etAggregate, textQuery, limitQuery, afterQuery).Permission(etView)).Build()
	c.getEventType = platformErrors(httpfw.Get[EventTypeDetail]("/api/admin/event-types/{id}").
		ID("getAdminEventType").Summary("One event type with its specification versions").Tag("admin").
		Path(idPath).Permission(etView).
		Error(http.StatusNotFound, "EVENT_TYPE_NOT_FOUND", "there is no such event type of this application")).Build()
	c.listRoles = platformErrors(httpfw.Get[admin.Page[Role]]("/api/admin/roles").
		ID("listAdminRoles").Summary("The application's roles, as the platform holds them").Tag("admin").
		Query(roleSource, textQuery, limitQuery, afterQuery).Permission(rolesView)).Build()
	c.getRole = platformErrors(httpfw.Get[RoleDetail]("/api/admin/roles/{name}").
		ID("getAdminRole").Summary("One role with its permissions").Tag("admin").
		Path(namePath).Permission(rolesView).
		Error(http.StatusNotFound, "ROLE_NOT_FOUND", "there is no such role of this application")).Build()
	adm.Section(
		admin.NewSection("event-types", "Event types", "/admin/event-types", EventTypesView(app)).
			WithIcon("i-lucide-zap").
			WithDescription("The event types this application publishes, as the platform holds them.").
			Serving(c.listEventTypes, c.getEventType),
		admin.NewSection("roles", "Roles", "/admin/roles", RolesView(app)).
			WithIcon("i-lucide-key-round").
			WithDescription("This application's roles and the permissions each grants.").
			Serving(c.listRoles, c.getRole),
	)
	return c
}

func eventTypeView(e flowcatalyst.EventTypeInfo) EventType {
	status := EventTypeCurrent
	if strings.EqualFold(e.Status, "ARCHIVED") {
		status = EventTypeArchived
	}
	return EventType{
		ID: e.ID, Code: e.Code, Name: e.Name, Description: optional(e.Description), Subdomain: e.Subdomain,
		Aggregate: e.Aggregate, Event: e.Event, Status: status, VersionCount: int32(len(e.SpecVersions)), //nolint:gosec // a handful of versions
	}
}

func roleView(r flowcatalyst.RoleInfo) Role {
	return Role{
		ID: r.ID, Name: r.Name, DisplayName: r.DisplayName, Description: optional(r.Description), Source: r.Source,
		ClientManaged: r.ClientManaged, PermissionCount: int32(len(r.Permissions)), //nolint:gosec // a handful of permissions
	}
}

// ownsRole says the role is the application's: its application code (else the prefix of its name)
// is the application, and its name starts with the application's prefix.
func ownsRole(own string, r flowcatalyst.RoleInfo) bool {
	owner := r.Application
	if strings.TrimSpace(owner) == "" {
		owner, _, _ = strings.Cut(r.Name, ":")
	}
	return owner == own && strings.HasPrefix(r.Name, own+":")
}

// matches reports whether the needle (lower case, "" for none) is in one of the fields.
func matches(needle string, fields ...string) bool {
	if needle == "" {
		return true
	}
	for _, f := range fields {
		if strings.Contains(strings.ToLower(f), needle) {
			return true
		}
	}
	return false
}

func lowerNeedle(in httpfw.Params) string {
	q, _ := textQuery.Value(in)
	if strings.TrimSpace(q) == "" {
		return ""
	}
	return strings.ToLower(q)
}

// bind binds the four operations.
func (c *Catalog) bind(rt *httpfw.Routes, cl caller) error {
	if cl.client.Enabled() {
		app, err := cl.client.Application()
		if err != nil {
			return err
		}
		if app != c.application {
			return fmt.Errorf("the platform admin sections are for application '%s' but flowcatalyst.application is '%s'", c.application, app)
		}
	}
	own := c.application.String()

	rt.Bind(httpfw.Handle(c.listEventTypes, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[EventType], error) {
		status, hasStatus := etStatus.Value(in.Params)
		subdomain, hasSubdomain := etSubdomain.Value(in.Params)
		aggregate, hasAggregate := etAggregate.Value(in.Params)
		needle := lowerNeedle(in.Params)
		found, err := run(ctx, cl, "", "", func() ([]flowcatalyst.EventTypeInfo, error) {
			return cl.client.EventTypes(ctx, string(status), subdomain, aggregate)
		})
		if err != nil {
			return admin.Page[EventType]{}, err
		}
		items := []EventType{}
		for _, e := range found {
			// The filters were sent to the platform; applying them here too keeps the answer right
			// whatever the platform does with them.
			if e.Application != own ||
				(hasStatus && !strings.EqualFold(string(status), e.Status)) ||
				(hasSubdomain && subdomain != e.Subdomain) ||
				(hasAggregate && aggregate != e.Aggregate) ||
				!matches(needle, e.Code, e.Name, e.Description) {
				continue
			}
			items = append(items, eventTypeView(e))
		}
		slices.SortFunc(items, func(a, b EventType) int { return strings.Compare(a.Code, b.Code) })
		after, hasAfter := afterQuery.Value(in.Params)
		return admin.PageOf(items, func(e EventType) string { return e.Code }, after, hasAfter, int(limitQuery.Value(in.Params))), nil
	}))

	rt.Bind(httpfw.Handle(c.getEventType, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (EventTypeDetail, error) {
		id := idPath.Value(in.Params)
		e, err := run(ctx, cl, "EVENT_TYPE_NOT_FOUND", "there is no such event type", func() (flowcatalyst.EventTypeInfo, error) {
			return cl.client.EventType(ctx, id)
		})
		if err != nil {
			return EventTypeDetail{}, err
		}
		if e.Application != own {
			return EventTypeDetail{}, httpfw.NewError(http.StatusNotFound, "EVENT_TYPE_NOT_FOUND", "there is no such event type")
		}
		versions := []SpecVersion{}
		for _, v := range e.SpecVersions {
			versions = append(versions, SpecVersion{Version: v.Version, Status: v.Status, Schema: optional(v.Schema)})
		}
		return EventTypeDetail{EventType: eventTypeView(e), Versions: versions}, nil
	}))

	rt.Bind(httpfw.Handle(c.listRoles, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (admin.Page[Role], error) {
		source, hasSource := roleSource.Value(in.Params)
		needle := lowerNeedle(in.Params)
		found, err := run(ctx, cl, "", "", func() ([]flowcatalyst.RoleInfo, error) { return cl.client.Roles(ctx) })
		if err != nil {
			return admin.Page[Role]{}, err
		}
		items := []Role{}
		for _, r := range found {
			if !ownsRole(own, r) || (hasSource && !strings.EqualFold(source, r.Source)) || !matches(needle, r.Name, r.DisplayName, r.Description) {
				continue
			}
			items = append(items, roleView(r))
		}
		slices.SortFunc(items, func(a, b Role) int { return strings.Compare(a.Name, b.Name) })
		after, hasAfter := afterQuery.Value(in.Params)
		return admin.PageOf(items, func(r Role) string { return r.Name }, after, hasAfter, int(limitQuery.Value(in.Params))), nil
	}))

	rt.Bind(httpfw.Handle(c.getRole, func(ctx context.Context, _ fw.Context, in httpfw.In[httpfw.NoBody]) (RoleDetail, error) {
		name := namePath.Value(in.Params)
		if !strings.HasPrefix(name, own+":") {
			return RoleDetail{}, httpfw.NewError(http.StatusNotFound, "ROLE_NOT_FOUND", "there is no such role")
		}
		r, err := run(ctx, cl, "ROLE_NOT_FOUND", "there is no such role", func() (flowcatalyst.RoleInfo, error) {
			return cl.client.Role(ctx, name)
		})
		if err != nil {
			return RoleDetail{}, err
		}
		if !ownsRole(own, r) {
			return RoleDetail{}, httpfw.NewError(http.StatusNotFound, "ROLE_NOT_FOUND", "there is no such role")
		}
		permissions := slices.Clone(r.Permissions)
		slices.Sort(permissions)
		return RoleDetail{Role: roleView(r), Permissions: permissions}, nil
	}))
	return nil
}
