// Package htmlnorm turns an HTML fragment into a canonical form so two
// renderings of the same markup compare equal regardless of formatting. It is
// the comparison behind the UI kit conformance tests (package kit) and
// implements the rules written down in the kit's fixtures/README.md, which
// the Java (JTE) implementation follows too:
//
//   - The fragment is parsed with the HTML5 fragment algorithm, in the context
//     of the element that would contain its first element (tbody for <tr>,
//     tr for <td>/<th>, table for <thead>/<tbody>/<tfoot>/<caption>/<colgroup>,
//     select for <option>/<optgroup>, otherwise body), so entities are decoded,
//     attribute quoting and void/self-closing syntax stop mattering.
//   - Comments are dropped.
//   - Attributes are sorted by name. Every attribute value has its whitespace
//     collapsed (runs become one space) and trimmed. Class token ORDER is kept:
//     the specs define the order (BASE + variant + size), so it is compared.
//   - Text has its whitespace collapsed and trimmed; whitespace-only text
//     disappears. (So "<svg/> Older" equals "<svg/>Older": leading and
//     trailing spaces inside a text node are not significant here.)
//
// Canonical output is one node per line, indented by depth, so a failing
// comparison can print a readable line diff.
package htmlnorm

import (
	"fmt"
	"regexp"
	"slices"
	"strings"

	"golang.org/x/net/html"
	"golang.org/x/net/html/atom"
)

var (
	spaces   = regexp.MustCompile(`\s+`)
	firstTag = regexp.MustCompile(`^\s*(?:<!--[\s\S]*?-->\s*)*<([a-zA-Z][a-zA-Z0-9]*)`)
)

// contextFor picks the parsing context for a fragment whose first element is tag.
func contextFor(tag string) *html.Node {
	ctx := atom.Body
	switch strings.ToLower(tag) {
	case "tr":
		ctx = atom.Tbody
	case "td", "th":
		ctx = atom.Tr
	case "thead", "tbody", "tfoot", "caption", "colgroup":
		ctx = atom.Table
	case "col":
		ctx = atom.Colgroup
	case "option", "optgroup":
		ctx = atom.Select
	case "html":
		return nil
	}
	return &html.Node{Type: html.ElementNode, Data: ctx.String(), DataAtom: ctx}
}

// Normalize returns the canonical form of an HTML fragment (or a whole
// document when it starts with <html>/<!doctype>).
func Normalize(fragment string) (string, error) {
	var nodes []*html.Node
	trimmed := strings.TrimSpace(fragment)
	lower := strings.ToLower(trimmed)
	if strings.HasPrefix(lower, "<!doctype") || strings.HasPrefix(lower, "<html") {
		doc, err := html.Parse(strings.NewReader(fragment))
		if err != nil {
			return "", fmt.Errorf("htmlnorm: parse document: %w", err)
		}
		nodes = []*html.Node{doc}
	} else {
		tag := "div"
		if m := firstTag.FindStringSubmatch(fragment); m != nil {
			tag = m[1]
		}
		var err error
		nodes, err = html.ParseFragment(strings.NewReader(fragment), contextFor(tag))
		if err != nil {
			return "", fmt.Errorf("htmlnorm: parse fragment: %w", err)
		}
	}
	var b strings.Builder
	for _, n := range nodes {
		write(&b, n, 0)
	}
	return b.String(), nil
}

// MustNormalize is Normalize for tests; it panics on a parse error (the
// tokenizer is lenient, so this does not happen for real input).
func MustNormalize(fragment string) string {
	s, err := Normalize(fragment)
	if err != nil {
		panic(err)
	}
	return s
}

func collapse(s string) string { return strings.TrimSpace(spaces.ReplaceAllString(s, " ")) }

func write(b *strings.Builder, n *html.Node, depth int) {
	switch n.Type {
	case html.DocumentNode:
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			write(b, c, depth)
		}
	case html.DoctypeNode:
		fmt.Fprintf(b, "%s<!doctype %s>\n", indent(depth), strings.ToLower(n.Data))
	case html.CommentNode:
		// dropped
	case html.TextNode:
		if t := collapse(n.Data); t != "" {
			fmt.Fprintf(b, "%s%s\n", indent(depth), html.EscapeString(t))
		}
	case html.ElementNode:
		attrs := make([]html.Attribute, len(n.Attr))
		copy(attrs, n.Attr)
		slices.SortFunc(attrs, func(a, c html.Attribute) int {
			return strings.Compare(qualified(a), qualified(c))
		})
		b.WriteString(indent(depth))
		b.WriteString("<" + n.Data)
		for _, a := range attrs {
			fmt.Fprintf(b, " %s=%q", qualified(a), collapse(a.Val))
		}
		b.WriteString(">\n")
		for c := n.FirstChild; c != nil; c = c.NextSibling {
			write(b, c, depth+1)
		}
		// <template> content lives in a separate fragment in the spec; the
		// x/net parser keeps it as children, which is what we want to compare.
		fmt.Fprintf(b, "%s</%s>\n", indent(depth), n.Data)
	case html.ErrorNode, html.RawNode:
		fmt.Fprintf(b, "%s%s\n", indent(depth), collapse(n.Data))
	}
}

func qualified(a html.Attribute) string {
	if a.Namespace != "" {
		return a.Namespace + ":" + a.Key
	}
	return a.Key
}

func indent(depth int) string { return strings.Repeat("  ", depth) }

// Diff returns a line diff of two canonical forms ("-" only in want, "+"
// only in got), capped at 40 lines, for test failure messages.
func Diff(want, got string) string {
	wl := strings.Split(strings.TrimRight(want, "\n"), "\n")
	gl := strings.Split(strings.TrimRight(got, "\n"), "\n")
	// Longest common subsequence table.
	lcs := make([][]int, len(wl)+1)
	for i := range lcs {
		lcs[i] = make([]int, len(gl)+1)
	}
	for i := range slices.Backward(wl) {
		for j := range slices.Backward(gl) {
			if wl[i] == gl[j] {
				lcs[i][j] = lcs[i+1][j+1] + 1
			} else {
				lcs[i][j] = max(lcs[i+1][j], lcs[i][j+1])
			}
		}
	}
	var b strings.Builder
	shown := 0
	emit := func(prefix, line string) {
		if shown < 40 {
			fmt.Fprintf(&b, "%s %s\n", prefix, line)
		}
		shown++
	}
	i, j := 0, 0
	for i < len(wl) || j < len(gl) {
		switch {
		case i < len(wl) && j < len(gl) && wl[i] == gl[j]:
			i++
			j++
		case j < len(gl) && (i == len(wl) || lcs[i][j+1] >= lcs[i+1][j]):
			emit("+", gl[j])
			j++
		default:
			emit("-", wl[i])
			i++
		}
	}
	if shown > 40 {
		fmt.Fprintf(&b, "... %d more differing lines\n", shown-40)
	}
	return b.String()
}
