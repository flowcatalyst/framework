package flowcatalyst

import (
	"context"
	"encoding/base64"
	"encoding/json/v2"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/flowcatalyst/flowcatalyst/pkg/fcsdk/auth"
	"github.com/flowcatalyst/flowcatalyst/pkg/fcsdk/client"

	"github.com/flowcatalyst/framework/go/config"
	"github.com/flowcatalyst/framework/go/fw"
)

// ErrDisabled is returned by every method of a disabled [PlatformClient] (flowcatalyst.enabled is
// false) except Enabled.
var ErrDisabled = errors.New("the flowcatalyst module is disabled (flowcatalyst.enabled)")

// PlatformError is a call to the platform that failed. Its message names what failed without the
// platform's answer or any credential: it is shown on the unauthenticated health endpoint.
type PlatformError struct {
	msg   string
	cause error
}

func (e *PlatformError) Error() string { return e.msg }

// Unwrap returns the underlying error (the SDK's, which may carry the platform's answer: log it,
// never return it to a caller).
func (e *PlatformError) Unwrap() error { return e.cause }

// ErrNoServicePrincipal wraps the error [PlatformClient.ServicePrincipal] returns when the platform
// token does not identify the application's service principal.
var ErrNoServicePrincipal = errors.New("the platform token does not identify the application's service principal")

// Completion is how a scheduled-job instance ended.
type Completion string

// The completions.
const (
	// CompletionSuccess means the job ran and succeeded.
	CompletionSuccess Completion = "SUCCESS"
	// CompletionFailure means the job failed, timed out or did not run.
	CompletionFailure Completion = "FAILURE"
)

// LogLevel is the level of a scheduled-job instance log line.
type LogLevel string

// The log levels.
const (
	// LogDebug is DEBUG.
	LogDebug LogLevel = "DEBUG"
	// LogInfo is INFO.
	LogInfo LogLevel = "INFO"
	// LogWarn is WARN.
	LogWarn LogLevel = "WARN"
	// LogError is ERROR.
	LogError LogLevel = "ERROR"
)

// PlatformClient is the application's client of the platform: a thin wrapper over the SDK's client
// built with client credentials (the application's service account). Application code uses its
// methods; the SDK's types do not appear in them. It is safe for concurrent use.
//
// It holds two SDK clients over one token source. The SDK retries 408, 425, 429 and 5xx inside every
// call with no per-call switch, and the scheduled-job reports have their own bounded retry (the
// receivers), so those two calls use a client that makes exactly one HTTP call per attempt;
// otherwise the two retries would multiply.
type PlatformClient struct {
	app       fw.ApplicationCode
	sdk       *client.FlowCatalystClient
	reporting *client.FlowCatalystClient
	tokens    *auth.ClientCredentialsProvider
	enabled   bool

	mu       sync.Mutex
	resolved resolvedToken
}

type resolvedToken struct {
	token     string
	principal fw.PrincipalID
}

func disabledClient() *PlatformClient { return &PlatformClient{} }

// newPlatformClient builds the client; the arguments are checked by [Build].
func newPlatformClient(baseURL url.URL, app fw.ApplicationCode, clientID string, clientSecret config.Secret, timeout time.Duration) *PlatformClient {
	httpc := &http.Client{Timeout: timeout}
	base := strings.TrimRight(baseURL.String(), "/")
	tokens := auth.NewClientCredentialsProvider(auth.ClientCredentialsConfig{
		IssuerURL: base, ClientID: clientID, ClientSecret: clientSecret.Reveal(), HTTPClient: httpc,
	})
	mk := func(attempts int) *client.FlowCatalystClient {
		return client.New(base, client.WithTokenProvider(tokens.Token), client.WithTimeout(timeout),
			client.WithHTTPClient(httpc), client.WithRetry(attempts, 100*time.Millisecond))
	}
	return &PlatformClient{app: app, sdk: mk(3), reporting: mk(1), tokens: tokens, enabled: true}
}

// Enabled reports whether the module is active (flowcatalyst.enabled).
func (c *PlatformClient) Enabled() bool { return c.enabled }

// Application returns the application's code on the platform.
func (c *PlatformClient) Application() (fw.ApplicationCode, error) {
	if !c.enabled {
		return fw.ApplicationCode{}, ErrDisabled
	}
	return c.app, nil
}

// VerifyCredentials fetches a token (or uses the cached one): nil when the platform accepts the
// application's credentials.
func (c *PlatformClient) VerifyCredentials(ctx context.Context) error {
	_, err := c.AccessToken(ctx)
	return err
}

// AccessToken returns the application's access token, fetched with its client credentials and
// cached until shortly before it expires. Use it as the credential of an HTTP client that calls
// the platform: httpclient.BearerRefreshing over AccessToken and ClearToken.
func (c *PlatformClient) AccessToken(ctx context.Context) (config.Secret, error) {
	if !c.enabled {
		return config.Secret{}, ErrDisabled
	}
	tok, err := c.tokens.Token(ctx)
	if err != nil {
		return config.Secret{}, tokenError(err)
	}
	return config.NewSecret(tok), nil
}

// ClearToken drops the cached token, so the next call fetches a new one.
func (c *PlatformClient) ClearToken() {
	if c.enabled {
		c.tokens.Invalidate()
	}
}

func tokenError(err error) error {
	switch {
	case errors.Is(err, context.Canceled), errors.Is(err, context.DeadlineExceeded):
		return err
	case errors.Is(err, auth.ErrTokenExchange):
		return &PlatformError{msg: "the platform did not issue a token (the token endpoint refused the credentials or is unreachable)", cause: err}
	}
	return &PlatformError{msg: "the platform did not issue a token", cause: err}
}

func callError(what string, err error) error {
	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return err
	}
	if api, ok := errors.AsType[*client.APIError](err); ok {
		return &PlatformError{msg: fmt.Sprintf("%s: the platform answered %d", what, api.StatusCode), cause: err}
	}
	return &PlatformError{msg: what + ": the platform call failed", cause: err}
}

// subPattern is what a principal id (the sub claim) must match.
var subPattern = regexp.MustCompile(`^[A-Za-z0-9._:-]{1,128}$`)

// ServicePrincipal returns the id of the application's service principal: the sub claim of the
// client-credentials access token the client already fetches (flowcatalyst contract section 4b).
// The token is the one received directly from the platform's token endpoint in answer to the
// application's own credentials; its signature is not checked (this module has no JWKS client).
// It must be a JWT whose payload is one JSON object with unique keys, with sub matching
// [A-Za-z0-9._:-]{1,128} and type exactly SERVICE. The id is kept with the token it came from and
// decoded again only when the token changes. A token that cannot be fetched or does not identify
// a service principal is an error wrapping [ErrNoServicePrincipal] or the token error; no receiver
// handler runs without a principal.
func (c *PlatformClient) ServicePrincipal(ctx context.Context) (fw.PrincipalID, error) {
	if !c.enabled {
		return fw.PrincipalID{}, ErrDisabled
	}
	secret, err := c.AccessToken(ctx)
	if err != nil {
		return fw.PrincipalID{}, err
	}
	token := secret.Reveal()
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.resolved.token == token && !c.resolved.principal.IsZero() {
		return c.resolved.principal, nil
	}
	p, err := servicePrincipalOf(token)
	if err != nil {
		return fw.PrincipalID{}, err
	}
	c.resolved = resolvedToken{token: token, principal: p}
	return p, nil
}

func refused(reason string) error {
	return fmt.Errorf("%w: %s", ErrNoServicePrincipal, reason)
}

// servicePrincipalOf reads the principal out of an access token (never logs or returns the token).
func servicePrincipalOf(token string) (fw.PrincipalID, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 || parts[0] == "" || parts[1] == "" || parts[2] == "" {
		return fw.PrincipalID{}, refused("it is not a JWT (three non-empty dot-separated segments)")
	}
	payload, err := base64.RawURLEncoding.DecodeString(strings.TrimRight(parts[1], "="))
	if err != nil {
		return fw.PrincipalID{}, refused("its payload is not base64url")
	}
	var claims map[string]any
	if err := json.Unmarshal(payload, &claims); err != nil { // json/v2 refuses duplicate names
		return fw.PrincipalID{}, refused("its payload is not one JSON object with unique keys")
	}
	sub, ok := claims["sub"].(string)
	if !ok || !subPattern.MatchString(sub) {
		return fw.PrincipalID{}, refused("its sub claim is missing or is not a principal id ([A-Za-z0-9._:-]{1,128})")
	}
	if typ, ok := claims["type"].(string); !ok || typ != "SERVICE" {
		return fw.PrincipalID{}, refused("its type claim is not SERVICE: the configured credentials (flowcatalyst.client-id) must be the application's service account, not a user's")
	}
	p, err := fw.ParsePrincipalID(sub)
	if err != nil {
		return fw.PrincipalID{}, refused("its sub claim is not a principal id")
	}
	return p, nil
}

// CompleteJobInstance reports how a scheduled-job instance ended
// (POST /api/scheduled-jobs/instances/{id}/complete). result is the JSON result, nil for none. The
// call is made once: the caller owns the retry.
func (c *PlatformClient) CompleteJobInstance(ctx context.Context, instanceID string, status Completion, result []byte) error {
	if !c.enabled {
		return ErrDisabled
	}
	req := &client.InstanceCompleteRequest{Status: client.CompletionStatus(status)}
	if len(result) > 0 {
		req.Result = []byte(result)
	}
	if err := c.reporting.ScheduledJobs().CompleteInstance(ctx, instanceID, req); err != nil {
		return callError("completing the scheduled-job instance", err)
	}
	return nil
}

// LogJobInstance appends a line to a scheduled-job instance's log
// (POST /api/scheduled-jobs/instances/{id}/log), once.
func (c *PlatformClient) LogJobInstance(ctx context.Context, instanceID string, level LogLevel, message string) error {
	if !c.enabled {
		return ErrDisabled
	}
	if err := c.reporting.ScheduledJobs().LogForInstance(ctx, instanceID,
		&client.InstanceLogRequest{Level: client.LogLevel(level), Message: message}); err != nil {
		return callError("logging to the scheduled-job instance", err)
	}
	return nil
}
