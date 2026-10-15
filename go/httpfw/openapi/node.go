package openapi

import (
	"fmt"
	"slices"
	"strconv"
	"strings"
	"unicode/utf8"
)

// node is an ordered JSON object: keys keep the order they were first set in, which is how the
// emitter fixes the order of every object (OpenAPI contract section 4.1).
type node struct {
	keys []string
	vals map[string]any
}

func newNode() *node { return &node{vals: map[string]any{}} }

// set stores v under k (a replaced key keeps its position) and returns n.
func (n *node) set(k string, v any) *node {
	if _, ok := n.vals[k]; !ok {
		n.keys = append(n.keys, k)
	}
	n.vals[k] = v
	return n
}

func (n *node) get(k string) (any, bool) {
	if n == nil {
		return nil, false
	}
	v, ok := n.vals[k]
	return v, ok
}

func (n *node) has(k string) bool { _, ok := n.get(k); return ok }

func (n *node) empty() bool { return n == nil || len(n.keys) == 0 }

func (n *node) clone() *node {
	c := newNode()
	for _, k := range n.keys {
		c.set(k, n.vals[k])
	}
	return c
}

// number is a JSON number kept as its text, so a bound prints exactly as written.
type number string

// typed returns {"type": t}.
func typed(t string) *node { return newNode().set("type", t) }

// formatted returns {"type": t, "format": f}.
func formatted(t, f string) *node { return typed(t).set("format", f) }

// write prints a tree of nodes, []any, strings, bools, int64s and numbers exactly as JavaScript's
// JSON.stringify(value, null, 2) does, plus a final newline (OpenAPI contract section 4.1): two-space
// indentation, "key": value, one element per line, empty containers as {} and [], only the quote,
// the backslash and control characters escaped.
func write(v any) []byte {
	var sb strings.Builder
	writeValue(&sb, v, 0)
	sb.WriteByte('\n')
	return []byte(sb.String())
}

func writeValue(sb *strings.Builder, v any, depth int) {
	switch x := v.(type) {
	case *node:
		if x == nil || x.empty() {
			sb.WriteString("{}")
			return
		}
		sb.WriteByte('{')
		for i, k := range x.keys {
			if i > 0 {
				sb.WriteByte(',')
			}
			newline(sb, depth+1)
			writeString(sb, k)
			sb.WriteString(": ")
			writeValue(sb, x.vals[k], depth+1)
		}
		newline(sb, depth)
		sb.WriteByte('}')
	case []any:
		if len(x) == 0 {
			sb.WriteString("[]")
			return
		}
		sb.WriteByte('[')
		for i, e := range x {
			if i > 0 {
				sb.WriteByte(',')
			}
			newline(sb, depth+1)
			writeValue(sb, e, depth+1)
		}
		newline(sb, depth)
		sb.WriteByte(']')
	case string:
		writeString(sb, x)
	case bool:
		sb.WriteString(strconv.FormatBool(x))
	case int:
		sb.WriteString(strconv.Itoa(x))
	case int64:
		sb.WriteString(strconv.FormatInt(x, 10))
	case number:
		sb.WriteString(string(x))
	default:
		panic(fmt.Sprintf("openapi: not a JSON value: %T", v))
	}
}

func newline(sb *strings.Builder, depth int) {
	sb.WriteByte('\n')
	for range depth {
		sb.WriteString("  ")
	}
}

func writeString(sb *strings.Builder, s string) {
	sb.WriteByte('"')
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		switch {
		case r == '"':
			sb.WriteString(`\"`)
		case r == '\\':
			sb.WriteString(`\\`)
		case r == '\b':
			sb.WriteString(`\b`)
		case r == '\f':
			sb.WriteString(`\f`)
		case r == '\n':
			sb.WriteString(`\n`)
		case r == '\r':
			sb.WriteString(`\r`)
		case r == '\t':
			sb.WriteString(`\t`)
		case r < 0x20:
			sb.WriteString(`\u00`)
			sb.WriteByte("0123456789abcdef"[r>>4])
			sb.WriteByte("0123456789abcdef"[r&0xf])
		case r == utf8.RuneError && size == 1:
			sb.WriteString(`\ufffd`) // invalid UTF-8 cannot be written as it is
		default:
			sb.WriteString(s[i : i+size])
		}
		i += size
	}
	sb.WriteByte('"')
}

// sortedStrings returns a sorted copy (Go compares strings by their UTF-8 bytes, the order the
// contract fixes for paths and component names).
func sortedStrings(in []string) []string {
	out := slices.Clone(in)
	slices.Sort(out)
	return out
}
