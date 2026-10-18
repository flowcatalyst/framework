package clientsettings

import (
	"fmt"
	"regexp"
)

// keyPattern is a setting key: lower case, starting with a letter, up to 100 characters. \A and \z
// anchor the whole string (a trailing newline is not accepted, as Java's Pattern.matches).
var keyPattern = regexp.MustCompile(`\A[a-z][a-z0-9._-]{0,99}\z`)

// Setting is one per-client setting declared in code (client-settings contract section 2): a key,
// the type T of its value (any type encoding/json/v2 reads and writes: a struct, an enum with its
// own UnmarshalJSON, a string, a number, a slice), a default and an optional rule. Make one with
// [NewSetting]; the zero Setting is invalid.
type Setting[T any] struct {
	key  string
	def  T
	rule func(T) string
	ok   bool
}

// NewSetting declares a setting with its default (what a client that never set it gets). The key
// must match [a-z][a-z0-9._-]{0,99} and be unique within the application (the store cannot tell: two
// definitions with one key would share a value).
func NewSetting[T any](key string, def T) (Setting[T], error) {
	if !keyPattern.MatchString(key) {
		return Setting[T]{}, fmt.Errorf("a client setting key must match [a-z][a-z0-9._-]{0,99}: %q", key)
	}
	return Setting[T]{key: key, def: def, ok: true}, nil
}

// MustSetting is [NewSetting] for a package-level declaration: it panics on an invalid key.
func MustSetting[T any](key string, def T) Setting[T] {
	s, err := NewSetting(key, def)
	if err != nil {
		panic(err)
	}
	return s
}

// WithRule returns the setting with a rule: check returns "" for an acceptable value, else a message
// safe to show a person. The rule runs on every [With] and can be called alone ([Setting.Check]) in a
// use case's Validate. It fails when the default breaks its own rule.
func (s Setting[T]) WithRule(check func(T) string) (Setting[T], error) {
	if msg := check(s.def); msg != "" {
		return Setting[T]{}, fmt.Errorf("the default of client setting %s breaks its own rule: %s", s.key, msg)
	}
	s.rule = check
	return s, nil
}

// Key returns the setting's key.
func (s Setting[T]) Key() string { return s.key }

// Default returns the default value.
func (s Setting[T]) Default() T { return s.def }

// Check runs the rule alone: "" when the value is acceptable or there is no rule, else the message.
func (s Setting[T]) Check(v T) string {
	if s.rule == nil {
		return ""
	}
	return s.rule(v)
}
