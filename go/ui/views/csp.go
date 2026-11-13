package views

import (
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

var hashPattern = regexp.MustCompile(`^sha(256|384|512)-[A-Za-z0-9+/]+={0,2}$`)

// imageSourcePattern is the rule of contract section 6 rule 1 (amended 2026-11-12) for an extra
// image source, matched after ASCII upper case is lowered: an https origin, one optional leading
// wildcard label, host labels of letters, digits and hyphens, an optional port, nothing after.
var imageSourcePattern = regexp.MustCompile(`^https://(\*\.)?[a-z0-9-]+(\.[a-z0-9-]+)*(:[1-9][0-9]{0,4})?$`)

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
//
// [CSPWithImageSources] is the same policy with extra image hosts.
func CSP(scriptHashes ...string) (string, error) {
	return CSPWithImageSources(scriptHashes, nil)
}

// CSPWithImageSources is [CSP] with extra image sources after img-src 'self' data: (contract
// section 6 rule 1, amended 2026-11-12), for example the tile server of a map:
//
//	img-src 'self' data: https://tile.openstreetmap.org
//
// Each source is an https origin, https://host or https://host:port, optionally with one leading
// wildcard label (https://*.example.org): host labels of letters, digits and hyphens, a port of 1 to
// 65535, nothing after the authority (no path, query or fragment, not even a trailing slash), no
// user-info. ASCII upper case is lowered; duplicates collapse to the first; the given order is kept.
// Anything else (a bare *, a bare scheme, data:, blob:, http, whitespace, quotes, commas,
// semicolons) is an error. With no image sources the policy is exactly CSP's.
func CSPWithImageSources(scriptHashes, imageSources []string) (string, error) {
	var script strings.Builder
	script.WriteString("script-src 'self' 'unsafe-eval'")
	for _, h := range scriptHashes {
		if !hashPattern.MatchString(h) {
			return "", errors.New("views: a script hash must be sha256-, sha384- or sha512- followed by base64, without quotes")
		}
		fmt.Fprintf(&script, " '%s'", h)
	}
	var img strings.Builder
	img.WriteString("img-src 'self' data:")
	var seen []string
	for _, s := range imageSources {
		src, err := imageSource(s)
		if err != nil {
			return "", err
		}
		if !slices.Contains(seen, src) {
			seen = append(seen, src)
			img.WriteString(" " + src)
		}
	}
	return "default-src 'self'; " + script.String() +
		"; style-src 'self'; style-src-attr 'unsafe-inline'; " + img.String() + "; font-src 'self';" +
		" connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self';" +
		" object-src 'none'", nil
}

// imageSource checks one extra image source and returns it lower-cased. Only ASCII letters are
// lowered (strings.ToLower would turn the Kelvin sign into k); anything else outside the rule's
// characters fails the pattern.
func imageSource(s string) (string, error) {
	lower := []byte(s)
	for i, c := range lower {
		if c >= 'A' && c <= 'Z' {
			lower[i] = c + ('a' - 'A')
		}
	}
	src := string(lower)
	ok := imageSourcePattern.MatchString(src)
	if ok {
		if i := strings.LastIndexByte(src, ':'); i > len("https") {
			port, err := strconv.Atoi(src[i+1:])
			ok = err == nil && port <= 65535
		}
	}
	if !ok {
		return "", fmt.Errorf("views: image source %q must be an https origin, https://host or https://host:port, "+
			"optionally with one leading wildcard label (https://*.example.org), with nothing after the host or port", s)
	}
	return src, nil
}
