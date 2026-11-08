package push

import (
	"errors"
	"fmt"
	"slices"
	"strings"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/pushrow"
)

// pattern is a topic pattern (push contract 6.3): segments that are literals or placeholders
// ({principal}, {client}, any other {name}). The first segment is a literal application code.
type pattern struct {
	text string
	segs []string // a placeholder keeps its braces
}

// parsePattern checks a pattern: a topic whose segments after the first may be {name} placeholders
// (name [a-z][A-Za-z0-9]*).
func parsePattern(text string) (pattern, error) {
	if len(text) > pushrow.MaxTopicLength {
		return pattern{}, fmt.Errorf("a topic pattern is at most %d characters", pushrow.MaxTopicLength)
	}
	segs := strings.Split(text, ":")
	if len(segs) < 2 || !pushrow.IsApplication(segs[0]) {
		return pattern{}, fmt.Errorf("a topic pattern is {application}:{segment}(:{segment})* with a literal application: %q", text)
	}
	for _, s := range segs[1:] {
		if !pushrow.IsSegment(s) && !isPlaceholder(s) {
			return pattern{}, fmt.Errorf("a pattern segment is [0-9A-Za-z_.-]+ or {name}: %q", text)
		}
	}
	return pattern{text: text, segs: segs}, nil
}

func isPlaceholder(s string) bool {
	if len(s) < 3 || s[0] != '{' || s[len(s)-1] != '}' {
		return false
	}
	name := s[1 : len(s)-1]
	if name[0] < 'a' || name[0] > 'z' {
		return false
	}
	for i := 1; i < len(name); i++ {
		c := name[i]
		if (c < 'a' || c > 'z') && (c < 'A' || c > 'Z') && (c < '0' || c > '9') {
			return false
		}
	}
	return true
}

func (p pattern) application() string { return p.segs[0] }

func (p pattern) has(placeholder string) bool { return slices.Contains(p.segs, placeholder) }

// matchAny reports whether topic has the pattern's shape, every placeholder matching any one
// segment (a ticket's patterns, 6.4).
func (p pattern) matchAny(topic []string) bool {
	if len(topic) != len(p.segs) {
		return false
	}
	for i, s := range p.segs {
		if !isPlaceholder(s) && s != topic[i] {
			return false
		}
	}
	return true
}

// Rule says who may subscribe to the topics of a pattern without a ticket (push contract 6.3). It is
// data, not a callback, so a gateway can apply it too. Make one with [NewRule] or [NewPublicRule],
// or declare it with [Declared.Topic] and [Declared.PublicTopic].
type Rule struct {
	pat      pattern
	requires []fw.Permission
	public   bool
}

// RuleOption adds to a rule.
type RuleOption func(*ruleOptions)

type ruleOptions struct {
	requires []string
}

// Requires needs the permission in the subscriber's Context. Given more than once, all are needed.
func Requires(permission string) RuleOption {
	return func(o *ruleOptions) { o.requires = append(o.requires, permission) }
}

// ErrOpenRule refuses a rule with neither a {principal} placeholder nor a required permission: a
// topic open to anyone is declared on purpose, with [NewPublicRule].
var ErrOpenRule = errors.New("a push rule needs a {principal} placeholder or Requires(permission); declare a deliberately open topic with PublicTopic")

// NewRule returns a rule on pattern: {principal} must equal the Context's principal, {client} the
// Context's client, any other {name} matches one segment, and every [Requires] permission must be
// held. A rule with neither {principal} nor Requires is refused ([ErrOpenRule]).
func NewRule(text string, opts ...RuleOption) (Rule, error) {
	p, err := parsePattern(text)
	if err != nil {
		return Rule{}, err
	}
	var o ruleOptions
	for _, f := range opts {
		f(&o)
	}
	r := Rule{pat: p}
	for _, s := range o.requires {
		perm, err := fw.ParsePermission(s)
		if err != nil {
			return Rule{}, fmt.Errorf("push rule %s: %w", text, err)
		}
		r.requires = append(r.requires, perm)
	}
	if len(r.requires) == 0 && !p.has("{principal}") {
		return Rule{}, fmt.Errorf("push rule %s: %w", text, ErrOpenRule)
	}
	return r, nil
}

// NewPublicRule returns a deliberately open rule: anyone may subscribe to its topics, with or
// without credentials. Its placeholders still apply ({principal} needs that principal).
func NewPublicRule(text string) (Rule, error) {
	p, err := parsePattern(text)
	if err != nil {
		return Rule{}, err
	}
	return Rule{pat: p, public: true}, nil
}

// Pattern returns the rule's topic pattern.
func (r Rule) Pattern() string { return r.pat.text }

// Required returns the permissions the rule needs.
func (r Rule) Required() []string {
	out := make([]string, len(r.requires))
	for i, p := range r.requires {
		out[i] = p.String()
	}
	return out
}

// Public reports whether the rule is deliberately open.
func (r Rule) Public() bool { return r.public }

// Allows reports whether the rule lets cx subscribe to topic. The zero Context is a request without
// credentials: only a public rule (without {principal} or {client}) can allow it.
func (r Rule) Allows(cx fw.Context, topic string) bool {
	if r.pat.segs == nil || (cx.IsZero() && !r.public) {
		return false
	}
	segs := strings.Split(topic, ":")
	if len(segs) != len(r.pat.segs) {
		return false
	}
	for i, s := range r.pat.segs {
		switch {
		case s == "{principal}":
			p, ok := cx.Principal()
			if !ok || p.String() != segs[i] {
				return false
			}
		case s == "{client}":
			if cx.IsZero() || cx.Client().String() != segs[i] {
				return false
			}
		case isPlaceholder(s):
		case s != segs[i]:
			return false
		}
	}
	for _, p := range r.requires {
		if !cx.HasPermission(p) {
			return false
		}
	}
	return true
}
