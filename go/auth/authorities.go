package auth

import (
	"net/http"
	"regexp"
	"slices"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// PlatformClient is "no particular client": the same value as the receivers' platform client.
var PlatformClient = fw.MustClientID("_platform")

// PermissionMapper turns a principal into the permissions its Context carries (contract section
// 7.3). Behaviour in code: the platform's id_token carries roles, not permissions, and only the
// application knows what its roles mean. It is called on every authenticated request, so a change
// of mapping takes effect at once.
//
//	auth.ScopesMapper().And(auth.ByRole(map[string][]string{
//		"ingestor:operator": {"ingestor:ingestion:manage"},
//	}))
type PermissionMapper func(Principal) []string

// ScopesMapper is the default: the scope of a bearer access token. A browser session gets none.
func ScopesMapper() PermissionMapper {
	return func(p Principal) []string { return slices.Clone(p.Scopes) }
}

// NoPermissions grants nothing.
func NoPermissions() PermissionMapper { return func(Principal) []string { return nil } }

// ByRole is a table from role name ("<application>:<role>", as the platform sends it) to
// permissions. The table is copied.
func ByRole(table map[string][]string) PermissionMapper {
	frozen := make(map[string][]string, len(table))
	for role, perms := range table {
		frozen[role] = slices.Clone(perms)
	}
	return func(p Principal) []string {
		var out []string
		for _, role := range p.Roles {
			for _, perm := range frozen[role] {
				if !slices.Contains(out, perm) {
					out = append(out, perm)
				}
			}
		}
		return out
	}
}

// And is the union with another mapping.
func (m PermissionMapper) And(other PermissionMapper) PermissionMapper {
	return func(p Principal) []string {
		out := m(p)
		for _, perm := range other(p) {
			if !slices.Contains(out, perm) {
				out = append(out, perm)
			}
		}
		return out
	}
}

// ClientSelector chooses the client a request acts for (contract section 7.3). Whatever it
// returns, the framework then checks that the principal may act for it (Principal.MayActFor),
// unless it is [PlatformClient], and answers 403 CLIENT_NOT_PERMITTED otherwise. A fw.ClientID
// holds the client's identifier (its code), not its platform id. An error becomes a 500.
type ClientSelector func(p Principal, r *http.Request) (fw.ClientID, error)

var clientIdentifier = regexp.MustCompile(`^[A-Za-z0-9._-]{1,100}$`)

// HomeClient is the default selection: the home client's identifier when the token names it;
// else, when Clients holds exactly one entry of the form "<id>:<identifier>", that identifier (a
// client service token: access tokens carry no home client); else [PlatformClient].
func HomeClient() ClientSelector {
	return func(p Principal, _ *http.Request) (fw.ClientID, error) {
		identifier, ok := p.HomeClientIdentifier()
		if !ok && len(p.Clients) == 1 {
			if _, after, found := cutColon(p.Clients[0]); found && after != "" {
				identifier, ok = after, true
			}
		}
		if ok && clientIdentifier.MatchString(identifier) {
			return fw.ParseClientID(identifier)
		}
		return PlatformClient, nil
	}
}

// cutColon splits at the first colon; the colon must not be the first character.
func cutColon(s string) (before, after string, found bool) {
	for i := 1; i < len(s); i++ {
		if s[i] == ':' {
			return s[:i], s[i+1:], true
		}
	}
	return s, "", false
}

// Source says how a request was authenticated.
type Source int

const (
	// SourceSession is the session cookie.
	SourceSession Source = iota + 1
	// SourceBearer is an Authorization: Bearer platform access token.
	SourceBearer
)

// String returns SESSION or BEARER.
func (s Source) String() string {
	switch s {
	case SourceSession:
		return "SESSION"
	case SourceBearer:
		return "BEARER"
	}
	return ""
}

// Authenticated is who a request is authenticated as. Read it with [Current].
type Authenticated struct {
	// Principal is the principal.
	Principal Principal
	// Client is the client the Context carries.
	Client fw.ClientID
	// Permissions is what the application's mapping granted.
	Permissions []string
	// Source is a browser session or a bearer token.
	Source Source
	// ExpiresAt is when the session or token stops being valid.
	ExpiresAt time.Time
	// IdleExpiresAt is, for a session, when it expires without activity; zero for a bearer token.
	IdleExpiresAt time.Time
	// AuthTime is when the person signed in at the platform, for a session; zero for a bearer token.
	AuthTime time.Time
}

// authorities is client selection, permission mapping and the Context (contract section 7.3).
type authorities struct {
	permissions PermissionMapper
	clients     ClientSelector
	application fw.ApplicationCode // zero for none
	logger      interface{ Warn(msg string, args ...any) }
}

func (a *authorities) authenticate(p Principal, r *http.Request, src Source, expiresAt, idleExpiresAt, authTime time.Time) (Authenticated, error) {
	client, err := a.clients(p, r)
	if err != nil {
		return Authenticated{}, err
	}
	if client.IsZero() {
		return Authenticated{}, errNoClient
	}
	if client != PlatformClient && !p.MayActFor(client.String()) {
		return Authenticated{}, httpfw.Forbidden("CLIENT_NOT_PERMITTED", "the principal may not act for this client")
	}
	return Authenticated{
		Principal: p, Client: client, Permissions: slices.Clone(a.permissions(p)), Source: src,
		ExpiresAt: expiresAt, IdleExpiresAt: idleExpiresAt, AuthTime: authTime,
	}, nil
}

// context builds the fw.Context of an authenticated request. A permission string fw cannot hold
// (a blank one, or one with an empty segment) is left out and logged: one bad scope on a token
// must not make every request of that token a 500.
func (a *authorities) context(au Authenticated, r *http.Request) (fw.Context, error) {
	id, err := fw.ParsePrincipalID(au.Principal.ID)
	if err != nil {
		return fw.Context{}, err
	}
	cx := fw.ContextFor(au.Client).WithPrincipalOfKind(id, au.Principal.Kind).WithCorrelation(httpfw.CorrelationID(r))
	if !a.application.IsZero() {
		cx = cx.WithApplication(a.application)
	}
	perms := make([]fw.Permission, 0, len(au.Permissions))
	for _, s := range au.Permissions {
		p, err := fw.ParsePermission(s)
		if err != nil {
			if a.logger != nil {
				a.logger.Warn("a permission the mapping granted is not usable and was left out", "permission_length", len(s))
			}
			continue
		}
		perms = append(perms, p)
	}
	return cx.WithPermissions(perms...), nil
}

var errNoClient = httpfw.NewError(http.StatusInternalServerError, "INTERNAL", "internal error")
