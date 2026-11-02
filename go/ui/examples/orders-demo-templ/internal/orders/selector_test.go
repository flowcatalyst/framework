package orders_test

import (
	"regexp"
	"strconv"
	"strings"
	"testing"

	"golang.org/x/net/html"
)

// A small CSS selector engine over x/net/html, enough for these tests: type, #id, .class, [attr],
// [attr=v], [attr$=v], :first-child, :nth-child(n), :not(<simple selector>), the descendant and
// child (>) combinators and comma lists. The module has no goquery and the tests add no dependency.

type simple struct {
	tag     string
	tests   []func(*html.Node) bool
	combine byte // how this compound relates to the one on its left: ' ' or '>'
}

var (
	tokenAttr = regexp.MustCompile(`^\[([A-Za-z0-9_:.-]+)(?:(\$?=)(?:'([^']*)'|"([^"]*)"|([^\]]*)))?\]`)
	tokenNth  = regexp.MustCompile(`^:nth-child\((\d+)\)`)
	tokenName = regexp.MustCompile(`^[A-Za-z0-9_-]+`)
)

func compile(t *testing.T, selector string) [][]simple {
	t.Helper()
	var out [][]simple
	for _, part := range splitTop(selector) {
		out = append(out, compileChain(t, strings.TrimSpace(part)))
	}
	return out
}

// splitTop splits on commas outside brackets and parentheses.
func splitTop(s string) []string {
	var parts []string
	depth, start := 0, 0
	for i, c := range s {
		switch c {
		case '[', '(':
			depth++
		case ']', ')':
			depth--
		case ',':
			if depth == 0 {
				parts = append(parts, s[start:i])
				start = i + 1
			}
		}
	}
	return append(parts, s[start:])
}

func compileChain(t *testing.T, s string) []simple {
	t.Helper()
	var chain []simple
	combine := byte(' ')
	for s != "" {
		switch s[0] {
		case ' ':
			s = s[1:]
		case '>':
			combine, s = '>', s[1:]
		default:
			var c simple
			c, s = compileCompound(t, s)
			c.combine = combine
			combine = ' '
			chain = append(chain, c)
		}
	}
	return chain
}

func compileCompound(t *testing.T, s string) (simple, string) {
	t.Helper()
	var c simple
	if m := tokenName.FindString(s); m != "" {
		c.tag, s = m, s[len(m):]
	}
	for s != "" && s[0] != ' ' && s[0] != '>' {
		switch {
		case s[0] == '#':
			id := tokenName.FindString(s[1:])
			c.tests = append(c.tests, func(n *html.Node) bool { return attrOf(n, "id") == id })
			s = s[1+len(id):]
		case s[0] == '.':
			class := tokenName.FindString(s[1:])
			c.tests = append(c.tests, func(n *html.Node) bool { return hasClass(n, class) })
			s = s[1+len(class):]
		case s[0] == '[':
			m := tokenAttr.FindStringSubmatch(s)
			if m == nil {
				t.Fatalf("selector: bad attribute test in %q", s)
			}
			name, op, want := m[1], m[2], m[3]+m[4]+m[5]
			c.tests = append(c.tests, func(n *html.Node) bool {
				v, ok := lookup(n, name)
				switch op {
				case "":
					return ok
				case "=":
					return ok && v == want
				default:
					return ok && strings.HasSuffix(v, want)
				}
			})
			s = s[len(m[0]):]
		case strings.HasPrefix(s, ":first-child"):
			c.tests = append(c.tests, func(n *html.Node) bool { return position(n) == 1 })
			s = s[len(":first-child"):]
		case strings.HasPrefix(s, ":nth-child"):
			m := tokenNth.FindStringSubmatch(s)
			if m == nil {
				t.Fatalf("selector: bad :nth-child in %q", s)
			}
			want, _ := strconv.Atoi(m[1])
			c.tests = append(c.tests, func(n *html.Node) bool { return position(n) == want })
			s = s[len(m[0]):]
		case strings.HasPrefix(s, ":not("):
			end := strings.IndexByte(s, ')')
			inner, _ := compileCompound(t, s[len(":not("):end])
			c.tests = append(c.tests, func(n *html.Node) bool { return !inner.matches(n) })
			s = s[end+1:]
		default:
			t.Fatalf("selector: cannot read %q", s)
		}
	}
	return c, s
}

func (c simple) matches(n *html.Node) bool {
	if n.Type != html.ElementNode || (c.tag != "" && n.Data != c.tag) {
		return false
	}
	for _, f := range c.tests {
		if !f(n) {
			return false
		}
	}
	return true
}

// position is the 1-based index of an element among its parent's element children.
func position(n *html.Node) int {
	i := 0
	for s := n; s != nil; s = s.PrevSibling {
		if s.Type == html.ElementNode {
			i++
		}
	}
	return i
}

func lookup(n *html.Node, name string) (string, bool) {
	for _, a := range n.Attr {
		if a.Key == name {
			return a.Val, true
		}
	}
	return "", false
}

func attrOf(n *html.Node, name string) string {
	v, _ := lookup(n, name)
	return v
}

func hasClass(n *html.Node, class string) bool {
	for f := range strings.FieldsSeq(attrOf(n, "class")) {
		if f == class {
			return true
		}
	}
	return false
}

// matchChain reports whether n matches chain[:len] with the last compound on n itself.
func matchChain(chain []simple, n *html.Node) bool {
	last := len(chain) - 1
	if !chain[last].matches(n) {
		return false
	}
	if last == 0 {
		return true
	}
	rest := chain[:last]
	switch chain[last].combine {
	case '>':
		return n.Parent != nil && matchChain(rest, n.Parent)
	default:
		for p := n.Parent; p != nil; p = p.Parent {
			if matchChain(rest, p) {
				return true
			}
		}
		return false
	}
}

// selectAll returns the elements under root that match the selector, in document order.
func selectAll(t *testing.T, root *html.Node, selector string) []*html.Node {
	t.Helper()
	chains := compile(t, selector)
	out := []*html.Node{}
	var walk func(*html.Node)
	walk = func(n *html.Node) {
		for _, c := range chains {
			if matchChain(c, n) {
				out = append(out, n)
				break
			}
		}
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk(c)
		}
	}
	for c := root.FirstChild; c != nil; c = c.NextSibling {
		walk(c)
	}
	return out
}

// textOf is the text of the elements, whitespace collapsed, joined by a space.
func textOf(nodes ...*html.Node) string {
	var parts []string
	for _, n := range nodes {
		var b strings.Builder
		var walk func(*html.Node)
		walk = func(x *html.Node) {
			if x.Type == html.TextNode {
				b.WriteString(x.Data)
			}
			for c := x.FirstChild; c != nil; c = c.NextSibling {
				walk(c)
			}
		}
		walk(n)
		parts = append(parts, strings.Join(strings.Fields(b.String()), " "))
	}
	return strings.Join(parts, " ")
}
