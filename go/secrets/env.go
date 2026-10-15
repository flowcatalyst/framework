package secrets

import (
	"context"
	"fmt"
	"regexp"
	"strings"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

var (
	envPrefixPattern = regexp.MustCompile(`^[A-Z][A-Z0-9_]*_$`)
	clientEnvPattern = regexp.MustCompile(`^[a-z0-9]+(-[a-z0-9]+)*$`)

	// forbiddenPrefixes are the variable prefixes of the framework's own components.
	forbiddenPrefixes = []string{"APP_", "HTTP_", "DATABASE_", "REDIS_", "LOCKS_", "FLOWCATALYST_", "SECRETS_"}
)

func checkEnvPrefix(prefix string) error {
	if len(prefix) < 4 || !envPrefixPattern.MatchString(prefix) {
		return fmt.Errorf("prefix must match [A-Z][A-Z0-9_]*_ and be at least 4 characters")
	}
	for _, f := range forbiddenPrefixes {
		if strings.HasPrefix(prefix, f) {
			return fmt.Errorf("prefix must not start with %s (a framework component's variables)", f)
		}
	}
	return nil
}

// EnvFactory makes the env store: see [Env].
type EnvFactory struct {
	prefix string
	cache  CachePolicy
}

// Env is the store that reads environment variables: the variable of (client, name) is
// prefix + E(client) + "__" + E(name), where E upper-cases and turns '-' into '_' (client
// "acme-corp", name "sftp-password", prefix "CLIENT_SECRET_" is
// CLIENT_SECRET_ACME_CORP__SFTP_PASSWORD). The prefix matches [A-Z][A-Z0-9_]*_, has at least 4
// characters and does not start with a framework component's prefix (APP_, HTTP_, DATABASE_,
// REDIS_, LOCKS_, FLOWCATALYST_, SECRETS_); it is checked when [Build] runs. The client must be
// lowercase letters, digits and single hyphens (else Refused, never approximated), so the
// encoding is injective: the variable splits back into exactly one (client, name). Variables are
// read by exact name from the environment the application was configured with; the environment
// is never listed. An absent or empty variable is NotFound.
func Env(prefix string) EnvFactory { return EnvFactory{prefix: prefix, cache: DefaultCache()} }

// WithCache returns a copy with another cache policy.
func (f EnvFactory) WithCache(p CachePolicy) EnvFactory { f.cache = p; return f }

// Create implements [StoreFactory].
func (f EnvFactory) Create(_ context.Context, name string, b *fw.Boot) (Store, error) {
	if err := checkEnvPrefix(f.prefix); err != nil {
		return nil, err
	}
	if err := f.cache.Validate(); err != nil {
		return nil, err
	}
	return withCache(&envStore{instance: name, prefix: f.prefix, lookup: b.LookupEnv}, f.cache, b.Clock()), nil
}

func withCache(s Store, p CachePolicy, clock fw.Clock) Store {
	if p.TTL == 0 {
		return s
	}
	return newCached(s, p, clock)
}

type envStore struct {
	instance string
	prefix   string
	lookup   func(string) (string, bool)
}

func (s *envStore) variable(client fw.ClientID, name string) (string, bool) {
	if !clientEnvPattern.MatchString(client.String()) {
		return "", false
	}
	return s.prefix + encode(client.String()) + "__" + encode(name), true
}

func encode(part string) string { return strings.ReplaceAll(strings.ToUpper(part), "-", "_") }

func (s *envStore) Find(_ context.Context, client fw.ClientID, name string) (Result, error) {
	if err := checkName(name); err != nil {
		return nil, fmt.Errorf("%w: %w", ErrInvalidName, err)
	}
	v, ok := s.variable(client, name)
	if !ok {
		return Refused{Reason: "secret store '" + s.instance + "' (env) cannot encode client '" + client.String() +
			"': only lowercase letters, digits and single hyphens"}, nil
	}
	value, set := s.lookup(v)
	if !set || value == "" {
		return NotFound{}, nil
	}
	return Found{Secret: config.NewSecret(value)}, nil
}

func (*envStore) Evict(fw.ClientID, string) {}
func (*envStore) EvictAll()                 {}
func (s *envStore) String() string          { return "EnvSecretStore[" + s.instance + "]" }
