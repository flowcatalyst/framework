package flowcatalyst

import (
	"context"
	"encoding/json/v2"
	"regexp"
	"sync"

	"github.com/flowcatalyst/framework/go/config"
)

// RevealedSecret is a secret property's plaintext as the platform reveals it (platform-config
// contract section 2). Value is a [config.Secret], so it prints without the text.
type RevealedSecret struct {
	// Section and Property name the property.
	Section, Property string
	// ClientID is the stored client id (clt_...) the value is for, even when the request named the
	// client by identifier; "" for the global value.
	ClientID string
	// Value is the plaintext; hand it to the caller and to nobody else, and never log it.
	Value config.Secret
	// UpdatedAt is when the value was last set, as the platform wrote it.
	UpdatedAt string
}

// RevealConfigSecret reads a secret property's plaintext: GET
// /api/config/{application}/{section}/{property}/secret?clientId=, as this application's service
// account (which needs platform:application-service:config:reveal). clientID is the platform's
// client id or the client's identifier, "" for the global value. A failure is a *PlatformError:
// [KindNotFound] (code Config_NOT_FOUND or Client_NOT_FOUND for the platform's own answers),
// [KindRejected] (400 NOT_A_SECRET, among others), [KindForbidden]. A disabled client fails with
// [ErrDisabled].
func (c *PlatformClient) RevealConfigSecret(ctx context.Context, section, property, clientID string) (RevealedSecret, error) {
	h, err := c.admin()
	if err != nil {
		return RevealedSecret{}, err
	}
	path := "/api/config/" + pathSegment(c.app.String()) + "/" + pathSegment(section) + "/" + pathSegment(property) + "/secret"
	raw, err := h.call(ctx, "GET", path, query{}.add("clientId", clientID), nil)
	if err != nil {
		return RevealedSecret{}, err
	}
	var answer struct {
		Section   string  `json:"section"`
		Property  string  `json:"property"`
		ClientID  *string `json:"clientId"`
		Value     *string `json:"value"`
		UpdatedAt string  `json:"updatedAt"`
	}
	if err := json.Unmarshal(raw, &answer); err != nil || answer.Value == nil {
		// the decoder's message can quote what it read: it is not kept
		return RevealedSecret{}, &PlatformError{msg: "the platform's answer to the reveal could not be read"}
	}
	out := RevealedSecret{Section: answer.Section, Property: answer.Property, Value: config.NewSecret(*answer.Value), UpdatedAt: answer.UpdatedAt}
	if answer.ClientID != nil {
		out.ClientID = *answer.ClientID
		c.aliases.learn(clientID, out.ClientID)
	}
	return out, nil
}

// CanonicalClient returns the stored client id the platform answered for a client identifier
// earlier (acme -> clt_0ACME), or client itself when no answer has named one.
func (c *PlatformClient) CanonicalClient(client string) string { return c.aliases.canonical(client) }

// maxAliases bounds the identifier-to-id pairs remembered; beyond it a client just stops being aliased.
const maxAliases = 10_000

var aliasValue = regexp.MustCompile(`^[A-Za-z0-9._-]{1,100}$`)

// clientAliases remembers which stored id the platform answered for a client identifier.
type clientAliases struct {
	mu sync.Mutex
	m  map[string]string
}

func (a *clientAliases) learn(requested, stored string) {
	if requested == "" || stored == "" || requested == stored || !aliasValue.MatchString(stored) {
		return
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.m == nil {
		a.m = map[string]string{}
	}
	if _, known := a.m[requested]; known || len(a.m) < maxAliases {
		a.m[requested] = stored
	}
}

func (a *clientAliases) canonical(client string) string {
	a.mu.Lock()
	defer a.mu.Unlock()
	if stored, ok := a.m[client]; ok {
		return stored
	}
	return client
}
