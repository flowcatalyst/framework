package flowcatalyst

import (
	"encoding/json/jsontext"
	"encoding/json/v2"
	"errors"
	"slices"
)

// The platform's answers are read into named fields only: a field the platform adds, or should
// never send (a token), is not copied. A value of the wrong type is a failed read.

var errUnexpected = errors.New("the platform answered with something the module cannot read")

func parseAs[T any](raw []byte) (T, error) {
	var v T
	if err := json.Unmarshal(raw, &v); err != nil {
		return v, errUnexpected
	}
	return v, nil
}

type wireSpecVersion struct {
	Version string         `json:"version"`
	Status  string         `json:"status"`
	Schema  jsontext.Value `json:"schema"`
}

type wireEventType struct {
	ID           string            `json:"id"`
	Code         string            `json:"code"`
	Name         string            `json:"name"`
	Description  string            `json:"description"`
	Application  string            `json:"application"`
	Subdomain    string            `json:"subdomain"`
	Aggregate    string            `json:"aggregate"`
	EventName    string            `json:"eventName"`
	Status       string            `json:"status"`
	SpecVersions []wireSpecVersion `json:"specVersions"`
}

func (w wireEventType) info() EventTypeInfo {
	out := EventTypeInfo{
		ID: w.ID, Code: w.Code, Name: w.Name, Description: w.Description, Application: w.Application,
		Subdomain: w.Subdomain, Aggregate: w.Aggregate, Event: w.EventName, Status: w.Status,
		SpecVersions: []SpecVersion{},
	}
	for _, v := range w.SpecVersions {
		out.SpecVersions = append(out.SpecVersions, SpecVersion{Version: v.Version, Status: v.Status, Schema: prettyJSON(v.Schema)})
	}
	return out
}

// prettyJSON is a JSON value as indented text; empty for none or null, a string as itself.
func prettyJSON(v jsontext.Value) string {
	if len(v) == 0 || v.Kind() == 'n' {
		return ""
	}
	if v.Kind() == '"' {
		var s string
		if json.Unmarshal(v, &s) == nil {
			return s
		}
	}
	c := slices.Clone(v)
	if err := c.Indent(jsontext.WithIndent("  ")); err != nil {
		return string(v)
	}
	return string(c)
}

func parseEventTypes(raw []byte) ([]EventTypeInfo, error) {
	w, err := parseAs[struct {
		Items []wireEventType `json:"items"`
	}](raw)
	if err != nil {
		return nil, err
	}
	out := make([]EventTypeInfo, 0, len(w.Items))
	for _, e := range w.Items {
		out = append(out, e.info())
	}
	return out, nil
}

func parseEventType(raw []byte) (EventTypeInfo, error) {
	w, err := parseAs[wireEventType](raw)
	return w.info(), err
}

type wireRole struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Application   string   `json:"applicationCode"`
	DisplayName   string   `json:"displayName"`
	Description   string   `json:"description"`
	Source        string   `json:"source"`
	ClientManaged bool     `json:"clientManaged"`
	Permissions   []string `json:"permissions"`
}

func (w wireRole) info() RoleInfo {
	return RoleInfo{
		ID: w.ID, Name: w.Name, Application: w.Application, DisplayName: w.DisplayName, Description: w.Description,
		Source: w.Source, ClientManaged: w.ClientManaged, Permissions: nonNil(w.Permissions),
	}
}

func nonNil[T any](s []T) []T {
	if s == nil {
		return []T{}
	}
	return s
}

func parseRoles(raw []byte) ([]RoleInfo, error) {
	w, err := parseAs[struct {
		Roles []wireRole `json:"roles"`
	}](raw)
	if err != nil {
		return nil, err
	}
	out := make([]RoleInfo, 0, len(w.Roles))
	for _, r := range w.Roles {
		out = append(out, r.info())
	}
	return out, nil
}

func parseRole(raw []byte) (RoleInfo, error) {
	w, err := parseAs[wireRole](raw)
	return w.info(), err
}

type wireUser struct {
	ID          string   `json:"id"`
	Email       string   `json:"email"`
	Name        string   `json:"name"`
	Active      bool     `json:"active"`
	ClientID    string   `json:"clientId"`
	IdpType     string   `json:"idpType"`
	Roles       []string `json:"roles"`
	Invitation  string   `json:"invitation"`
	LastLoginAt string   `json:"lastLoginAt"`
	CreatedAt   string   `json:"createdAt"`
}

func (w wireUser) info() UserInfo {
	return UserInfo{
		ID: w.ID, Email: w.Email, Name: w.Name, Active: w.Active, ClientID: w.ClientID, IdpType: w.IdpType,
		Roles: nonNil(w.Roles), Invitation: w.Invitation, LastLoginAt: w.LastLoginAt, CreatedAt: w.CreatedAt,
	}
}

func parseUser(raw []byte) (UserInfo, error) {
	w, err := parseAs[wireUser](raw)
	return w.info(), err
}

func parseUsers(raw []byte) (UserPage, error) {
	w, err := parseAs[struct {
		Users    []wireUser `json:"users"`
		Total    int        `json:"total"`
		Page     int        `json:"page"`
		PageSize int        `json:"pageSize"`
	}](raw)
	if err != nil {
		return UserPage{}, err
	}
	out := UserPage{Users: make([]UserInfo, 0, len(w.Users)), Total: w.Total, Page: w.Page, PageSize: w.PageSize}
	for _, u := range w.Users {
		out.Users = append(out.Users, u.info())
	}
	return out, nil
}

func parseInvitations(raw []byte) (InvitationPage, error) {
	type wire struct {
		UserID    string   `json:"userId"`
		Email     string   `json:"email"`
		Name      string   `json:"name"`
		ClientID  string   `json:"clientId"`
		Roles     []string `json:"roles"`
		Status    string   `json:"status"`
		CreatedAt string   `json:"createdAt"`
		ExpiresAt string   `json:"expiresAt"`
	}
	w, err := parseAs[struct {
		Invitations []wire `json:"invitations"`
		Total       int    `json:"total"`
		Page        int    `json:"page"`
		PageSize    int    `json:"pageSize"`
	}](raw)
	if err != nil {
		return InvitationPage{}, err
	}
	out := InvitationPage{Invitations: make([]InvitationInfo, 0, len(w.Invitations)), Total: w.Total, Page: w.Page, PageSize: w.PageSize}
	for _, i := range w.Invitations {
		out.Invitations = append(out.Invitations, InvitationInfo{
			UserID: i.UserID, Email: i.Email, Name: i.Name, ClientID: i.ClientID, Roles: nonNil(i.Roles),
			Status: i.Status, CreatedAt: i.CreatedAt, ExpiresAt: i.ExpiresAt,
		})
	}
	return out, nil
}

func parseInvite(raw []byte) (InviteResult, error) {
	w, err := parseAs[struct {
		ID             string `json:"id"`
		InviteLink     string `json:"inviteLink"`
		InvitationSent bool   `json:"invitationSent"`
	}](raw)
	return InviteResult{ID: w.ID, InviteLink: w.InviteLink, InvitationSent: w.InvitationSent}, err
}

func parseResend(raw []byte) (ResendResult, error) {
	w, err := parseAs[struct {
		InviteLink string `json:"inviteLink"`
		ExpiresAt  string `json:"expiresAt"`
	}](raw)
	return ResendResult{InviteLink: w.InviteLink, ExpiresAt: w.ExpiresAt}, err
}

type wireEvent struct {
	ID            string `json:"id"`
	Type          string `json:"type"`
	Source        string `json:"source"`
	Subject       string `json:"subject"`
	Time          string `json:"time"`
	Application   string `json:"application"`
	MessageGroup  string `json:"messageGroup"`
	CorrelationID string `json:"correlationId"`
	ClientID      string `json:"clientId"`
}

func (w wireEvent) info() EventInfo {
	return EventInfo(w)
}

func parseEvents(raw []byte) ([]EventInfo, error) {
	w, err := parseAs[[]wireEvent](raw)
	if err != nil {
		return nil, err
	}
	out := make([]EventInfo, 0, len(w))
	for _, e := range w {
		out = append(out, e.info())
	}
	return out, nil
}

func parseEventDetail(raw []byte) (EventDetail, error) {
	type contextEntry struct {
		Key   string `json:"key"`
		Value string `json:"value"`
	}
	type wire struct {
		wireEvent
		Data        jsontext.Value `json:"data"`
		ContextData []contextEntry `json:"contextData"`
		CausationID string         `json:"causationId"`
	}
	w, err := parseAs[wire](raw)
	if err != nil {
		return EventDetail{}, err
	}
	out := EventDetail{Event: w.info(), Data: prettyJSON(w.Data), Context: []ContextEntry{}, CausationID: w.CausationID}
	for _, c := range w.ContextData {
		out.Context = append(out.Context, ContextEntry(c))
	}
	return out, nil
}

type wireJob struct {
	ID             string `json:"id"`
	EventID        string `json:"eventId"`
	SubscriptionID string `json:"subscriptionId"`
	ClientID       string `json:"clientId"`
	Code           string `json:"code"`
	Status         string `json:"status"`
	Kind           string `json:"kind"`
	TargetURL      string `json:"targetUrl"`
	Mode           string `json:"mode"`
	MessageGroup   string `json:"messageGroup"`
	Descriptor     string `json:"descriptor"`
	CreatedAt      string `json:"createdAt"`
	LastAttemptAt  string `json:"lastAttemptAt"`
	CompletedAt    string `json:"completedAt"`
	AttemptCount   int    `json:"attemptCount"`
	Protocol       string `json:"protocol"`
	RetryStrategy  string `json:"retryStrategy"`
	LastError      string `json:"lastError"`
	TimeoutSeconds *int   `json:"timeoutSeconds"`
	MaxRetries     *int   `json:"maxRetries"`
}

func (w wireJob) info() JobInfo {
	return JobInfo(w)
}

func parseJob(raw []byte) (JobInfo, error) {
	w, err := parseAs[wireJob](raw)
	return w.info(), err
}

func parseJobs(raw []byte) ([]JobInfo, error) {
	w, err := parseAs[[]wireJob](raw)
	if err != nil {
		return nil, err
	}
	out := make([]JobInfo, 0, len(w))
	for _, j := range w {
		out = append(out, j.info())
	}
	return out, nil
}

func parseAttempts(raw []byte) ([]AttemptInfo, error) {
	type wire struct {
		AttemptNumber  int    `json:"attemptNumber"`
		AttemptedAt    string `json:"attemptedAt"`
		CompletedAt    string `json:"completedAt"`
		DurationMillis *int64 `json:"durationMillis"`
		ResponseCode   *int   `json:"responseCode"`
		ResponseBody   string `json:"responseBody"`
		Success        bool   `json:"success"`
		ErrorMessage   string `json:"errorMessage"`
		ErrorType      string `json:"errorType"`
	}
	w, err := parseAs[[]wire](raw)
	if err != nil {
		return nil, err
	}
	out := make([]AttemptInfo, 0, len(w))
	for _, a := range w {
		out = append(out, AttemptInfo(a))
	}
	return out, nil
}
