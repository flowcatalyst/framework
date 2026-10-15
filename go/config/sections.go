package config

import (
	"slices"
	"strings"
)

// checkSection reads and checks one instance section (core contract section 2).
func (l *loader) checkSection(sec Section) Instances {
	add := func(key, source, msg string) {
		l.problems = append(l.problems, Problem{Module: sec.Module, Key: key, Source: source, Message: msg})
	}
	result := Instances{Path: sec.Path, ByName: map[string]Instance{}}

	var defaultName string
	var hasDefault bool
	if !sec.NoDefault {
		defaultName, hasDefault = l.readString(sec.Path+".default", add)
	}

	// keys directly under the section other than default and instances.*
	prefix := sec.Path + "."
	for _, full := range l.leaves.order {
		rest, ok := strings.CutPrefix(full, prefix)
		if !ok || rest == "default" || rest == "instances" || strings.HasPrefix(rest, "instances.") {
			continue
		}
		if _, removed := l.removedMatch(full); removed {
			continue
		}
		add(full, l.sourceOf(full), "unknown key in section '"+sec.Path+"'")
	}

	// instance names, in file order
	instPrefix := sec.Path + ".instances."
	var names []string
	for _, full := range l.leaves.order {
		if rest, ok := strings.CutPrefix(full, instPrefix); ok {
			name, _, _ := strings.Cut(rest, ".")
			if !slices.Contains(names, name) {
				names = append(names, name)
			}
		}
	}

	for _, name := range names {
		if in, ok := l.checkInstance(sec, name, add); ok {
			result.ByName[name] = in
		}
	}
	result.Names = names

	switch {
	case sec.NoDefault:
	case hasDefault:
		if slices.Contains(names, defaultName) {
			result.Default = defaultName
		} else {
			add(sec.Path+".default", "", "names instance '"+defaultName+"', which is not configured; known: "+listOrNone(names))
		}
	case len(names) == 1:
		result.Default = names[0]
	case len(names) > 1:
		add(sec.Path+".default", "", "missing; several instances are configured ("+strings.Join(names, ", ")+") so one must be named as the default")
	}
	return result
}

func listOrNone(names []string) string {
	if len(names) == 0 {
		return "(none)"
	}
	return strings.Join(names, ", ")
}

// readString reads a string key (environment over file). A value that is not a scalar is
// reported and counts as absent.
func (l *loader) readString(key string, add func(key, source, msg string)) (string, bool) {
	raw, src, found := l.find(key)
	if !found {
		return "", false
	}
	text, ok := scalarText(raw)
	if !ok {
		add(key, src, "expected a string, found 'a list'")
		return "", false
	}
	return text, true
}

func (l *loader) checkInstance(sec Section, name string, add func(key, source, msg string)) (Instance, bool) {
	path := sec.Path + ".instances." + name
	in := Instance{Name: name, values: map[string]any{}, keys: map[string]struct{}{}}
	failed := false
	for _, k := range sec.Keys {
		rel := k.Key()
		full := path + "." + rel
		in.keys[rel] = struct{}{}
		if why := k.invalid(); why != "" {
			add(full, "", why)
			failed = true
			continue
		}
		v, has, source, failure := l.read(full, k)
		switch {
		case failure != "":
			add(full, source, failure)
			failed = true
		case has:
			in.values[rel] = v
		case k.isRequired():
			add(full, "", "missing required key; set it in a config file or as environment variable "+EnvName(full))
			failed = true
		}
	}
	for _, full := range l.leaves.order {
		rest, ok := strings.CutPrefix(full, path+".")
		if !ok {
			continue
		}
		if _, read := in.keys[rest]; read {
			continue
		}
		if _, removed := l.removedMatch(full); removed {
			failed = true // reported once, as a removed key
			continue
		}
		add(full, l.sourceOf(full), "unknown key for "+sec.Capability+" instance '"+name+"'")
		failed = true
	}
	return in, !failed
}
