package config

import (
	"errors"
	"path/filepath"
	"strings"
)

// loader holds the merged sources while one Load runs.
type loader struct {
	env      map[string]string
	rules    KeyRules
	leaves   *leafSet
	problems []Problem
	statuses []SettingStatus
}

func load(src Sources, settings []Declared, sections []Section, rules KeyRules) (*Values, []Problem) {
	env := src.Env
	if env == nil {
		env = Environment()
	}
	dir := src.Dir
	if dir == "" {
		dir = "config"
	}
	environment := env["APP_ENV"]
	if isJavaBlank(environment) {
		environment = "local"
	}

	l := &loader{env: env, leaves: newLeafSet(), rules: rules}
	names := []string{"application.yaml", "application-" + environment + ".yaml"}
	if environment == "local" || environment == "test" {
		names = append(names, "secrets.local.yaml")
	}
	for _, name := range names {
		if p, bad := readYAMLFile(filepath.Join(dir, name), l.leaves); bad {
			l.problems = append(l.problems, p)
		}
	}

	values := map[string]any{}
	declaredBy := map[string]string{}
	for _, d := range settings {
		key := d.Setting.Key()
		if why := d.Setting.invalid(); why != "" {
			l.problems = append(l.problems, Problem{Module: d.Module, Key: key, Message: why})
			continue
		}
		if earlier, dup := declaredBy[key]; dup {
			l.problems = append(l.problems, Problem{Module: d.Module, Key: key, Message: "declared twice (first by module '" + earlier + "')"})
			continue
		}
		declaredBy[key] = d.Module
		l.resolveSetting(d.Module, d.Setting, values)
	}

	l.checkEnvironmentNames(settings)
	l.checkKeyRules(declaredBy)

	instances := map[string]Instances{}
	for _, sec := range sections {
		instances[sec.Path] = l.checkSection(sec)
	}

	if len(l.problems) > 0 {
		return nil, l.problems
	}
	return &Values{environment: environment, values: values, instances: instances, statuses: l.statuses}, nil
}

// find returns the raw value of a full key: the environment variable derived from the key, else
// the file value. found is false when neither has one.
func (l *loader) find(key string) (raw any, source string, found bool) {
	name := EnvName(key)
	if v, ok := l.env[name]; ok {
		return v, "environment variable " + name, true
	}
	if lf, ok := l.leaves.m[key]; ok && lf.value != nil {
		return lf.value, lf.source, true
	}
	return nil, "", false
}

// sourceOf is where the winning value of a key came from, "not set" when there is none.
func (l *loader) sourceOf(key string) string {
	if _, src, ok := l.find(key); ok {
		return src
	}
	return "not set"
}

// read resolves one declaration at the full key (the declared key itself for a setting, the
// instance path plus the relative key in an instance). It returns the parsed value, whether
// there is one, and the problem message and source when the text does not parse.
func (l *loader) read(key string, d Decl) (value any, ok bool, source, failure string) {
	raw, src, found := l.find(key)
	if found {
		v, err := d.resolve(raw)
		if err != nil {
			return nil, false, src, failureText(d, raw, err)
		}
		return v, true, src, ""
	}
	if v, has := d.defaultValue(); has {
		if msg := d.checkValue(v); msg != "" {
			return nil, false, "", msg
		}
		return v, true, "", ""
	}
	return nil, false, "", ""
}

func failureText(d Decl, raw any, err error) string {
	if ce, ok := errors.AsType[*checkError](err); ok {
		return ce.msg
	}
	expected := err.Error()
	if fe, ok := errors.AsType[*formatError](err); ok {
		expected = fe.expected
	}
	msg := "expected " + expected
	if d.isSecret() {
		return msg
	}
	if _, isList := raw.([]any); isList {
		return msg + ", found 'a list'"
	}
	text, _ := raw.(string)
	return msg + ", found '" + text + "'"
}

func (l *loader) resolveSetting(module string, d Decl, values map[string]any) {
	key := d.Key()
	v, ok, source, failure := l.read(key, d)
	switch {
	case failure != "":
		l.problems = append(l.problems, Problem{Module: module, Key: key, Source: source, Message: failure})
	case ok:
		values[key] = v
		origin := source
		if origin == "" {
			origin = "default"
		}
		l.statuses = append(l.statuses, newStatus(module, d, v, true, origin))
	case d.isRequired():
		hint := "config/application.yaml"
		if d.isSecret() {
			hint += ", config/secrets.local.yaml (local and test only)"
		}
		l.problems = append(l.problems, Problem{
			Module: module, Key: key,
			Message: "missing required setting; set it in " + hint + " or as environment variable " + EnvName(key),
		})
	default:
		l.statuses = append(l.statuses, newStatus(module, d, nil, false, ""))
	}
}

// checkEnvironmentNames fails when two different keys read the same environment variable: every
// declared key, then the file keys that have an "instances" segment.
func (l *loader) checkEnvironmentNames(settings []Declared) {
	type claim struct{ key, label string }
	claimed := map[string]claim{}
	try := func(key, label, module string) {
		name := EnvName(key)
		first, ok := claimed[name]
		if !ok {
			claimed[name] = claim{key, label}
			return
		}
		if first.key != key {
			l.problems = append(l.problems, Problem{
				Module: module, Key: key,
				Message: "reads environment variable " + name + ", which key '" + first.key + "' (" + first.label +
					") reads too; keys may contain '_' and both '.' and '-' become '_', so rename one of them",
			})
		}
	}
	for _, d := range settings {
		if d.Setting.invalid() != "" {
			continue
		}
		try(d.Setting.Key(), "declared by module '"+d.Module+"'", d.Module)
	}
	for _, key := range l.leaves.order {
		if strings.HasPrefix(key, "instances.") || strings.Contains(key, ".instances.") {
			try(key, "an instance setting in "+l.leaves.m[key].source, "")
		}
	}
}
