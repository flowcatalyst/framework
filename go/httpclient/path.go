package httpclient

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
)

var (
	placeholder = regexp.MustCompile(`\{([A-Za-z][A-Za-z0-9]*)\}`)
	literalPath = regexp.MustCompile(`^[A-Za-z0-9\-._~!$&'()*+,;=:@]*(%[0-9A-Fa-f]{2}[A-Za-z0-9\-._~!$&'()*+,;=:@]*)*$`)
)

// pathTemplate is a request path with {name} placeholders, checked so a request can never leave
// its service: no //, ?, #, backslash, or . and .. segments; values are percent-encoded as one
// segment and may not be empty, "." or "..".
type pathTemplate struct {
	text     string
	segments []string
	names    []string
}

func parsePath(text string) (pathTemplate, error) {
	if !strings.HasPrefix(text, "/") {
		return pathTemplate{}, fmt.Errorf("%w: a request path must start with /", ErrInvalidRequest)
	}
	if strings.Contains(text, "//") || strings.ContainsAny(text, "?#\\") {
		return pathTemplate{}, fmt.Errorf("%w: a request path may not contain //, ?, # or \\ (use Query)", ErrInvalidRequest)
	}
	p := pathTemplate{text: text, segments: strings.Split(text[1:], "/")}
	seen := map[string]bool{}
	for _, seg := range p.segments {
		if seg == "." || seg == ".." {
			return pathTemplate{}, fmt.Errorf("%w: a request path may not contain . or .. segments", ErrInvalidRequest)
		}
		for _, m := range placeholder.FindAllStringSubmatch(seg, -1) {
			if seen[m[1]] {
				return pathTemplate{}, fmt.Errorf("%w: placeholder {%s} appears twice", ErrInvalidRequest, m[1])
			}
			seen[m[1]] = true
			p.names = append(p.names, m[1])
		}
		if !literalPath.MatchString(placeholder.ReplaceAllString(seg, "")) {
			return pathTemplate{}, fmt.Errorf("%w: a request path may contain only unreserved characters, sub-delimiters, ':', '@' and %%XX", ErrInvalidRequest)
		}
	}
	return p, nil
}

func (p pathTemplate) has(name string) bool {
	return slices.Contains(p.names, name)
}

// fill returns the filled, encoded path. Every placeholder needs a value, and every value a
// placeholder.
func (p pathTemplate) fill(values map[string]string) (string, error) {
	for name := range values {
		if !p.has(name) {
			return "", fmt.Errorf("%w: the path %s has no placeholder {%s}", ErrInvalidRequest, p.text, name)
		}
	}
	out := make([]string, len(p.segments))
	for i, seg := range p.segments {
		var ferr error
		out[i] = placeholder.ReplaceAllStringFunc(seg, func(m string) string {
			name := m[1 : len(m)-1]
			v, ok := values[name]
			if !ok {
				ferr = fmt.Errorf("%w: no value for {%s} in %s", ErrInvalidRequest, name, p.text)
				return ""
			}
			enc, err := encodeSegment(v)
			if err != nil {
				ferr = err
			}
			return enc
		})
		if ferr != nil {
			return "", ferr
		}
	}
	return "/" + strings.Join(out, "/"), nil
}

func encodeSegment(v string) (string, error) {
	if v == "" || v == "." || v == ".." {
		return "", fmt.Errorf("%w: a path parameter may not be empty, \".\" or \"..\"", ErrInvalidRequest)
	}
	return percentEncode(v), nil
}

const upperHex = "0123456789ABCDEF"

// percentEncode encodes everything but the RFC 3986 unreserved characters, hex in upper case.
func percentEncode(v string) string {
	var sb strings.Builder
	for i := range len(v) {
		c := v[i]
		if c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.' || c == '_' || c == '~' {
			sb.WriteByte(c)
			continue
		}
		sb.WriteByte('%')
		sb.WriteByte(upperHex[c>>4])
		sb.WriteByte(upperHex[c&0xf])
	}
	return sb.String()
}

// formEscape is Java's URLEncoder.encode: A-Z a-z 0-9 . - * _ stay, a space is '+', everything else
// is %XX of the UTF-8 bytes.
func formEscape(v string) string {
	var sb strings.Builder
	for i := range len(v) {
		c := v[i]
		switch {
		case c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '.' || c == '-' || c == '*' || c == '_':
			sb.WriteByte(c)
		case c == ' ':
			sb.WriteByte('+')
		default:
			sb.WriteByte('%')
			sb.WriteByte(upperHex[c>>4])
			sb.WriteByte(upperHex[c&0xf])
		}
	}
	return sb.String()
}
