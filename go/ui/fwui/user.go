package fwui

import (
	"net/http"
	"strings"
	"unicode"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/ui/kit"
)

// ShellUser is the signed-in user for the app shell, from the request's authentication (auth.Current):
// the display name (the email when the token had no name, the principal id when it had neither),
// the email and the initials. It reports false before the context resolver ran (a route that does
// not require a context) and for an unauthenticated request.
func ShellUser(r *http.Request) (kit.ShellUser, bool) {
	a, ok := auth.Current(r)
	if !ok {
		return kit.ShellUser{}, false
	}
	p := a.Principal
	name := p.Name
	if name == "" {
		name = p.Email
	}
	if name == "" {
		name = p.ID
	}
	return kit.ShellUser{Name: name, Email: p.Email, Initials: Initials(name)}, true
}

// Initials are the upper-cased first letters of the first two words of a name ("Ada Lovelace" is
// "AL"), or, for an address, of the part before "@" split at ".", "_" and "-" ("ada.lovelace@x.org"
// is "AL"); "?" when there is no letter or digit.
func Initials(name string) string {
	name, _, _ = strings.Cut(name, "@")
	var out []rune
	for _, word := range strings.FieldsFunc(name, func(r rune) bool {
		return unicode.IsSpace(r) || r == '.' || r == '_' || r == '-'
	}) {
		out = append(out, unicode.ToUpper([]rune(word)[0]))
		if len(out) == 2 {
			break
		}
	}
	if len(out) == 0 {
		return "?"
	}
	return string(out)
}
