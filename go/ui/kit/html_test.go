package kit

import (
	"context"
	"io"
	"os"
	"strings"
	"testing"

	"github.com/a-h/templ"
	"golang.org/x/net/html"
	"golang.org/x/net/html/atom"
)

// render renders c (with children as its default slot, when given).
func render(t testing.TB, c templ.Component, children templ.Component) string {
	t.Helper()
	ctx := context.Background()
	if children != nil {
		ctx = templ.WithChildren(ctx, children)
	}
	var sb strings.Builder
	if err := c.Render(ctx, &sb); err != nil {
		t.Fatalf("render: %v", err)
	}
	return sb.String()
}

// parse parses markup as the children of <body>.
func parse(t testing.TB, markup string) []*html.Node {
	t.Helper()
	nodes, err := html.ParseFragment(strings.NewReader(markup), &html.Node{Type: html.ElementNode, Data: "body", DataAtom: atom.Body})
	if err != nil {
		t.Fatalf("parse: %v", err)
	}
	return nodes
}

// walk calls fn for every node under (and including) the given ones.
func walk(nodes []*html.Node, fn func(*html.Node)) {
	for _, n := range nodes {
		fn(n)
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			walk([]*html.Node{c}, fn)
		}
	}
}

// find returns the elements with this tag name.
func find(nodes []*html.Node, tag string) []*html.Node {
	out := []*html.Node{}
	walk(nodes, func(n *html.Node) {
		if n.Type == html.ElementNode && n.Data == tag {
			out = append(out, n)
		}
	})
	return out
}

func attr(n *html.Node, key string) string {
	for _, a := range n.Attr {
		if a.Key == key {
			return a.Val
		}
	}
	return ""
}

func hasAttr(n *html.Node, key string) bool {
	for _, a := range n.Attr {
		if a.Key == key {
			return true
		}
	}
	return false
}

func text(n *html.Node) string {
	var sb strings.Builder
	walk([]*html.Node{n}, func(c *html.Node) {
		if c.Type == html.TextNode {
			sb.WriteString(c.Data)
		}
	})
	return sb.String()
}

// danger counts what a payload would add to a page: script and img elements,
// event-handler attributes (on*) and a stray attribute named x.
func danger(markup string, t testing.TB) int { return dangerOf(markup, t, true) }

// dangerOf is danger with img elements counted or not: markdown.md keeps image
// elements (their attributes are filtered), so the sweep does not count them
// for that component.
func dangerOf(markup string, t testing.TB, images bool) int {
	n := 0
	walk(parse(t, markup), func(e *html.Node) {
		if e.Type != html.ElementNode {
			return
		}
		if e.Data == "script" || (images && e.Data == "img") {
			n++
		}
		for _, a := range e.Attr {
			if strings.HasPrefix(a.Key, "on") || a.Key == "x" {
				n++
			}
		}
	})
	return n
}

// refused runs fn and returns the panic message, "" when it did not panic.
func refused(fn func()) (msg string) {
	defer func() {
		if r := recover(); r != nil {
			if s, ok := r.(string); ok {
				msg = s
			} else {
				msg = "non-string panic"
			}
		}
	}()
	fn()
	return ""
}

func readFile(t testing.TB, name string) string {
	t.Helper()
	b, err := os.ReadFile(name)
	if err != nil {
		t.Fatal(err)
	}
	return string(b)
}

// plain is escaped text as a slot.
func plain(s string) templ.Component {
	return templ.ComponentFunc(func(_ context.Context, w io.Writer) error {
		_, err := io.WriteString(w, templ.EscapeString(s))
		return err
	})
}
