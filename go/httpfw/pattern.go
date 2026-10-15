package httpfw

import (
	"fmt"
	"regexp"
	"slices"
	"strings"
)

// The methods a route may have (HTTP contract section 2). HEAD is answered from GET and OPTIONS by
// the router unless a route says otherwise.
var methods = []string{"GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"}

func isMethod(m string) bool {
	return slices.Contains(methods, m)
}

var paramPattern = regexp.MustCompile(`^\{([a-z][A-Za-z0-9]*)\}$`)

// routePattern is a parsed route: "POST /orders/{id}/lines". Segments are literals or {name}
// parameters; a trailing slash is a trailing empty literal segment (a different path from none).
type routePattern struct {
	method string
	text   string // the path, as written
	segs   []string
	params []string // parameter names in segment order
}

// parsePattern reads "METHOD /path".
func parsePattern(pattern string) (routePattern, error) {
	method, path, ok := strings.Cut(pattern, " ")
	if !ok || !isMethod(method) {
		return routePattern{}, fmt.Errorf("route pattern must be \"METHOD /path\" with a method of %s: %q", strings.Join(methods, ", "), pattern)
	}
	return parsePath(method, path)
}

func parsePath(method, path string) (routePattern, error) {
	if !strings.HasPrefix(path, "/") {
		return routePattern{}, fmt.Errorf("route pattern must start with '/': %q", path)
	}
	parts := strings.Split(path[1:], "/")
	p := routePattern{method: method, text: path}
	for i, part := range parts {
		if part == "" && i != len(parts)-1 {
			return routePattern{}, fmt.Errorf("route pattern has an empty segment: %q", path)
		}
		if strings.ContainsAny(part, "{}") {
			m := paramPattern.FindStringSubmatch(part)
			if m == nil {
				return routePattern{}, fmt.Errorf("route pattern parameter must be a whole segment {name} with name [a-z][A-Za-z0-9]*: %q", path)
			}
			if slices.Contains(p.params, m[1]) {
				return routePattern{}, fmt.Errorf("route pattern repeats parameter '%s': %q", m[1], path)
			}
			p.params = append(p.params, m[1])
		}
		p.segs = append(p.segs, part)
	}
	return p, nil
}

func (p routePattern) isParam(i int) bool { return strings.HasPrefix(p.segs[i], "{") }

// shape is the pattern with parameter names ignored, so /a/{x} and /a/{y} are equal.
func (p routePattern) shape() string {
	var sb strings.Builder
	for i, s := range p.segs {
		sb.WriteByte('/')
		if p.isParam(i) {
			sb.WriteString("{}")
		} else {
			sb.WriteString(s)
		}
	}
	return sb.String()
}
