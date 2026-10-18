package flowcatalyst

// What the admin pages read from, and send to, the platform (admin contract sections 11 and 12):
// plain values, no SDK types. Times are the platform's ISO-8601 text, kept as it sends them. None of
// these ever holds a password hash, a token or a second-factor field: the platform does not send
// them, and the parsing reads named fields only.

// SpecVersion is one version of an event type's payload specification.
type SpecVersion struct {
	Version string
	Status  string
	// Schema is the schema as pretty-printed JSON text; empty when there is none.
	Schema string
}

// EventTypeInfo is an event type as the platform holds it.
type EventTypeInfo struct {
	ID, Code, Name, Description, Application, Subdomain, Aggregate, Event, Status string
	SpecVersions                                                                  []SpecVersion
}

// RoleInfo is a role as the platform holds it.
type RoleInfo struct {
	ID, Name, Application, DisplayName, Description, Source string
	ClientManaged                                           bool
	Permissions                                             []string
}

// UserInfo is one of the application's users.
type UserInfo struct {
	ID, Email, Name string
	Active          bool
	ClientID        string
	// IdpType is INTERNAL (a platform password) or OIDC (federated).
	IdpType string
	// Roles are the application's own roles the user holds.
	Roles []string
	// Invitation is PENDING or EXPIRED while the user awaits an invitation, else empty.
	Invitation  string
	LastLoginAt string
	CreatedAt   string
}

// UserQuery is the filters and the page of a user list.
type UserQuery struct {
	// Q is a substring of name or email.
	Q string
	// Active is "true", "false" or empty for both.
	Active string
	// ClientID restricts to users homed at this client.
	ClientID string
	// Roles are the application's roles; users holding any (empty for no filter).
	Roles []string
	// Page is the 0-based page.
	Page int
	// PageSize is 1 to 200.
	PageSize int
	// SortField is name, email or createdAt, or empty.
	SortField string
	// SortOrder is asc or desc, or empty.
	SortOrder string
}

// UserPage is a page of users.
type UserPage struct {
	Users    []UserInfo
	Total    int
	Page     int
	PageSize int
}

// InvitationInfo is an outstanding invitation. It never carries the link.
type InvitationInfo struct {
	// UserID is the invited user's id (what resend and revoke take).
	UserID, Email, Name, ClientID string
	Roles                         []string
	// Status is PENDING (a live link exists) or EXPIRED.
	Status    string
	CreatedAt string
	ExpiresAt string
}

// InvitationPage is a page of invitations.
type InvitationPage struct {
	Invitations []InvitationInfo
	Total       int
	Page        int
	PageSize    int
}

// InviteRequest is an invitation to send.
type InviteRequest struct {
	Email, Name string
	// ClientID is an id or the client's identifier.
	ClientID string
	Roles    []string
	// SendInvitation says whether the platform emails the invitation.
	SendInvitation bool
	// ReturnInviteLink says whether the platform returns the link instead of emailing it.
	ReturnInviteLink bool
}

// InviteResult is the platform's answer to an invitation. InviteLink is a bearer credential: it is
// handed to the person who asked for it and to nobody else, and is in no log; String withholds it.
type InviteResult struct {
	ID             string
	InviteLink     string
	InvitationSent bool
}

// String withholds the link.
func (r InviteResult) String() string {
	link := "none"
	if r.InviteLink != "" {
		link = "withheld"
	}
	return "InviteResult[id=" + r.ID + ", inviteLink=" + link + "]"
}

// ResendResult is the platform's answer to a resend. InviteLink is a bearer credential, as above.
type ResendResult struct {
	InviteLink string
	ExpiresAt  string
}

// String withholds the link.
func (r ResendResult) String() string {
	link := "none"
	if r.InviteLink != "" {
		link = "withheld"
	}
	return "ResendResult[expiresAt=" + r.ExpiresAt + ", inviteLink=" + link + "]"
}

// EventInfo is an event in a list.
type EventInfo struct {
	ID, Type, Source, Subject, Time, Application, MessageGroup, CorrelationID, ClientID string
}

// ContextEntry is one key and value of an event's context data.
type ContextEntry struct{ Key, Value string }

// EventDetail is an event with its payload.
type EventDetail struct {
	Event EventInfo
	// Data is the payload as JSON text; empty when there is none.
	Data        string
	Context     []ContextEntry
	CausationID string
}

// EventQuery is the filters and the page of an event list.
type EventQuery struct {
	Type, Subject, CorrelationID, ClientID string
	// Since and Until are RFC 3339 times, or empty.
	Since, Until  string
	Limit, Offset int
}

// JobInfo is a dispatch job. The list fills the first part; the single read adds the rest (empty or
// nil in a list).
type JobInfo struct {
	ID, EventID, SubscriptionID, ClientID, Code, Status, Kind, TargetURL, Mode, MessageGroup, Descriptor string
	CreatedAt, LastAttemptAt, CompletedAt                                                                string
	AttemptCount                                                                                         int
	Protocol, RetryStrategy, LastError                                                                   string
	TimeoutSeconds, MaxRetries                                                                           *int
}

// JobQuery is the filters and the page of a dispatch-job list.
type JobQuery struct {
	// Statuses are the states to keep (empty for any).
	Statuses                     []string
	Code, ClientID, MessageGroup string
	Since, Until                 string
	Limit, Offset                int
}

// AttemptInfo is one delivery attempt of a dispatch job.
type AttemptInfo struct {
	AttemptNumber            int
	AttemptedAt, CompletedAt string
	DurationMillis           *int64
	ResponseCode             *int
	ResponseBody             string
	Success                  bool
	ErrorMessage, ErrorType  string
}
