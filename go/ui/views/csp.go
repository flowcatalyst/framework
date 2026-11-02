package views

import (
	"errors"
	"fmt"
	"regexp"
	"strings"
)

var hashPattern = regexp.MustCompile(`^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$`)

// CSP returns the Content-Security-Policy of a server-rendered document, exactly as contract
// server-rendered-ui.md section 6 rule 1 writes it:
//
//	default-src 'self'; script-src 'self' 'unsafe-eval'[ '<hash>']...; style-src 'self'; style-src-attr
//	'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none';
//	base-uri 'none'; form-action 'self'; object-src 'none'
//
// 'unsafe-eval' is there because Datastar compiles every data-* expression with the Function
// constructor (its 1.0.4 bundle has no CSP-safe build); style-src-attr because a few kit components
// carry style attributes. Nothing else is relaxed: there is no inline script, so no nonce, and an
// injected script element is still refused. scriptHashes are the hashes of an application's own
// inline scripts (normally none), each sha256-, sha384- or sha512- followed by base64 and given
// without quotes; a malformed one is an error.
//
// Because of 'unsafe-eval' the policy is weaker against an XSS that can write attribute values than
// the single-page application's strict policy. The module escapes every dynamic value so that such a
// hole is not expected to exist; the policy is the second line, not the first.
func CSP(scriptHashes ...string) (string, error) {
	var script strings.Builder
	script.WriteString("script-src 'self' 'unsafe-eval'")
	for _, h := range scriptHashes {
		if !hashPattern.MatchString(h) {
			return "", errors.New("views: a script hash must be sha256-, sha384- or sha512- followed by base64, without quotes")
		}
		fmt.Fprintf(&script, " '%s'", h)
	}
	return "default-src 'self'; " + script.String() +
		"; style-src 'self'; style-src-attr 'unsafe-inline'; img-src 'self' data:; font-src 'self';" +
		" connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';" +
		" object-src 'none'", nil
}
