package config

import (
	"regexp"
	"slices"
	"sort"
	"strings"
)

// RemovedKey is a configuration key whose behaviour moved to code (config-and-code contract
// section 5). Giving it in a file or the environment fails start-up with MovedTo.
type RemovedKey struct {
	// Pattern is dotted segments; "*" matches one segment (an instance name). In a file a key
	// matches when its leading segments match, so "locks" matches every key under it.
	Pattern string
	// Module is the module the key belonged to, named in the problem.
	Module string
	// MovedTo says where the behaviour is given now.
	MovedTo string
}

// ComponentClaim is a module's claim on a component: every key under it in a file must be
// declared by some module, or start-up fails (config-and-code contract section 5).
type ComponentClaim struct {
	Module    string
	Component string
}

// KeyRules are the checks applied to the keys found in files and the environment. The zero value
// applies none: the loader alone, as the conformance vector config.json exercises it.
type KeyRules struct {
	Removed []RemovedKey
	Claims  []ComponentClaim
}

const guideHint = " (see the guide page migrating-config-to-code)"

var (
	removedSegment   = regexp.MustCompile(`^(\*|[a-z][a-z0-9_-]*)$`)
	componentPattern = regexp.MustCompile(`^[a-z][a-z0-9_-]*$`)
)

func (r RemovedKey) segments() []string { return strings.Split(r.Pattern, ".") }

func (r RemovedKey) valid() bool {
	if strings.TrimSpace(r.MovedTo) == "" {
		return false
	}
	for _, s := range r.segments() {
		if !removedSegment.MatchString(s) {
			return false
		}
	}
	return true
}

// matches reports whether a file key is this removed key or lies under it.
func (r RemovedKey) matches(key string) bool {
	k := strings.Split(key, ".")
	p := r.segments()
	if len(k) < len(p) {
		return false
	}
	for i, seg := range p {
		if seg != "*" && seg != k[i] {
			return false
		}
	}
	return true
}

// FrameworkRemovedKeys is the framework's removed keys (config-and-code contract section 8),
// most specific patterns first: a key is reported with the first pattern it matches.
func FrameworkRemovedKeys() []RemovedKey {
	var l []RemovedKey
	k := func(pattern, module, movedTo string) { l = append(l, RemovedKey{pattern, module, movedTo}) }

	k("database.instances.*.driver", "database", "nothing: PostgreSQL is the only database driver")
	k("redis.instances.*.driver", "redis", "nothing: one Redis driver")

	stores := "locks.Build(ctx, b, d, locks.WithStore(name, locks.Memory() | dblock.Store(dbs) | redislock.Store(clients)))"
	k("locks.default", "locks", "locks.DefaultStore(name)")
	for _, s := range []string{"driver", "database", "connection", "prefix"} {
		k("locks.instances.*."+s, "locks", stores)
	}
	k("locks", "locks", stores)

	named := "nothing: clients are named in code, one httpclient client per name with its options"
	k("http-clients.default", "http-client", named)
	k("http-clients.instances.*.driver", "http-client", named)
	k("http-clients.instances.*.connect-timeout", "http-client", "the client's options: connect timeout")
	k("http-clients.instances.*.request-timeout", "http-client", "the client's options: request timeout")
	k("http-clients.instances.*.max-response-bytes", "http-client", "the client's options: maximum response bytes")
	for _, s := range []string{"max-attempts", "initial-backoff", "max-backoff", "max-retry-after"} {
		k("http-clients.instances.*.retry."+s, "http-client", "the client's options: retry policy")
	}
	k("http-clients.instances.*.retry", "http-client", "the client's options: retry policy")
	for _, s := range []string{"type", "method", "scope", "audience", "name"} {
		k("http-clients.instances.*.auth."+s, "http-client", "the client's options: authentication strategy")
	}

	secretStores := "secrets stores named in code (secrets.Env(prefix) | secrets.File(directory)) with a cache policy"
	k("secrets.default", "secrets", "secrets.DefaultStore(name)")
	for _, s := range []string{"driver", "directory", "prefix", "max-bytes", "cache-ttl", "cache-max"} {
		k("secrets.instances.*."+s, "secrets", secretStores)
	}
	k("secrets", "secrets", secretStores)

	limiters := "rate limiters built in code (ratelimit memory or Redis limiter)"
	k("rate-limits.default", "rate-limit", limiters)
	for _, s := range []string{"driver", "limit", "period", "burst", "max-keys", "connection", "prefix"} {
		k("rate-limits.instances.*."+s, "rate-limit", limiters)
	}
	k("rate-limits", "rate-limit", limiters)
	return l
}

// removedMatch returns the first removed key a file key matches.
func (l *loader) removedMatch(key string) (RemovedKey, bool) {
	for _, r := range l.rules.Removed {
		if r.valid() && r.matches(key) {
			return r, true
		}
	}
	return RemovedKey{}, false
}

// checkKeyRules applies the removed-key and claimed-component checks (contract section 5).
func (l *loader) checkKeyRules(declaredBy map[string]string) {
	rules := l.rules
	for _, r := range rules.Removed {
		if !r.valid() {
			l.problems = append(l.problems, Problem{Module: r.Module, Key: r.Pattern, Message: "invalid removed-key pattern"})
		}
	}
	claimedBy := map[string]string{}
	for _, c := range rules.Claims {
		if !componentPattern.MatchString(c.Component) {
			l.problems = append(l.problems, Problem{
				Module: c.Module, Key: c.Component,
				Message: "a claimed component must match [a-z][a-z0-9_-]*",
			})
			continue
		}
		if earlier, dup := claimedBy[c.Component]; dup {
			l.problems = append(l.problems, Problem{
				Module: c.Module, Key: c.Component,
				Message: "component '" + c.Component + "' is already claimed by module '" + earlier + "'",
			})
			continue
		}
		claimedBy[c.Component] = c.Module
	}

	reported := map[string]bool{}
	for _, key := range l.leaves.order {
		lf := l.leaves.m[key]
		if _, declared := declaredBy[key]; declared || l.emptyAboveDeclared(key, lf, declaredBy) {
			continue
		}
		if r, ok := l.removedMatch(key); ok {
			l.problems = append(l.problems, movedTo(r, key, lf.source))
			reported[key] = true
			continue
		}
		component, _, _ := strings.Cut(key, ".")
		if claimer, ok := claimedBy[component]; ok {
			l.problems = append(l.problems, Problem{
				Module: claimer, Key: key, Source: lf.source,
				Message: "unknown key: component '" + component + "' belongs to module '" + claimer +
					"', which reads " + declaredUnder(component, declaredBy),
			})
			reported[key] = true
		}
	}

	// the environment: names derived from removed patterns of two or more segments, each "*"
	// replaced by the names found at that position in the files. Never scanned.
	for _, r := range rules.Removed {
		if !r.valid() || len(r.segments()) < 2 {
			continue
		}
		for _, key := range expandPattern(r.segments(), l.leaves.order) {
			if _, declared := declaredBy[key]; declared || reported[key] {
				continue
			}
			name := EnvName(key)
			if _, ok := l.env[name]; ok {
				l.problems = append(l.problems, movedTo(r, key, "environment variable "+name))
				reported[key] = true
			}
		}
	}
}

func movedTo(r RemovedKey, key, source string) Problem {
	return Problem{Module: r.Module, Key: key, Source: source, Message: "moved to code: " + r.MovedTo + guideHint}
}

// emptyAboveDeclared is an empty mapping (null) whose key is an ancestor of a declared key.
func (l *loader) emptyAboveDeclared(key string, lf leaf, declaredBy map[string]string) bool {
	if lf.value != nil {
		return false
	}
	for d := range declaredBy {
		if strings.HasPrefix(d, key+".") {
			return true
		}
	}
	return false
}

func declaredUnder(component string, declaredBy map[string]string) string {
	var keys []string
	for d := range declaredBy {
		if strings.HasPrefix(d, component+".") {
			keys = append(keys, d)
		}
	}
	if len(keys) == 0 {
		return "no key under it (its behaviour is set in code)"
	}
	sort.Strings(keys)
	return "only " + strings.Join(keys, ", ")
}

// expandPattern lists every key the pattern names, each "*" replaced by a name found there in
// the file keys.
func expandPattern(pattern, fileKeys []string) []string {
	prefixes := []string{""}
	for _, seg := range pattern {
		var next []string
		for _, prefix := range prefixes {
			if seg != "*" {
				if prefix == "" {
					next = append(next, seg)
				} else {
					next = append(next, prefix+"."+seg)
				}
				continue
			}
			head := prefix + "."
			var names []string
			for _, k := range fileKeys {
				if rest, ok := strings.CutPrefix(k, head); ok {
					name, _, _ := strings.Cut(rest, ".")
					if !slices.Contains(names, name) {
						names = append(names, name)
					}
				}
			}
			for _, n := range names {
				next = append(next, head+n)
			}
		}
		prefixes = next
	}
	return prefixes
}
