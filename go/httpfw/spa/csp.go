package spa

import (
	"fmt"
	"regexp"
	"strings"
)

// CSP is a Content-Security-Policy and the headers that go with it (contract section 7). The zero
// value is not a policy: Options{} uses [Strict].
type CSP struct {
	value  string
	strict bool
}

const baseline = "frame-ancestors 'none'; object-src 'none'; base-uri 'self'"

// Baseline is the platform's policy: it restricts framing, plugins and <base> only, and leaves
// scripts, styles and connections open. An application whose pages cannot run under [Strict]
// chooses it.
func Baseline() CSP { return CSP{value: baseline} }

var hashPattern = regexp.MustCompile(`^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$`)

// Strict is the policy of docs/go/ui.md section 9 (default-src 'self', script-src 'self' plus the
// given hashes for inline snippets, style-src 'self', img-src 'self' data:, font-src 'self',
// connect-src 'self', frame-ancestors 'none', base-uri 'none', form-action 'self', object-src
// 'none'), with Cross-Origin-Opener-Policy and Permissions-Policy. A hash is "sha256-" followed by
// base64, without quotes (@flowcatalyst/ui exports the colour-mode snippet's); a malformed one
// panics, a programming error found at start-up.
func Strict(scriptHashes ...string) CSP {
	var script strings.Builder
	script.WriteString("script-src 'self'")
	for _, h := range scriptHashes {
		if !hashPattern.MatchString(h) {
			panic(fmt.Sprintf("spa: a script hash is sha256-, sha384- or sha512- followed by base64, without quotes: %q", h))
		}
		script.WriteString(" '" + h + "'")
	}
	return CSP{strict: true, value: "default-src 'self'; " + script.String() +
		"; style-src 'self'; img-src 'self' data:; font-src 'self'; connect-src 'self';" +
		" frame-ancestors 'none'; base-uri 'none'; form-action 'self'; object-src 'none'"}
}

// String returns the header value.
func (c CSP) String() string { return c.value }
