package config

import (
	"fmt"
	"math"
	"math/big"
	"os"
	"regexp"
	"slices"
	"strconv"
	"strings"

	"go.yaml.in/yaml/v3"

	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// leaf is one flattened value from a file: nil for an explicit null or an empty mapping, a
// string for a scalar (its text), a []any for a list.
type leaf struct {
	value  any
	source string
}

// leafSet is the merged file contents in first-seen order (a key replaced by a later file keeps
// its first position, as a Java LinkedHashMap does).
type leafSet struct {
	order []string
	m     map[string]leaf
}

func newLeafSet() *leafSet { return &leafSet{m: map[string]leaf{}} }

func (l *leafSet) put(key string, v leaf) {
	if _, ok := l.m[key]; !ok {
		l.order = append(l.order, key)
	}
	l.m[key] = v
}

// orderedMap is a YAML mapping with its keys in file order.
type orderedMap struct {
	keys []string
	m    map[string]any
}

func (o *orderedMap) set(k string, v any) {
	if _, ok := o.m[k]; !ok {
		o.keys = append(o.keys, k)
	}
	o.m[k] = v
}

// maxYAMLDepth bounds aliases and nesting, so a self-referencing document ends.
const maxYAMLDepth = 200

// readYAMLFile reads one file if it exists, flattening it into into. It returns the problem, if
// any, in the form the loader reports.
func readYAMLFile(path string, into *leafSet) (Problem, bool) {
	st, err := os.Stat(path)
	if err != nil || !st.Mode().IsRegular() {
		return Problem{}, false
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return Problem{Source: path, Message: "cannot read file: " + err.Error()}, true
	}
	var doc yaml.Node
	if err := yaml.Unmarshal(data, &doc); err != nil {
		return Problem{Source: path, Message: "invalid YAML: " + firstLine(err.Error())}, true
	}
	root := &doc
	if root.Kind == yaml.DocumentNode {
		if len(root.Content) == 0 {
			return Problem{}, false
		}
		root = root.Content[0]
	}
	if root.Kind == 0 {
		return Problem{}, false
	}
	tree, err := convert(root, 0)
	if err != nil {
		return Problem{Source: path, Message: "invalid YAML: " + firstLine(err.Error())}, true
	}
	switch t := tree.(type) {
	case nil:
		return Problem{}, false
	case *orderedMap:
		flatten("", t, path, into)
		return Problem{}, false
	}
	return Problem{Source: path, Message: "the top level must be a mapping"}, true
}

func firstLine(s string) string {
	line, _, _ := strings.Cut(s, "\n")
	return line
}

func flatten(prefix string, m *orderedMap, source string, into *leafSet) {
	for _, k := range m.keys {
		path := k
		if prefix != "" {
			path = prefix + "." + k
		}
		put(path, m.m[k], source, into)
	}
}

func put(path string, v any, source string, into *leafSet) {
	if nested, ok := v.(*orderedMap); ok {
		if len(nested.keys) > 0 {
			flatten(path, nested, source, into)
			return
		}
		v = nil
	}
	if _, exists := into.m[path]; v == nil && exists {
		return // a null never hides a value from an earlier file
	}
	into.put(path, leaf{value: v, source: source})
}

// convert turns a node into nil, a string (a scalar's text), a []any or an *orderedMap.
func convert(n *yaml.Node, depth int) (any, error) {
	if depth > maxYAMLDepth {
		return nil, fmt.Errorf("yaml: line %d: document is nested too deeply", n.Line)
	}
	switch n.Kind {
	case yaml.AliasNode:
		return convert(n.Alias, depth+1)
	case yaml.ScalarNode:
		return scalarValue(n), nil
	case yaml.SequenceNode:
		list := make([]any, 0, len(n.Content))
		for _, c := range n.Content {
			v, err := convert(c, depth+1)
			if err != nil {
				return nil, err
			}
			list = append(list, v)
		}
		return list, nil
	case yaml.MappingNode:
		return convertMapping(n, depth)
	case yaml.DocumentNode:
		if len(n.Content) == 0 {
			return nil, nil //nolint:nilnil // an empty document is a null value
		}
		return convert(n.Content[0], depth+1)
	}
	return nil, fmt.Errorf("yaml: line %d: unsupported node", n.Line)
}

func resolveAlias(n *yaml.Node) *yaml.Node {
	for range maxYAMLDepth {
		if n.Kind != yaml.AliasNode {
			return n
		}
		n = n.Alias
	}
	return n
}

func convertMapping(n *yaml.Node, depth int) (any, error) {
	out := &orderedMap{m: map[string]any{}}
	// merge keys first: their entries have the lowest precedence
	for i := 0; i+1 < len(n.Content); i += 2 {
		k, v := n.Content[i], n.Content[i+1]
		if k.Kind != yaml.ScalarNode || k.Tag != "!!merge" || k.Style&yaml.TaggedStyle != 0 {
			continue
		}
		sources := []*yaml.Node{resolveAlias(v)}
		if sources[0].Kind == yaml.SequenceNode {
			sources = nil
			for _, c := range slices.Backward(v.Content) { // earlier maps win
				sources = append(sources, resolveAlias(c))
			}
		}
		for _, s := range sources {
			if s.Kind != yaml.MappingNode {
				return nil, fmt.Errorf("yaml: line %d: a merge key needs a mapping", s.Line)
			}
			m, err := convertMapping(s, depth+1)
			if err != nil {
				return nil, err
			}
			if om, ok := m.(*orderedMap); ok {
				for _, key := range om.keys {
					out.set(key, om.m[key])
				}
			}
		}
	}
	seen := map[string]int{}
	for i := 0; i+1 < len(n.Content); i += 2 {
		k, v := n.Content[i], n.Content[i+1]
		if k.Kind == yaml.ScalarNode && k.Tag == "!!merge" && k.Style&yaml.TaggedStyle == 0 {
			continue
		}
		k = resolveAlias(k)
		if k.Kind != yaml.ScalarNode {
			return nil, fmt.Errorf("yaml: line %d: a mapping key must be a scalar", k.Line)
		}
		key := "null"
		if t, ok := scalarValue(k).(string); ok {
			key = t
		}
		if first, dup := seen[key]; dup {
			return nil, fmt.Errorf("yaml: line %d: mapping key %q already defined at line %d", k.Line, key, first)
		}
		seen[key] = k.Line
		val, err := convert(v, depth+1)
		if err != nil {
			return nil, err
		}
		out.set(key, val)
	}
	return out, nil
}

var (
	intRE   = regexp.MustCompile(`^[-+]?[0-9]+$`)
	octRE   = regexp.MustCompile(`^0o[0-7]+$`)
	hexRE   = regexp.MustCompile(`^0x[0-9a-fA-F]+$`)
	floatRE = regexp.MustCompile(`^[-+]?(\.[0-9]+|[0-9]+(\.[0-9]*)?)([eE][-+]?[0-9]+)?$`)
	infRE   = regexp.MustCompile(`^[-+]?\.(inf|Inf|INF)$`)
	nanRE   = regexp.MustCompile(`^\.(nan|NaN|NAN)$`)
)

// scalarValue is the value of a scalar node: nil for null, else the scalar's text as the Java
// reader prints it (a String as is, a Boolean true/false, an integer in decimal, a float as
// Double.toString).
func scalarValue(n *yaml.Node) any {
	const quoted = yaml.DoubleQuotedStyle | yaml.SingleQuotedStyle | yaml.LiteralStyle | yaml.FoldedStyle
	if n.Style&yaml.TaggedStyle != 0 {
		switch n.ShortTag() {
		case "!!null":
			return nil
		case "!!int":
			if t, ok := intText(n.Value); ok {
				return t
			}
		case "!!float":
			if t, ok := floatText(n.Value); ok {
				return t
			}
		case "!!bool":
			if t, ok := boolText(n.Value); ok {
				return t
			}
		}
		return n.Value
	}
	if n.Style&quoted != 0 {
		return n.Value
	}
	v := n.Value
	switch v {
	case "", "~", "null", "Null", "NULL":
		return nil
	}
	if t, ok := boolText(v); ok {
		return t
	}
	if t, ok := intText(v); ok {
		return t
	}
	if t, ok := floatText(v); ok {
		return t
	}
	return v
}

func boolText(v string) (string, bool) {
	switch v {
	case "true", "True", "TRUE":
		return "true", true
	case "false", "False", "FALSE":
		return "false", true
	}
	return "", false
}

// intText is the decimal text of a YAML 1.2 core-schema integer of any size.
func intText(v string) (string, bool) {
	var digits string
	base := 10
	switch {
	case intRE.MatchString(v):
		digits = strings.TrimPrefix(v, "+")
	case octRE.MatchString(v):
		digits, base = v[2:], 8
	case hexRE.MatchString(v):
		digits, base = v[2:], 16
	default:
		return "", false
	}
	n, ok := new(big.Int).SetString(digits, base)
	if !ok {
		return "", false
	}
	return n.String(), true
}

// floatText is Java's Double.toString of a YAML 1.2 core-schema float.
func floatText(v string) (string, bool) {
	switch {
	case infRE.MatchString(v):
		if v[0] == '-' {
			return javafmt.Double(math.Inf(-1)), true
		}
		return javafmt.Double(math.Inf(1)), true
	case nanRE.MatchString(v):
		return javafmt.Double(math.NaN()), true
	case floatRE.MatchString(v):
		f, _ := strconv.ParseFloat(v, 64) // out of range gives +-Inf, as Double.valueOf does
		return javafmt.Double(f), true
	}
	return "", false
}
