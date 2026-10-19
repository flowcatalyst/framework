package auth

import (
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
)

// Principal is who signed in or presented a token, as the platform described them.
type Principal struct {
	// ID is the principal id (the sub claim), for example prn_0HZXEQ5Y8JY5Z.
	ID string
	// Kind says whether it is a person or a service account (the type claim).
	Kind fw.PrincipalKind
	// Name and Email are the display name and address, "" when the token had none.
	Name, Email string
	// Tier is ANCHOR, PARTNER or CLIENT, "" when the token had none.
	Tier string
	// HomeClient is the platform id of the principal's own client (the client_id claim), "" when
	// none.
	HomeClient string
	// Clients are the clients it may act for: "*", or "<id>:<identifier>" or "<id>" entries.
	Clients []string
	// Applications are "*", or "<id>:<code>" entries.
	Applications []string
	// Roles are role names, "<application>:<role>".
	Roles []string
	// Scopes are the permissions a bearer access token carries (the scope claim); empty for a
	// browser session, whose id_token carries roles, not permissions.
	Scopes []string
}

// MayActFor reports whether the principal may act for a client: a Clients entry that is "*",
// equals the value, starts with "<value>:" (the value is the platform id) or ends with ":<value>"
// (the value is the client's identifier, which fw.ClientID holds).
func (p Principal) MayActFor(client string) bool {
	if client == "" {
		return false
	}
	for _, entry := range p.Clients {
		if entry == "*" || entry == client || strings.HasPrefix(entry, client+":") {
			return true
		}
		if _, after, ok := strings.Cut(entry, ":"); ok && after == client {
			return true
		}
	}
	return false
}

// MayUse reports whether the principal may use an application: an Applications entry that is "*",
// or whose code is the value. An entry "<id>:<code>" matches on the part after the first colon; an
// entry without a colon is compared whole.
func (p Principal) MayUse(applicationCode string) bool {
	if applicationCode == "" {
		return false
	}
	for _, entry := range p.Applications {
		if entry == "*" {
			return true
		}
		code := entry
		if _, after, ok := strings.Cut(entry, ":"); ok {
			code = after
		}
		if code == applicationCode {
			return true
		}
	}
	return false
}

// HomeClientIdentifier is the identifier of the home client, from the Clients entry
// "<homeClient>:<identifier>"; false when there is no home client or the token did not name its
// identifier.
func (p Principal) HomeClientIdentifier() (string, bool) {
	if p.HomeClient == "" {
		return "", false
	}
	for _, entry := range p.Clients {
		if rest, ok := strings.CutPrefix(entry, p.HomeClient+":"); ok && rest != "" {
			return rest, true
		}
	}
	return "", false
}

// HasRole reports whether the principal holds a role, "<application>:<role>".
func (p Principal) HasRole(role string) bool { return slices.Contains(p.Roles, role) }

// HasScope reports whether a bearer token carried a permission, by exact name.
func (p Principal) HasScope(scope string) bool { return slices.Contains(p.Scopes, scope) }
