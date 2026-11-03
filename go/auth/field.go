package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/sha256"
	"encoding/asn1"
	"encoding/base64"
	"errors"
	"fmt"
	"math/big"
	"slices"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// Field workers (sessions-and-login contract section 15): the platform issues a short access token
// bound to an enrolled device's key; the device proves each request with a DPoP proof (RFC 9449).
// The application only verifies. Nothing here is on unless the application passes [FieldWorkers].

const (
	fieldTokenType = "fc-field+jwt" //nolint:gosec // a JOSE typ, not a credential
	fieldSubPrefix = "fwu_"
	// proofWindow is how far a proof's iat may be from the server clock, either way. Field devices
	// keep bad time; freshness comes from the nonce.
	proofWindow  = 24 * time.Hour
	maxJTILength = 256
)

// FieldPrincipal is a verified field worker on a verified device (contract section 15.5).
type FieldPrincipal struct {
	// WorkerID is the worker's principal id (sub), fwu_...
	WorkerID string
	// WorkerCode and Name are the worker's code and display name.
	WorkerCode, Name string
	// ClientID is the platform id of the worker's client (clt_...) and ClientIdentifier its
	// identifier, which is the Context's client.
	ClientID, ClientIdentifier string
	// AppCode is the application the token was issued for (aud).
	AppCode string
	// DeviceID is the enrolled device, DeviceMode its mode: PERSONAL or SHARED.
	DeviceID, DeviceMode string
	// AMR lists how the worker authenticated (for example "pin").
	AMR []string
	// AuthTime is when the worker signed in.
	AuthTime time.Time
	// SessionID is the login family (sid): every token of one sign-in carries it.
	SessionID string
	// ExpiresAt is when the access token stops being valid.
	ExpiresAt time.Time
}

// FieldPermissionMapper turns a field worker into the permissions its Context carries (contract
// section 15.5). The default grants none, so a route that requires a permission refuses field
// workers until the application maps them.
type FieldPermissionMapper func(FieldPrincipal) []string

// FieldNoPermissions grants nothing (the default).
func FieldNoPermissions() FieldPermissionMapper { return func(FieldPrincipal) []string { return nil } }

// FieldPermissionsAll grants every field worker the same permissions.
func FieldPermissionsAll(permissions ...string) FieldPermissionMapper {
	frozen := slices.Clone(permissions)
	return func(FieldPrincipal) []string { return slices.Clone(frozen) }
}

// FieldPermissionsByDeviceMode grants one set on a PERSONAL device and another on a SHARED one
// (shared kiosks usually get less). Any other mode is treated as SHARED, the lesser.
func FieldPermissionsByDeviceMode(personal, shared []string) FieldPermissionMapper {
	personal, shared = slices.Clone(personal), slices.Clone(shared)
	return func(p FieldPrincipal) []string {
		if p.DeviceMode == "PERSONAL" {
			return slices.Clone(personal)
		}
		return slices.Clone(shared)
	}
}

// FieldOption configures [FieldWorkers].
type FieldOption func(*fieldOptions)

type fieldOptions struct {
	permissions FieldPermissionMapper
	replay      FieldReplayCache
}

// MapFieldPermissions sets how a field worker's permissions are found. Default
// [FieldNoPermissions].
func MapFieldPermissions(m FieldPermissionMapper) FieldOption {
	return func(o *fieldOptions) { o.permissions = m }
}

// FieldReplay sets the replay cache. Default: a memory cache, which logs one warning at start-up
// because it is wrong with more than one replica; pass authredis.NewReplayCache then.
func FieldReplay(c FieldReplayCache) FieldOption { return func(o *fieldOptions) { o.replay = c } }

// FieldResult is the closed set of outcomes of [FieldTokens.Verify]: [FieldValid], [FieldInvalid],
// [FieldNonceRequired] or [FieldUnavailable].
//
//sumtype:decl
type FieldResult interface{ fieldResult() }

// FieldValid is a token and a proof that both check out.
type FieldValid struct{ Principal FieldPrincipal }

// FieldInvalid is a credential refused; Reason is one of the contract's reasons: the reasons of
// section 5.5 (malformed, alg, crit, kid, unknown_kid, signature) and section 15.2 (wrong_typ, iss,
// wrong_aud, expired, not_yet_valid, iat, wrong_token_use, sub, claims, pcr) and 15.3
// (missing_proof, invalid_proof, jkt_mismatch, htm_mismatch, htu_mismatch, ath_mismatch,
// stale_proof, replayed).
type FieldInvalid struct{ Reason string }

// FieldNonceRequired means the proof carries no valid application nonce: answer 401
// use_dpop_nonce with a fresh nonce.
type FieldNonceRequired struct{}

// FieldUnavailable means the platform's keys or the replay cache cannot be read: answer 503.
type FieldUnavailable struct{ Reason string }

func (FieldValid) fieldResult()         {}
func (FieldInvalid) fieldResult()       {}
func (FieldNonceRequired) fieldResult() {}
func (FieldUnavailable) fieldResult()   {}

// FieldTokens verifies field access tokens and the DPoP proofs that go with them (contract section
// 15.2 and 15.3). It shares the platform's key cache and issuer with [PlatformTokens]. It is safe for
// concurrent use; the auth module builds one when [FieldWorkers] is passed ([Module.FieldTokens]).
type FieldTokens struct {
	platform *platform
	clock    fw.Clock
	appCode  string
	baseURL  string // auth.public-base-url without a trailing slash
	nonces   *fieldNonces
	replay   FieldReplayCache

	// conformance runs only (package tests): the platform's vectors carry the platform's nonces.
	skipNonce bool
	window    time.Duration
}

// Nonce returns a fresh application nonce (the DPoP-Nonce header value).
func (t *FieldTokens) Nonce() string { return t.nonces.issue(t.clock.Now()) }

// Verify checks a request's credentials: the access token (without the DPoP prefix), the proof
// header, the request method and the request's escaped path (no query). The proof's htu must equal
// auth.public-base-url plus that path.
func (t *FieldTokens) Verify(ctx context.Context, token, proof, method, escapedPath string) FieldResult {
	return t.verify(ctx, token, proof, method, t.baseURL+escapedPath)
}

func (t *FieldTokens) verify(ctx context.Context, token, proof, method, htu string) FieldResult {
	c, err := verifyJWT(ctx, token, t.platform)
	if err != nil {
		return FieldUnavailable{Reason: "the platform's keys cannot be read"}
	}
	if c.reason != "" {
		return FieldInvalid{c.reason}
	}
	now := t.clock.Now()
	if typ, _ := object(strings.SplitN(token, ".", 2)[0])["typ"].(string); typ != fieldTokenType {
		return FieldInvalid{"wrong_typ"}
	}
	p, jkt, reason := t.checkClaims(c.claims, now)
	if reason != "" {
		return FieldInvalid{reason}
	}
	return t.checkProof(ctx, p, jkt, token, proof, method, htu, now)
}

// checkClaims applies contract section 15.2 to the claims of a signed token.
func (t *FieldTokens) checkClaims(claims claimSet, now time.Time) (p FieldPrincipal, jkt, reason string) {
	if text(claims, "iss") != t.platform.issuer {
		return FieldPrincipal{}, "", "iss"
	}
	if aud, ok := claims["aud"].(string); !ok || aud != t.appCode {
		return FieldPrincipal{}, "", "wrong_aud"
	}
	if r := times(claims, now); r != "" {
		return FieldPrincipal{}, "", r
	}
	if text(claims, "token_use") != "field" {
		return FieldPrincipal{}, "", "wrong_token_use"
	}
	sub := text(claims, "sub")
	if !strings.HasPrefix(sub, fieldSubPrefix) || len(sub) == len(fieldSubPrefix) {
		return FieldPrincipal{}, "", "sub"
	}
	cnf, _ := claims["cnf"].(map[string]any)
	jkt, _ = cnf["jkt"].(string)
	p = FieldPrincipal{
		WorkerID: sub, WorkerCode: text(claims, "worker_code"), Name: text(claims, "name"),
		ClientID: text(claims, "client_id"), ClientIdentifier: text(claims, "client_identifier"),
		AppCode: t.appCode, DeviceID: text(claims, "device_id"), DeviceMode: text(claims, "device_mode"),
		AMR: texts(claims["amr"]), SessionID: text(claims, "sid"),
	}
	if p.ClientID == "" || p.ClientIdentifier == "" || p.DeviceID == "" || p.SessionID == "" || jkt == "" ||
		!clientIdentifier.MatchString(p.ClientIdentifier) {
		return FieldPrincipal{}, "", "claims"
	}
	if pcr, has := claims["pcr"]; has && pcr == true {
		return FieldPrincipal{}, "", "pcr"
	}
	exp, _ := claims["exp"].(float64)
	p.ExpiresAt = seconds(exp)
	if at, ok := claims["auth_time"].(float64); ok {
		p.AuthTime = seconds(at)
	}
	return p, jkt, ""
}

// checkProof applies contract section 15.3.
func (t *FieldTokens) checkProof(ctx context.Context, p FieldPrincipal, jkt, token, proof, method, htu string, now time.Time) FieldResult {
	if strings.TrimSpace(proof) == "" {
		return FieldInvalid{"missing_proof"}
	}
	pr, reason := parseProof(proof)
	if reason != "" {
		return FieldInvalid{reason}
	}
	switch {
	case !same(pr.jkt, jkt):
		return FieldInvalid{"jkt_mismatch"}
	case pr.htm != method:
		return FieldInvalid{"htm_mismatch"}
	case pr.htu != htu:
		return FieldInvalid{"htu_mismatch"}
	}
	sum := sha256.Sum256([]byte(token))
	if !same(pr.ath, base64.RawURLEncoding.EncodeToString(sum[:])) {
		return FieldInvalid{"ath_mismatch"}
	}
	window := proofWindow
	if t.window > 0 {
		window = t.window
	}
	if d := now.Sub(pr.iat); d > window || d < -window {
		return FieldInvalid{"stale_proof"}
	}
	if !t.skipNonce && !t.nonces.valid(pr.nonce, now) {
		return FieldNonceRequired{}
	}
	first, err := t.replay.Remember(ctx, pr.jkt, pr.jti, FieldReplayWindow)
	if err != nil {
		return FieldUnavailable{Reason: "the replay cache cannot be asked"}
	}
	if !first {
		return FieldInvalid{"replayed"}
	}
	return FieldValid{Principal: p}
}

type fieldProof struct {
	jkt, htm, htu, ath, nonce, jti string
	iat                            time.Time
}

// parseProof checks the proof's own shape and signature (contract section 15.3, first bullet and
// the header rules) and returns its claims; the reason is "" on success.
func parseProof(compact string) (fieldProof, string) {
	bad := func() (fieldProof, string) { return fieldProof{}, "invalid_proof" }
	parts := splitDots(strings.TrimSpace(compact))
	if len(parts) != 3 || len(compact) > maxTokenLength {
		return bad()
	}
	header := object(parts[0])
	if header == nil || header["typ"] != "dpop+jwt" || header["alg"] != "ES256" {
		return bad()
	}
	if _, has := header["crit"]; has {
		return bad()
	}
	pub, jkt, ok := proofKey(header["jwk"])
	if !ok {
		return bad()
	}
	sig, ok := decodeB64(parts[2])
	if !ok || len(sig) != 64 {
		return bad()
	}
	der, err := asn1.Marshal(struct{ R, S *big.Int }{new(big.Int).SetBytes(sig[:32]), new(big.Int).SetBytes(sig[32:])})
	digest := sha256.Sum256([]byte(parts[0] + "." + parts[1]))
	if err != nil || !ecdsa.VerifyASN1(pub, digest[:], der) {
		return bad()
	}
	claims := object(parts[1])
	if claims == nil {
		return bad()
	}
	iat, hasIat := claims["iat"].(float64)
	jti, _ := claims["jti"].(string)
	if !hasIat || jti == "" || len(jti) > maxJTILength {
		return bad()
	}
	return fieldProof{
		jkt: jkt, htm: text(claims, "htm"), htu: text(claims, "htu"), ath: text(claims, "ath"), nonce: text(claims, "nonce"),
		jti: jti, iat: seconds(iat),
	}, ""
}

// proofKey reads the proof's jwk: a public EC P-256 key, no private member, on the curve; with
// its RFC 7638 thumbprint.
func proofKey(v any) (*ecdsa.PublicKey, string, bool) {
	jwk, ok := v.(map[string]any)
	if !ok || jwk["kty"] != "EC" || jwk["crv"] != "P-256" {
		return nil, "", false
	}
	if _, private := jwk["d"]; private {
		return nil, "", false
	}
	xs, _ := jwk["x"].(string)
	ys, _ := jwk["y"].(string)
	x, okX := decodeB64(xs)
	y, okY := decodeB64(ys)
	if !okX || !okY || len(x) != 32 || len(y) != 32 {
		return nil, "", false
	}
	pub, err := ecdsa.ParseUncompressedPublicKey(elliptic.P256(), append(append([]byte{4}, x...), y...))
	if err != nil {
		return nil, "", false
	}
	canonical := fmt.Sprintf(`{"crv":"P-256","kty":"EC","x":"%s","y":"%s"}`,
		base64.RawURLEncoding.EncodeToString(x), base64.RawURLEncoding.EncodeToString(y))
	sum := sha256.Sum256([]byte(canonical))
	return pub, base64.RawURLEncoding.EncodeToString(sum[:]), true
}

// fieldRuntime is what the resolver and the middleware need when field workers are enabled.
type fieldRuntime struct {
	tokens      *FieldTokens
	permissions FieldPermissionMapper
}

var errFieldNeedsApplication = errors.New("FieldWorkers needs auth.Application: field tokens are addressed to the application's code")
