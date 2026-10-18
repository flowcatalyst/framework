package auth

import (
	"crypto/subtle"
	"slices"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// kindOf is the principal kind a type claim names; anything but SERVICE is a user.
func kindOf(t string) fw.PrincipalKind {
	if t == "SERVICE" {
		return fw.PrincipalService
	}
	return fw.PrincipalUser
}

// same compares two strings in constant time.
func same(a, b string) bool { return subtle.ConstantTimeCompare([]byte(a), []byte(b)) == 1 }

// checkIDToken applies steps 5 to 12 of contract section 5.5 and returns the refusal reason, "" when
// the token is acceptable.
func checkIDToken(claims claimSet, issuer, clientID, nonce string, now time.Time) string {
	if text(claims, "iss") != issuer {
		return "iss"
	}
	aud := claims["aud"]
	audiences := texts(aud)
	_, isString := aud.(string)
	_, isArray := aud.([]any)
	if aud == nil || (!isString && !isArray) || !slices.Contains(audiences, clientID) {
		return "aud"
	}
	azp, hasAzp := claims["azp"]
	if isArray && len(audiences) > 1 && !hasAzp {
		return "azp"
	}
	if hasAzp {
		if s, ok := azp.(string); !ok || s != clientID {
			return "azp"
		}
	}
	if r := times(claims, now); r != "" {
		return r
	}
	presented := text(claims, "nonce")
	if presented == "" || !same(presented, nonce) {
		return "nonce"
	}
	if text(claims, "sub") == "" {
		return "sub"
	}
	if _, has := claims["token_use"]; has {
		return "token_use"
	}
	return ""
}

// checkAccessToken applies contract section 5.6 to an access token's claims.
func checkAccessToken(claims claimSet, issuer string, now time.Time) string {
	if text(claims, "iss") != issuer {
		return "iss"
	}
	aud := claims["aud"]
	_, isString := aud.(string)
	_, isArray := aud.([]any)
	if aud == nil || (!isString && !isArray) || !slices.Contains(texts(aud), issuer) {
		return "aud"
	}
	if r := times(claims, now); r != "" {
		return r
	}
	if text(claims, "token_use") != "api" {
		return "token_use"
	}
	if text(claims, "sub") == "" {
		return "sub"
	}
	return ""
}

// principalOf is the principal a checked token describes; scopes only from an access token. The
// second result is false when sub is not a usable principal id.
func principalOf(claims claimSet, withScopes bool) (Principal, bool) {
	sub := text(claims, "sub")
	if strings.TrimSpace(sub) == "" {
		return Principal{}, false
	}
	scopes := []string{}
	if withScopes {
		for s := range strings.SplitSeq(text(claims, "scope"), " ") {
			if strings.TrimSpace(s) != "" && !slices.Contains(scopes, s) {
				scopes = append(scopes, s)
			}
		}
	}
	return Principal{
		ID:           sub,
		Kind:         kindOf(text(claims, "type")),
		Name:         text(claims, "name"),
		Email:        text(claims, "email"),
		Tier:         text(claims, "tier"),
		HomeClient:   text(claims, "client_id"),
		Clients:      texts(claims["clients"]),
		Applications: texts(claims["applications"]),
		Roles:        texts(claims["roles"]),
		Scopes:       scopes,
	}, true
}
