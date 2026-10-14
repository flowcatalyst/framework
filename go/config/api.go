// Package config is the framework's configuration (core contract section 4): settings declared in
// code with a type, an optional default and whether they are required; values from the
// declaration's default, config/application.yaml, config/application-{env}.yaml,
// config/secrets.local.yaml (only when APP_ENV is local or test) and environment variables, in
// that order; every problem reported at once; and instance sections (core contract section 2)
// whose driver keys are declared too, so they are checked when the application is configured.
//
// The rules and the problem texts are the Java framework's, checked by
// conformance/config/config.json. One difference is allowed: a YAML file with a duplicate key is
// refused here (go.yaml.in/yaml/v3), where the Java reader keeps the last value.
package config

import (
	"fmt"
	"net/url"
	"os"
	"strings"
	"time"
)

// Setting declares one configuration value of type T: its key, type, optional default, whether
// it is required, and a description for tooling. A Setting is an immutable descriptor; package
// level variables may hold them. Settings with a relative key ("url", "pool.max") describe the
// keys of an instance section's driver ([Driver]).
type Setting[T any] struct {
	key         string
	description string
	typ         settingType[T]
	def         T
	hasDefault  bool
	required    bool
	problem     string // set when the key or the secret rule is violated; Load reports it
}

// String declares a text setting.
func String(key, description string) Setting[string] { return newSetting(key, description, stringType) }

// Int declares a 32-bit integer setting ("an integer").
func Int(key, description string) Setting[int] { return newSetting(key, description, intType) }

// Int64 declares a 64-bit integer setting ("a long integer").
func Int64(key, description string) Setting[int64] { return newSetting(key, description, int64Type) }

// Bool declares a boolean setting: exactly true or false.
func Bool(key, description string) Setting[bool] { return newSetting(key, description, boolType) }

// Duration declares a duration setting: 500ms, 30s, 5m, 2h, 1d or ISO-8601 (PT30S).
func Duration(key, description string) Setting[time.Duration] {
	return newSetting(key, description, durationType)
}

// URL declares a URI setting.
func URL(key, description string) Setting[url.URL] { return newSetting(key, description, urlType) }

// StringList declares a list of strings: a YAML list, or comma-separated text.
func StringList(key, description string) Setting[[]string] {
	return newSetting(key, description, stringListType)
}

// SecretValue declares a secret: its value is a [Secret], never printed, and it cannot have a
// default.
func SecretValue(key, description string) Setting[Secret] {
	return newSetting(key, description, secretType)
}

// Enum declares a setting whose value is one of values; the text is matched ignoring case, with
// '-' read as '_' (slow-start matches SLOW_START).
func Enum[E ~string](key, description string, values ...E) Setting[E] {
	return newSetting(key, description, enumType(values))
}

// WithDefault returns a copy with a default value.
func (s Setting[T]) WithDefault(v T) Setting[T] {
	s.def, s.hasDefault = v, true
	if s.typ.secret && s.problem == "" {
		s.problem = "secret setting '" + s.key + "' cannot have a default"
	}
	return s
}

// Required returns a copy that must have a value after every source was read.
func (s Setting[T]) Required() Setting[T] {
	s.required = true
	return s
}

// Key returns the dotted key.
func (s Setting[T]) Key() string { return s.key }

// Description returns the description.
func (s Setting[T]) Description() string { return s.description }

// EnvName returns the environment variable that overrides the setting: the key upper-cased with
// '.' and '-' replaced by '_'.
func (s Setting[T]) EnvName() string { return EnvName(s.key) }

// EnvName returns the environment variable name of a dotted key.
func EnvName(key string) string {
	return strings.NewReplacer(".", "_", "-", "_").Replace(strings.ToUpper(key))
}

// Decl is a setting of any type, as the loader and the driver key schemas see it. Only
// [Setting] implements it.
type Decl interface {
	Key() string
	isSecret() bool
	// invalid is the reason the declaration is refused ("" when it is fine).
	invalid() string
	// resolve parses a raw value: a string, or a []any from a YAML list. The error's text is the
	// expected-type description.
	resolve(raw any) (any, error)
	defaultValue() (any, bool)
	isRequired() bool
	describe() string
}

func (s Setting[T]) isSecret() bool  { return s.typ.secret }
func (s Setting[T]) invalid() string { return s.problem }
func (s Setting[T]) resolve(raw any) (any, error) {
	v, err := s.typ.parse(raw)
	if err != nil {
		return nil, err
	}
	return v, nil
}

func (s Setting[T]) defaultValue() (any, bool) {
	if !s.hasDefault {
		return nil, false
	}
	return s.def, true
}
func (s Setting[T]) isRequired() bool { return s.required }
func (s Setting[T]) describe() string { return s.typ.describe }

// settingType is the parse rule of a setting's type.
type settingType[T any] struct {
	name     string // "string", "integer", ... for tooling
	describe string // the text after "expected " in a problem
	secret   bool
	parse    func(raw any) (T, error)
}

// Declarer receives declarations. fw.Declarer implements it; a capability's Declare function
// passes it to [Declare].
type Declarer interface {
	// DeclareSetting records a setting for the declarer's module.
	DeclareSetting(d Decl)
}

// Source gives the configured values after configuration. fw.Boot implements it, and so does
// [*Values].
type Source interface {
	// ConfigValue returns the value of a declared key, or false when it has none.
	ConfigValue(key string) (any, bool)
}

// Handle is the proof that a setting was declared: only [Declare] makes a non-zero one, and
// reading it after configuration cannot fail for a setting that is required or has a default.
type Handle[T any] struct {
	setting Setting[T]
}

// Declare declares s for the declarer's module and returns its handle.
func Declare[T any](d Declarer, s Setting[T]) Handle[T] {
	d.DeclareSetting(s)
	return Handle[T]{setting: s}
}

// Get returns the setting's value. It panics when the setting has no value (an optional setting
// without a default: use Lookup) or the handle is the zero Handle: both are programming errors.
func (h Handle[T]) Get(src Source) T {
	v, ok := h.Lookup(src)
	if !ok {
		panic("config: setting '" + h.setting.key + "' has no value; it is optional, use Lookup")
	}
	return v
}

// Lookup returns the setting's value, or false when it has none.
func (h Handle[T]) Lookup(src Source) (T, bool) {
	var zero T
	if h.setting.key == "" {
		panic("config: zero Handle; get a Handle from config.Declare")
	}
	x, ok := src.ConfigValue(h.setting.key)
	if !ok {
		return zero, false
	}
	v, ok := x.(T)
	if !ok {
		panic(fmt.Sprintf("config: the value of '%s' is a %T, not the declared type", h.setting.key, x))
	}
	return v, true
}

// Setting returns the declared setting.
func (h Handle[T]) Setting() Setting[T] { return h.setting }

// Declared is one setting declared by one module, as the loader receives them.
type Declared struct {
	Module  string
	Setting Decl
}

// Section declares an instance section (core contract section 2): the capability at Path
// ("locks", "database") reads its instances from Path.instances.<name>, the default instance's
// name from Path.default, and each instance's keys as its driver declares them.
type Section struct {
	// Module is the declaring module, named in problems.
	Module string
	// Path is the section's key ("locks").
	Path string
	// Capability names the capability in problems ("lock store").
	Capability string
	// DefaultDriver is the driver of an instance without a driver key ("" when the key is
	// required: capabilities with a real choice have no default driver).
	DefaultDriver string
	// Drivers are the registered drivers and the keys each reads.
	Drivers []Driver
}

// Driver is one driver of an instance section and the keys of an instance using it, relative to
// the instance ("url", "pool.max"). The "driver" key itself is not listed.
type Driver struct {
	Name string
	Keys []Decl
}

// Sources are where values come from.
type Sources struct {
	// Dir is the config directory (default "config").
	Dir string
	// Env is the environment: an explicit map in tests, else [Environment].
	Env map[string]string
}

// Environment returns the process environment as a map. The one place the framework reads it.
func Environment() map[string]string {
	out := map[string]string{}
	for _, kv := range os.Environ() { //nolint:forbidigo // the one reader of the process environment
		if k, v, ok := strings.Cut(kv, "="); ok && k != "" {
			out[k] = v
		}
	}
	return out
}

// Problem is one configuration problem: module, key and source where known, and the message.
// Its String form is the Java framework's (module 'svc': key 'svc.port' (from
// config/application.yaml): expected an integer, found 'eighty').
type Problem struct {
	Module, Key, Source, Message string
}

// String formats the problem as the Java framework does.
func (p Problem) String() string {
	var sb strings.Builder
	if p.Module != "" {
		sb.WriteString("module '" + p.Module + "': ")
	}
	switch {
	case p.Key != "":
		sb.WriteString("key '" + p.Key + "'")
		if p.Source != "" {
			sb.WriteString(" (from " + p.Source + ")")
		}
		sb.WriteString(": ")
	case p.Source != "":
		sb.WriteString(p.Source + ": ")
	}
	sb.WriteString(p.Message)
	return sb.String()
}

// Load reads every source, resolves every declared setting and checks every instance section.
// It returns the values, or every problem found.
func Load(src Sources, settings []Declared, sections []Section) (*Values, []Problem) {
	return load(src, settings, sections)
}

// Values are the resolved configuration. The methods of a nil *Values (what Load returns beside
// problems) answer as an empty configuration.
type Values struct {
	environment string
	values      map[string]any
	instances   map[string]Instances
}

// Environment returns APP_ENV, "local" when it is absent or blank.
func (v *Values) Environment() string {
	if v == nil {
		return ""
	}
	return v.environment
}

// ConfigValue implements [Source].
func (v *Values) ConfigValue(key string) (any, bool) {
	if v == nil {
		return nil, false
	}
	x, ok := v.values[key]
	return x, ok
}

// Instances returns the checked instances of a declared section.
func (v *Values) Instances(path string) Instances {
	if v == nil {
		return Instances{}
	}
	return v.instances[path]
}

// Instances are the configured instances of one section, checked.
type Instances struct {
	// Path is the section's key.
	Path string
	// Default is the default instance's name, "" when no instance is configured.
	Default string
	// Names are the instances' names in the order of the configuration file.
	Names []string
	// ByName maps each name to its instance.
	ByName map[string]Instance
}

// Instance is one configured instance: its name, driver and the values of its driver's keys.
type Instance struct {
	Name   string
	Driver string
	values map[string]any
	keys   map[string]struct{} // the keys the driver declares
}

// Get returns the value of one of the driver's keys in the instance. It panics when the key has
// no value (an optional key without a default: use Lookup) or was not declared for the driver.
func (s Setting[T]) Get(in Instance) T {
	if _, declared := in.keys[s.key]; !declared {
		panic("config: key '" + s.key + "' is not declared for driver '" + in.Driver + "' of instance '" + in.Name + "'")
	}
	v, ok := s.Lookup(in)
	if !ok {
		panic("config: instance '" + in.Name + "' has no value for '" + s.key + "'; it is optional, use Lookup")
	}
	return v
}

// Lookup returns the value of one of the driver's keys in the instance, or false when it has none
// (or the driver does not declare the key).
func (s Setting[T]) Lookup(in Instance) (T, bool) {
	var zero T
	x, ok := in.values[s.key]
	if !ok {
		return zero, false
	}
	v, ok := x.(T)
	if !ok {
		panic(fmt.Sprintf("config: the value of '%s' in instance '%s' is a %T, not the declared type", s.key, in.Name, x))
	}
	return v, true
}
