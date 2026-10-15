package httpfw

import (
	"slices"
	"strings"
)

// compiled is a route ready to run.
type compiled struct {
	pattern         routePattern
	registrant      string
	requiresContext bool
	handler         HandlerFunc
}

// router is a trie of path segments. Literals beat parameters at the same position, the first
// difference deciding (so /orders/new beats /orders/{id}). Unlike ServeMux it never refuses two
// patterns that overlap with neither more specific, never redirects and never cleans a path
// (HTTP contract section 2).
type router struct{ root *node }

type node struct {
	literals map[string]*node
	param    *node
	routes   map[string]*compiled
}

func newNode() *node { return &node{literals: map[string]*node{}, routes: map[string]*compiled{}} }

func newRouter() *router { return &router{root: newNode()} }

func (rt *router) add(c *compiled) {
	n := rt.root
	for i, seg := range c.pattern.segs {
		if c.pattern.isParam(i) {
			if n.param == nil {
				n.param = newNode()
			}
			n = n.param
			continue
		}
		next, ok := n.literals[seg]
		if !ok {
			next = newNode()
			n.literals[seg] = next
		}
		n = next
	}
	n.routes[c.pattern.method] = c
}

type matchKind int

const (
	notFound matchKind = iota
	found
	methodNotAllowed
	optionsAnswer
)

type match struct {
	kind    matchKind
	route   *compiled
	params  map[string]string
	allowed []string // sorted, for methodNotAllowed and optionsAnswer
}

type candidate struct {
	n      *node
	values []string
}

// match matches decoded segments. Among every route whose shape fits, the one that is most literal
// at the first differing position wins; if none has the method, the result lists the methods the
// path does have.
func (rt *router) match(method string, segments []string) match {
	var cands []candidate
	collect(rt.root, segments, 0, nil, &cands)
	if len(cands) == 0 {
		return match{kind: notFound}
	}
	for _, c := range cands {
		route := c.n.routes[method]
		if route == nil && method == "HEAD" {
			route = c.n.routes["GET"]
		}
		if route != nil {
			params := make(map[string]string, len(route.pattern.params))
			for i, name := range route.pattern.params {
				params[name] = c.values[i]
			}
			return match{kind: found, route: route, params: params}
		}
	}
	allowed := map[string]bool{"OPTIONS": true}
	for _, c := range cands {
		for m := range c.n.routes {
			allowed[m] = true
		}
	}
	if allowed["GET"] {
		allowed["HEAD"] = true
	}
	list := make([]string, 0, len(allowed))
	for m := range allowed {
		list = append(list, m)
	}
	slices.Sort(list)
	if method == "OPTIONS" {
		return match{kind: optionsAnswer, allowed: list}
	}
	return match{kind: methodNotAllowed, allowed: list}
}

func collect(n *node, segs []string, i int, values []string, out *[]candidate) {
	if i == len(segs) {
		if len(n.routes) > 0 {
			*out = append(*out, candidate{n: n, values: slices.Clone(values)})
		}
		return
	}
	seg := segs[i]
	if lit, ok := n.literals[seg]; ok {
		collect(lit, segs, i+1, values, out)
	}
	if n.param != nil && seg != "" {
		collect(n.param, segs, i+1, append(values, seg), out)
	}
}

func allowHeader(methods []string) string { return strings.Join(methods, ", ") }
