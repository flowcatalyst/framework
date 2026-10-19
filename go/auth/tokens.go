package auth

import (
	"context"
	"errors"
	"log/slog"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// AccessToken is a verified platform access token.
type AccessToken struct {
	// Principal is who the token describes, with its Scopes.
	Principal Principal
	// AuthorizedParty is the OAuth client the token was issued to (azp), "" when absent.
	AuthorizedParty string
	// ExpiresAt is when the token stops being valid.
	ExpiresAt time.Time
}

// MayActFor reports whether the token's principal may act for a client ([Principal.MayActFor]).
func (a AccessToken) MayActFor(client string) bool { return a.Principal.MayActFor(client) }

// TokenResult is the closed set of outcomes of [PlatformTokens.Verify]: [Valid], [Invalid] or
// [Unavailable].
//
//sumtype:decl
type TokenResult interface{ tokenResult() }

// Valid is a token the platform signed and whose claims are acceptable.
type Valid struct{ Token AccessToken }

// Invalid is a token refused; Reason is the contract's reason name (malformed, alg, crit, kid,
// unknown_kid, signature, iss, aud, expired, not_yet_valid, iat, token_use, sub).
type Invalid struct{ Reason string }

// Unavailable means the platform's keys cannot be read, so the token cannot be checked right now
// (not a refusal of the token: answer 503, not 401).
type Unavailable struct{ Reason string }

func (Valid) tokenResult()       {}
func (Invalid) tokenResult()     {}
func (Unavailable) tokenResult() {}

// PlatformTokens verifies platform access tokens (contract section 5.6) without sessions: RS256
// against the platform's published keys, issuer and audience equal to the platform's base URL, the
// time claims with 60 seconds of allowance, token_use api. An identity token from a default login
// client is refused.
//
// It is safe for concurrent use; build one per platform and keep it, so the keys are cached. The
// auth module provides one.
type PlatformTokens struct {
	platform *platform
	clock    fw.Clock
}

// TokensOption configures [NewPlatformTokens].
type TokensOption func(*tokensOptions)

type tokensOptions struct{ allowHTTP bool }

// AllowPlainHTTP accepts an http:// issuer. For APP_ENV local and test only.
func AllowPlainHTTP() TokensOption { return func(o *tokensOptions) { o.allowHTTP = true } }

// AllowPlainHTTPIn accepts an http:// issuer only when env (APP_ENV) is local or test, the check
// [Module] makes; an application passes its environment instead of writing the test.
func AllowPlainHTTPIn(env string) TokensOption {
	return func(o *tokensOptions) { o.allowHTTP = env == "local" || env == "test" }
}

// NewPlatformTokens returns a verifier for the platform at issuer (its base URL, which is also its
// tokens' issuer and audience). timeout bounds each call to the platform; clock decides whether a
// token is still valid. log may be nil.
//
// An http:// issuer is refused unless [AllowPlainHTTP] is passed, which an application does only
// when APP_ENV is local or test: keys and tokens read over plain http can be forged by anyone on
// the path.
func NewPlatformTokens(issuer string, timeout time.Duration, clock fw.Clock, log *slog.Logger, opts ...TokensOption) (*PlatformTokens, error) {
	var o tokensOptions
	for _, f := range opts {
		f(&o)
	}
	p, err := newPlatform(issuer, timeout, clock, log, o.allowHTTP)
	if err != nil {
		return nil, err
	}
	return &PlatformTokens{platform: p, clock: clock}, nil
}

// Verify checks a compact access token (without the Bearer prefix).
func (t *PlatformTokens) Verify(ctx context.Context, token string) TokenResult {
	c, err := verifyJWT(ctx, token, t.platform)
	if err != nil {
		if errors.Is(err, ErrPlatformUnavailable) {
			return Unavailable{Reason: err.Error()}
		}
		return Unavailable{Reason: "the platform cannot be reached"}
	}
	if c.reason != "" {
		return Invalid{Reason: c.reason}
	}
	if r := checkAccessToken(c.claims, t.platform.issuer, t.clock.Now()); r != "" {
		return Invalid{Reason: r}
	}
	principal, ok := principalOf(c.claims, true)
	if !ok {
		return Invalid{Reason: "sub"}
	}
	exp, _ := c.claims["exp"].(float64)
	return Valid{Token: AccessToken{Principal: principal, AuthorizedParty: text(c.claims, "azp"), ExpiresAt: seconds(exp)}}
}
