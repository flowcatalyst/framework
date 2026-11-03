package authtest

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"fmt"
	"maps"
	"net/http"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

// FieldAudience sets the application code the next field tokens are addressed to (aud). Default
// "test-app"; set it to the application under test's code, or let auth.Testing.FieldRequest fill it.
func (p *Provider) FieldAudience(code string) { p.set(func() { p.fieldAudience = code }) }

// FieldToken signs a field access token (contract section 15.8): RS256 with the published key,
// JOSE typ fc-field+jwt, valid for ten minutes, for the application set by [Provider.FieldAudience],
// with the given claims set over the defaults (nil removes one). The default token is bound to no
// device key; use [Provider.FieldTokenFor] or set cnf.
func (p *Provider) FieldToken(changes map[string]any) string {
	now := p.clock.Now()
	p.mu.Lock()
	aud := p.fieldAudience
	p.mu.Unlock()
	if aud == "" {
		aud = "test-app"
	}
	claims := map[string]any{
		"iss": p.Issuer(), "aud": aud, "sub": "fwu_TEST1", "token_use": "field",
		"client_id": "clt_TEST", "client_identifier": "test-client", "worker_code": "W001", "name": "Test Worker",
		"device_id": "fwd_TEST1", "device_mode": "PERSONAL", "sid": "fws_TEST1", "amr": []any{"pin"},
		"auth_time": now.Unix(), "iat": now.Unix(), "nbf": now.Unix(), "exp": now.Add(10 * time.Minute).Unix(),
		"jti": randomText(12),
	}
	apply(claims, changes)
	header := p.header()
	header["typ"] = "fc-field+jwt"
	return p.sign(header, claims, SigningNormal)
}

// FieldTokenFor is [Provider.FieldToken] bound to a device's key (cnf.jkt).
func (p *Provider) FieldTokenFor(d *FieldDevice, changes map[string]any) string {
	merged := map[string]any{"cnf": map[string]any{"jkt": d.JKT()}}
	maps.Copy(merged, changes)
	return p.FieldToken(merged)
}

// FieldDevice is a test device: an ES256 key that signs DPoP proofs (contract section 15.8).
type FieldDevice struct {
	key   *ecdsa.PrivateKey
	clock fw.Clock
}

// DeviceOption configures [NewFieldDevice].
type DeviceOption func(*FieldDevice)

// DeviceClock makes the proofs' iat follow a clock (default: the system clock).
func DeviceClock(c fw.Clock) DeviceOption { return func(d *FieldDevice) { d.clock = c } }

// NewFieldDevice returns a device with a new key.
func NewFieldDevice(opts ...DeviceOption) *FieldDevice {
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		panic(err)
	}
	d := &FieldDevice{key: key, clock: fw.SystemClock()}
	for _, o := range opts {
		o(d)
	}
	return d
}

func (d *FieldDevice) coords() (x, y string) {
	pub, err := d.key.PublicKey.Bytes() // uncompressed point
	if err != nil {
		panic(err)
	}
	return b64.EncodeToString(pub[1:33]), b64.EncodeToString(pub[33:])
}

// JKT is the RFC 7638 thumbprint of the device's public key: the token's cnf.jkt.
func (d *FieldDevice) JKT() string {
	x, y := d.coords()
	sum := sha256.Sum256(fmt.Appendf(nil, `{"crv":"P-256","kty":"EC","x":"%s","y":"%s"}`, x, y))
	return b64.EncodeToString(sum[:])
}

// ProofOption breaks or changes one thing about a proof.
type ProofOption func(*proofSpec)

type proofSpec struct {
	claims   map[string]any
	header   map[string]any
	signer   *FieldDevice
	tamper   bool
	noNonce  bool
	noJTI    bool
	jwkExtra map[string]any
}

// ProofHTM sets the htm claim (default: the method).
func ProofHTM(v string) ProofOption { return func(s *proofSpec) { s.claims["htm"] = v } }

// ProofHTU sets the htu claim (default: the URL).
func ProofHTU(v string) ProofOption { return func(s *proofSpec) { s.claims["htu"] = v } }

// ProofATH sets the ath claim (default: the hash of the token).
func ProofATH(v string) ProofOption { return func(s *proofSpec) { s.claims["ath"] = v } }

// ProofJTI sets the jti claim (default: random), for a replay.
func ProofJTI(v string) ProofOption { return func(s *proofSpec) { s.claims["jti"] = v } }

// ProofIssuedAt sets the iat claim (default: the device clock's time).
func ProofIssuedAt(t time.Time) ProofOption { return func(s *proofSpec) { s.claims["iat"] = t.Unix() } }

// ProofWithoutNonce leaves the nonce claim out.
func ProofWithoutNonce() ProofOption { return func(s *proofSpec) { s.noNonce = true } }

// ProofWithoutJTI leaves the jti claim out.
func ProofWithoutJTI() ProofOption { return func(s *proofSpec) { s.noJTI = true } }

// ProofSignedBy signs the proof with another device's key (and publishes that key): the thumbprint
// then differs from the token's cnf.jkt.
func ProofSignedBy(other *FieldDevice) ProofOption { return func(s *proofSpec) { s.signer = other } }

// ProofTamperedSignature flips one byte of the signature.
func ProofTamperedSignature() ProofOption { return func(s *proofSpec) { s.tamper = true } }

// ProofHeader sets (or, with nil, removes) a JOSE header member, for example typ or alg.
func ProofHeader(name string, v any) ProofOption { return func(s *proofSpec) { s.header[name] = v } }

// ProofJWKMember adds a member to the published jwk, for example "d" (a private member).
func ProofJWKMember(name string, v any) ProofOption {
	return func(s *proofSpec) { s.jwkExtra[name] = v }
}

// ProofFor is [FieldDevice.Proof] without options.
func (d *FieldDevice) ProofFor(method, url, nonce, token string) string {
	return d.Proof(method, url, nonce, token)
}

// Proof returns a valid DPoP proof for a request: method, the URL without query, the application
// nonce ("" for none) and the access token it accompanies; options break one rule each.
func (d *FieldDevice) Proof(method, url, nonce, token string, opts ...ProofOption) string {
	sum := sha256.Sum256([]byte(token))
	spec := &proofSpec{
		claims: map[string]any{
			"htm": method, "htu": url, "ath": b64.EncodeToString(sum[:]), "iat": d.clock.Now().Unix(), "jti": randomText(12),
		},
		header: map[string]any{"typ": "dpop+jwt", "alg": "ES256"}, jwkExtra: map[string]any{}, signer: d,
	}
	if nonce != "" {
		spec.claims["nonce"] = nonce
	}
	for _, o := range opts {
		o(spec)
	}
	if spec.noNonce {
		delete(spec.claims, "nonce")
	}
	if spec.noJTI {
		delete(spec.claims, "jti")
	}
	x, y := spec.signer.coords()
	jwk := map[string]any{"kty": "EC", "crv": "P-256", "x": x, "y": y}
	maps.Copy(jwk, spec.jwkExtra)
	header := map[string]any{"jwk": jwk}
	apply(header, spec.header)
	signed := part(header) + "." + part(spec.claims)
	digest := sha256.Sum256([]byte(signed))
	r, s, err := ecdsa.Sign(rand.Reader, spec.signer.key, digest[:])
	if err != nil {
		panic(err)
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	if spec.tamper {
		sig[10] ^= 1
	}
	return signed + "." + b64.EncodeToString(sig)
}

// NonceFrom reads the application nonce a response carries (the DPoP-Nonce header), as a client
// does after a first 401 use_dpop_nonce.
func NonceFrom(h http.Header) (string, bool) {
	n := h.Get("DPoP-Nonce")
	return n, n != ""
}
