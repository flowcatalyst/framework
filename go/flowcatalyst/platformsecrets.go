package flowcatalyst

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"regexp"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/secrets"
)

// DefaultSecretsSection is the platform configuration section the platform secrets driver reads
// unless told another.
const DefaultSecretsSection = "secrets"

var (
	secretsSection = regexp.MustCompile(`^[a-z][a-z0-9-]{0,63}$`)
	// platformClient is a client the platform can name: its client id, or a client identifier.
	platformClient = regexp.MustCompile(`^(clt_[A-Za-z0-9]{1,60}|[A-Za-z0-9]([A-Za-z0-9-]{0,98}[A-Za-z0-9])?)$`)
)

// PlatformSecretsFactory is the platform secrets driver (platform-config contract section 3,
// secrets contract section 4), from [PlatformSecrets]: a per-client secret is a platform
// configuration property of this application, in one section, at that client's scope, read with
// the platform's reveal call as the application's service account.
type PlatformSecretsFactory struct {
	client  *PlatformClient
	section string
	cache   secrets.CachePolicy
}

// PlatformSecrets is the platform secrets driver over the module's client, reading section
// [DefaultSecretsSection] with [secrets.DefaultCache]:
//
//	fc, err := flowcatalyst.Build(ctx, b, fcd)
//	resolver, err := secrets.Build(ctx, b, sd,
//		secrets.WithStore("platform", flowcatalyst.PlatformSecrets(fc.Client())),
//		secrets.WithStore("ftp", flowcatalyst.PlatformSecrets(fc.Client()).Section("ftp")),
//		secrets.DefaultStore("platform"))
//
// The client of a lookup is the platform's client id (clt_...) or the client's identifier (acme):
// the platform resolves either to the same scope, so an application that names its clients by code
// needs no translation; a client that is neither is Refused without a call. There is no fall-back
// to the global value of the property. Answers: 200 is Found; 404 Config_NOT_FOUND or
// Client_NOT_FOUND is NotFound (never cached); 400 NOT_A_SECRET is Refused; anything else (another
// 404, 403, 5xx, the network, an answer without a value) is a *secrets.StoreError that names the
// store, client and secret, never a value.
func PlatformSecrets(client *PlatformClient) PlatformSecretsFactory {
	return PlatformSecretsFactory{client: client, section: DefaultSecretsSection, cache: secrets.DefaultCache()}
}

// Section returns a copy reading another section ([a-z][a-z0-9-]{0,63}; checked when the store is
// built).
func (f PlatformSecretsFactory) Section(name string) PlatformSecretsFactory {
	f.section = name
	return f
}

// WithCache returns a copy with another cache policy.
func (f PlatformSecretsFactory) WithCache(p secrets.CachePolicy) PlatformSecretsFactory {
	f.cache = p
	return f
}

// Create implements [secrets.StoreFactory]: it fails when the section is not a section name, the
// cache policy is out of range or the flowcatalyst module is disabled.
func (f PlatformSecretsFactory) Create(_ context.Context, name string, b *fw.Boot) (secrets.Store, error) {
	if !secretsSection.MatchString(f.section) {
		return nil, fmt.Errorf("section must match [a-z][a-z0-9-]{0,63}, was '%s'", f.section)
	}
	if err := f.cache.Validate(); err != nil {
		return nil, err
	}
	if f.client == nil || !f.client.Enabled() {
		return nil, ErrDisabled
	}
	inner := &platformSecretStore{instance: name, platform: f.client, section: f.section, log: b.Logger(ModuleID)}
	return secrets.Cached(inner, f.cache, b), nil
}

// The platform's codes for "no such property" and "no such client" on the config calls.
var notFoundCodes = map[string]bool{"Config_NOT_FOUND": true, "Client_NOT_FOUND": true}

type platformSecretStore struct {
	instance string
	platform *PlatformClient
	section  string
	log      *slog.Logger
}

// Canonical keeps one cache entry for a client's identifier and its stored id.
func (s *platformSecretStore) Canonical(client fw.ClientID) fw.ClientID {
	stored := s.platform.CanonicalClient(client.String())
	if id, err := fw.ParseClientID(stored); err == nil {
		return id
	}
	return client
}

func (s *platformSecretStore) Find(ctx context.Context, client fw.ClientID, name string) (secrets.Result, error) {
	if !platformClient.MatchString(client.String()) {
		return secrets.Refused{Reason: "the client must be the platform's client id (clt_...) or its identifier"}, nil
	}
	revealed, err := s.platform.RevealConfigSecret(ctx, s.section, name, client.String())
	if err != nil {
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return s.outcome(err, client, name)
	}
	if revealed.Value.Reveal() == "" {
		return nil, s.failure("answered without a value", client, name)
	}
	s.log.DebugContext(ctx, fmt.Sprintf("secret store '%s': found %s for client %s", s.instance, name, client))
	return secrets.Found{Secret: revealed.Value}, nil
}

func (s *platformSecretStore) outcome(err error, client fw.ClientID, name string) (secrets.Result, error) {
	pe, ok := errors.AsType[*PlatformError](err)
	if !ok {
		return nil, s.failure("could not be read", client, name)
	}
	switch pe.Kind() {
	case KindNotFound:
		// only the platform's own codes mean not found; any other 404 (a route that does not
		// exist, a proxy's page) says nothing about the secret
		if notFoundCodes[pe.Code()] {
			return secrets.NotFound{}, nil
		}
		return nil, s.failure("could not be read: "+pe.Error()+" (not a platform not-found answer)", client, name)
	case KindRejected:
		if pe.Code() == "NOT_A_SECRET" {
			return secrets.Refused{Reason: "the platform property is not a secret"}, nil
		}
		return nil, s.failure("was refused by the platform ("+pe.Error()+")", client, name)
	case KindForbidden:
		return nil, s.failure("was not read: application '"+s.platform.app.String()+"' is not authorised to reveal it", client, name)
	case KindOther:
	}
	// the cause is dropped: a decoder's message can quote the text it could not read
	return nil, s.failure("could not be read: "+pe.Error(), client, name)
}

func (s *platformSecretStore) failure(what string, client fw.ClientID, name string) error {
	return secrets.NewStoreError(fmt.Sprintf("secret store '%s' (platform): secret '%s' of client '%s' %s", s.instance, name, client, what))
}

func (*platformSecretStore) Evict(fw.ClientID, string) {}
func (*platformSecretStore) EvictAll()                 {}

func (s *platformSecretStore) String() string {
	return fmt.Sprintf("PlatformSecretStore[%s, section=%s]", s.instance, s.section)
}
