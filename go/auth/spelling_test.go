package auth

import (
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"encoding/json/v2"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/b64url"
	"github.com/flowcatalyst/framework/go/internal/b64url/b64urltest"
)

// The token-spelling contract (docs/contracts/token-spelling.md) for the auth module: the vectors
// Java writes (conformance/auth/spelling.json), the values issued before the rule
// (conformance/auth/issued-before.json), values issued fresh here, and fuzz targets.

type spellingVector struct {
	AccessToken struct {
		Issuer string `json:"issuer"`
		Now    string `json:"now"`
		JWKS   struct {
			Keys []map[string]any `json:"keys"`
		} `json:"jwks"`
		b64urltest.Cases
	} `json:"accessToken"`
	Field struct {
		Issuer  string `json:"issuer"`
		AppCode string `json:"appCode"`
		Method  string `json:"method"`
		URL     string `json:"url"`
		Now     string `json:"now"`
		Token   struct {
			Proof string `json:"proof"`
			b64urltest.Cases
		} `json:"token"`
		TokenOnly b64urltest.Cases `json:"tokenOnly"`
		Proof     struct {
			Token string `json:"token"`
			b64urltest.Cases
		} `json:"proof"`
	} `json:"field"`
	FieldNonces struct {
		CookieKeys string `json:"cookieKeys"`
		Cases      []struct {
			Now string `json:"now"`
			b64urltest.Cases
		} `json:"cases"`
	} `json:"fieldNonces"`
	SealedCookies struct {
		CookieKeys string `json:"cookieKeys"`
		Cases      []struct {
			Kind      string `json:"kind"`
			Plaintext string `json:"plaintext"`
			b64urltest.Cases
		} `json:"cases"`
	} `json:"sealedCookies"`
}

func readSpelling(t testing.TB) spellingVector {
	t.Helper()
	var v spellingVector
	b64urltest.Read(t, "auth/spelling.json", &v)
	return v
}

func at(t testing.TB, s string) time.Time {
	t.Helper()
	now, err := time.Parse(time.RFC3339, s)
	if err != nil {
		t.Fatal(err)
	}
	return now
}

// fixedPlatform is a platform whose keys are "already read": nothing is fetched.
func fixedPlatform(t testing.TB, issuer string, clock *fwtest.Clock, jwks []map[string]any) *platform {
	t.Helper()
	p, err := newPlatform(issuer, time.Second, clock, nil, false)
	if err != nil {
		t.Fatal(err)
	}
	for _, k := range jwks {
		kid, _ := k["kid"].(string)
		p.keys[kid] = p.rsaKey(k)
		if p.keys[kid] == nil {
			t.Fatalf("key %s cannot be read", kid)
		}
	}
	now := clock.Now()
	p.fetchedAt = &now
	return p
}

func accessAnswer(r TokenResult) string {
	switch r := r.(type) {
	case Valid:
		return "valid"
	case Invalid:
		return r.Reason
	case Unavailable:
		return "unavailable"
	}
	return "?"
}

func fieldAnswer(r FieldResult) string {
	switch r := r.(type) {
	case FieldValid:
		return "valid"
	case FieldInvalid:
		return r.Reason
	case FieldNonceRequired:
		return "nonce_required"
	case FieldUnavailable:
		return "unavailable"
	}
	return "?"
}

func (v spellingVector) accessVerifier(t testing.TB) func(string) string {
	a := v.AccessToken
	clock := fwtest.NewClock(at(t, a.Now))
	tokens := &PlatformTokens{platform: fixedPlatform(t, a.Issuer, clock, a.JWKS.Keys), clock: clock}
	return func(token string) string { return accessAnswer(tokens.Verify(context.Background(), token)) }
}

// fieldVerifier checks as conformance/field is checked (15.9): no application nonce, a five-minute
// window, a fresh replay cache per check.
func (v spellingVector) fieldVerifier(t testing.TB) func(token, proof string) string {
	f := v.Field
	var jwks struct {
		Keys []map[string]any `json:"keys"`
	}
	b64urltest.Read(t, "field/jwks.json", &jwks)
	clock := fwtest.NewClock(at(t, f.Now))
	p := fixedPlatform(t, f.Issuer, clock, jwks.Keys)
	htu, _, _ := strings.Cut(f.URL, "?")
	return func(token, proof string) string {
		tokens := &FieldTokens{
			platform: p, clock: clock, appCode: f.AppCode, baseURL: "https://app.example.test",
			replay: NewMemoryFieldReplayCache(clock), skipNonce: true, window: 5 * time.Minute,
		}
		return fieldAnswer(tokens.verify(context.Background(), token, proof, f.Method, htu))
	}
}

// fieldTokenVerifier checks the field token alone (the proof binds to the token's exact text, so
// with it a re-spelled token is refused for that even by a lenient decoder).
func (v spellingVector) fieldTokenVerifier(t testing.TB) func(string) string {
	f := v.Field
	var jwks struct {
		Keys []map[string]any `json:"keys"`
	}
	b64urltest.Read(t, "field/jwks.json", &jwks)
	clock := fwtest.NewClock(at(t, f.Now))
	p := fixedPlatform(t, f.Issuer, clock, jwks.Keys)
	tokens := &FieldTokens{platform: p, clock: clock, appCode: f.AppCode}
	return func(token string) string {
		c, err := verifyJWT(context.Background(), token, p)
		switch {
		case err != nil:
			return "unavailable"
		case c.reason != "":
			return c.reason
		}
		if typ, _ := object(strings.SplitN(token, ".", 2)[0])["typ"].(string); typ != fieldTokenType {
			return "wrong_typ"
		}
		if _, _, reason := tokens.checkClaims(c.claims, clock.Now()); reason != "" {
			return reason
		}
		return "valid"
	}
}

func TestSpellingVectors(t *testing.T) {
	t.Parallel()
	v := readSpelling(t)
	ran := 0
	t.Run("access token", func(t *testing.T) { ran += v.AccessToken.Run(t, v.accessVerifier(t)) })
	field := v.fieldVerifier(t)
	t.Run("field token", func(t *testing.T) {
		ran += v.Field.Token.Run(t, func(token string) string { return field(token, v.Field.Token.Proof) })
	})
	t.Run("field token alone", func(t *testing.T) { ran += v.Field.TokenOnly.Run(t, v.fieldTokenVerifier(t)) })
	t.Run("field proof", func(t *testing.T) {
		ran += v.Field.Proof.Run(t, func(proof string) string { return field(v.Field.Proof.Token, proof) })
	})
	keys, err := ParseCookieKeys(v.FieldNonces.CookieKeys)
	if err != nil {
		t.Fatal(err)
	}
	nonces, err := newFieldNonces(keys)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range v.FieldNonces.Cases {
		now := at(t, c.Now)
		t.Run("nonce at "+c.Now, func(t *testing.T) {
			ran += c.Run(t, func(n string) string {
				if nonces.valid(n, now) {
					return "valid"
				}
				return "invalid"
			})
		})
	}
	sealing, err := ParseCookieKeys(v.SealedCookies.CookieKeys)
	if err != nil {
		t.Fatal(err)
	}
	for i, c := range v.SealedCookies.Cases {
		t.Run("sealed cookie "+string(rune('a'+i)), func(t *testing.T) {
			ran += c.Run(t, func(value string) string {
				plain, ok := sealing.open(Kind(c.Kind), value)
				if !ok {
					return "refused"
				}
				if string(plain) != c.Plaintext {
					t.Fatalf("opened to %q", plain)
				}
				return "opens"
			})
		})
	}
	if len(v.AccessToken.Resigned) < 50 || len(v.Field.TokenOnly.Respelled) < 20 {
		t.Fatalf("%d re-signed access tokens, %d re-spelled field tokens", len(v.AccessToken.Resigned), len(v.Field.TokenOnly.Respelled))
	}
	if ran < 480 {
		t.Fatalf("only %d checks ran", ran)
	}
}

// TestValuesIssuedBeforeTheRuleStillVerify: sealed cookies and nonces the Go and Java code issued at
// 2360d29, before the rule (conformance/auth/issued-before.json).
func TestValuesIssuedBeforeTheRuleStillVerify(t *testing.T) {
	t.Parallel()
	var before struct {
		CookieKeys    string `json:"cookieKeys"`
		SealedCookies []struct {
			IssuedBy  string `json:"issuedBy"`
			Kind      string `json:"kind"`
			Plaintext string `json:"plaintext"`
			Value     string `json:"value"`
		} `json:"sealedCookies"`
		FieldNonces []struct {
			Now   string `json:"now"`
			Nonce string `json:"nonce"`
		} `json:"fieldNonces"`
	}
	b64urltest.Read(t, "auth/issued-before.json", &before)
	keys, err := ParseCookieKeys(before.CookieKeys)
	if err != nil {
		t.Fatal(err)
	}
	for _, s := range before.SealedCookies {
		plain, ok := keys.open(Kind(s.Kind), s.Value)
		if !ok || string(plain) != s.Plaintext {
			t.Errorf("%s sealed by %s does not open (%v, %q)", s.Kind, s.IssuedBy, ok, plain)
		}
	}
	nonces, err := newFieldNonces(keys)
	if err != nil {
		t.Fatal(err)
	}
	for _, n := range before.FieldNonces {
		if !nonces.valid(n.Nonce, at(t, n.Now)) {
			t.Errorf("nonce %s is not valid at %s", n.Nonce, n.Now)
		}
	}
	if len(before.SealedCookies) != 10 || len(before.FieldNonces) != 2 {
		t.Fatalf("%d sealed values, %d nonces", len(before.SealedCookies), len(before.FieldNonces))
	}
}

// ---- values issued fresh -------------------------------------------------------------------------

func TestSpellingOfFreshAccessAndIDTokens(t *testing.T) {
	t.Parallel()
	f := newRP(t, "")
	tokens := &PlatformTokens{platform: f.rp.platform, clock: f.clock}
	verify := func(token string) string { return accessAnswer(tokens.Verify(context.Background(), token)) }
	for range 4 {
		b64urltest.Fresh(t, f.provider.AccessToken(nil), 2, "valid", verify)
	}
	for range 4 {
		idToken, nonce, _ := f.login()
		b64urltest.Fresh(t, idToken, 2, "", func(token string) string {
			v, err := f.rp.verify(context.Background(), token, nonce)
			if err != nil {
				t.Fatal(err)
			}
			return v.reason
		})
	}
}

// proofBy signs a proof whose jwk carries x and y exactly as given (so they can be spelled any
// way), with headerSpaces and claimSpaces spaces after the JSON of the header and the claims (they
// change the lengths, so the parts take every residue mod 4).
func proofBy(t testing.TB, key *ecdsa.PrivateKey, x, y, htu, token string, iat time.Time, headerSpaces, claimSpaces int) string {
	t.Helper()
	sum := sha256.Sum256([]byte(token))
	header, err := json.Marshal(map[string]any{"typ": "dpop+jwt", "alg": "ES256", "jwk": map[string]any{"kty": "EC", "crv": "P-256", "x": x, "y": y}})
	if err != nil {
		t.Fatal(err)
	}
	claims, err := json.Marshal(map[string]any{"htm": "POST", "htu": htu, "iat": iat.Unix(), "jti": "j-1", "ath": b64url.Encode(sum[:])})
	if err != nil {
		t.Fatal(err)
	}
	signed := b64url.Encode(append(header, strings.Repeat(" ", headerSpaces)...)) + "." + b64url.Encode(append(claims, strings.Repeat(" ", claimSpaces)...))
	return signed + "." + es256(t, key, signed)
}

func es256(t testing.TB, key *ecdsa.PrivateKey, signed string) string {
	t.Helper()
	digest := sha256.Sum256([]byte(signed))
	r, s, err := ecdsa.Sign(rand.Reader, key, digest[:])
	if err != nil {
		t.Fatal(err)
	}
	sig := make([]byte, 64)
	r.FillBytes(sig[:32])
	s.FillBytes(sig[32:])
	return b64url.Encode(sig)
}

// TestAProofWhoseHeaderOrClaimsAreRespelledAndSignedAgainIsRefused: the signature is valid over the
// new text, so a lenient decoder would accept each of these and only the spelling rule refuses them.
func TestAProofWhoseHeaderOrClaimsAreRespelledAndSignedAgainIsRefused(t *testing.T) {
	t.Parallel()
	const htu, token = "https://app.example.test/api/orders", "the.access.token"
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	pub, err := key.PublicKey.Bytes()
	if err != nil {
		t.Fatal(err)
	}
	x, y := b64url.Encode(pub[1:33]), b64url.Encode(pub[33:])
	checked := 0
	for spaces := range 3 {
		for which := range 2 {
			issued := proofBy(t, key, x, y, htu, token, time.Now(), spaces*(1-which), spaces*which)
			if _, reason := parseProof(issued); reason != "" {
				t.Fatalf("as issued: %q", reason)
			}
			for _, s := range b64urltest.Resigned(t, issued, func(signed string) string { return es256(t, key, signed) }) {
				if _, reason := parseProof(s.Value); reason != "invalid_proof" {
					t.Errorf("%s (%d spaces in part %d): %q", s.Name, spaces, which, reason)
				}
				checked++
			}
		}
	}
	if checked < 40 {
		t.Fatalf("only %d cases", checked)
	}
}

func TestSpellingOfFreshProofsAndTheirKeys(t *testing.T) {
	t.Parallel()
	const htu, token = "https://app.example.test/api/orders", "the.access.token"
	for range 4 {
		key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			t.Fatal(err)
		}
		pub, err := key.PublicKey.Bytes()
		if err != nil {
			t.Fatal(err)
		}
		x, y := b64url.Encode(pub[1:33]), b64url.Encode(pub[33:])
		check := func(proof string) string {
			_, reason := parseProof(proof)
			if reason == "" {
				return "valid"
			}
			return reason
		}
		issued := proofBy(t, key, x, y, htu, token, time.Now(), 0, 0)
		b64urltest.Fresh(t, issued, 2, "valid", check)
		// the key's coordinates re-spelled in a proof the device itself signed: the thumbprint is
		// taken over the decoded bytes and so is the same; only the spelling rule refuses it
		var keys [][2]string
		for _, o := range b64urltest.LastCharacterSpellings(x) {
			keys = append(keys, [2]string{o, y})
		}
		for _, o := range b64urltest.LastCharacterSpellings(y) {
			keys = append(keys, [2]string{x, o})
		}
		keys = append(keys, [2]string{x + "=", y}, [2]string{x, y + "="}, [2]string{x[:20] + "\n" + x[20:], y}, [2]string{" " + x, y})
		if len(keys) != 3+3+4 {
			t.Fatalf("%d key spellings", len(keys))
		}
		for _, k := range keys {
			if got := check(proofBy(t, key, k[0], k[1], htu, token, time.Now(), 0, 0)); got != "invalid_proof" {
				t.Errorf("x %q y %q: %q", k[0], k[1], got)
			}
		}
	}
}

func TestSpellingOfFreshSealedCookiesAndNonces(t *testing.T) {
	t.Parallel()
	text, err := GenerateCookieKey("k1")
	if err != nil {
		t.Fatal(err)
	}
	keys, err := ParseCookieKeys(text)
	if err != nil {
		t.Fatal(err)
	}
	spare := map[int]int{}
	for n := range 31 {
		plain := make([]byte, n)
		_, _ = rand.Read(plain)
		sealed, err := keys.seal(KindSession, plain)
		if err != nil {
			t.Fatal(err)
		}
		spare[b64urltest.SpareBits(len(strings.Split(sealed, ".")[2]))]++
		b64urltest.Fresh(t, sealed, 2, "opens", func(v string) string {
			got, ok := keys.open(KindSession, v)
			if !ok {
				return "refused"
			}
			if !slices.Equal(got, plain) {
				t.Fatalf("opened to other bytes")
			}
			return "opens"
		})
	}
	if spare[0] == 0 || spare[2] == 0 || spare[4] == 0 {
		t.Fatalf("not every residue was tried: %v", spare)
	}
	nonces, err := newFieldNonces(keys)
	if err != nil {
		t.Fatal(err)
	}
	for minute := 0; minute < 60; minute += 7 {
		now := start.Add(time.Duration(minute) * time.Minute)
		b64urltest.Fresh(t, nonces.issue(now), 0, "valid", func(n string) string {
			if nonces.valid(n, now) {
				return "valid"
			}
			return "invalid"
		})
	}
}

// TestADPoPProofIsNotTrimmed: the proof header value is checked as received; net/http has already
// dropped the whitespace around it, so a proof with any left is not one the device sent.
func TestADPoPProofIsNotTrimmed(t *testing.T) {
	t.Parallel()
	v := readSpelling(t)
	field := v.fieldVerifier(t)
	if got := field(v.Field.Proof.Token, " "+v.Field.Proof.Issued); got != "invalid_proof" {
		t.Fatalf("got %q", got)
	}
	if got := field(v.Field.Proof.Token, "  "); got != "missing_proof" {
		t.Fatalf("a blank proof is still missing: %q", got)
	}
}

// ---- fuzz: whatever a verifier accepts is spelled canonically --------------------------------

func FuzzAccessTokenSpelling(f *testing.F) {
	v := readSpelling(f)
	f.Add(v.AccessToken.Issued)
	for _, s := range append(v.AccessToken.Respelled, v.AccessToken.Tampered...) {
		f.Add(s.Value)
	}
	verify := v.accessVerifier(f)
	f.Fuzz(func(t *testing.T, token string) {
		if verify(token) == "valid" && !b64urltest.SameAsReencoded(token) {
			t.Fatalf("accepted a token that is not spelled canonically: %q", token)
		}
	})
}

func FuzzProofSpelling(f *testing.F) {
	v := readSpelling(f)
	f.Add(v.Field.Proof.Issued)
	for _, s := range append(v.Field.Proof.Respelled, v.Field.Proof.Tampered...) {
		f.Add(s.Value)
	}
	f.Fuzz(func(t *testing.T, proof string) {
		if _, reason := parseProof(proof); reason == "" && !b64urltest.SameAsReencoded(proof) {
			t.Fatalf("accepted a proof that is not spelled canonically: %q", proof)
		}
	})
}

func FuzzSealedCookieSpelling(f *testing.F) {
	v := readSpelling(f)
	keys, err := ParseCookieKeys(v.SealedCookies.CookieKeys)
	if err != nil {
		f.Fatal(err)
	}
	for _, c := range v.SealedCookies.Cases {
		f.Add(c.Kind, c.Issued)
		for _, s := range c.Respelled {
			f.Add(c.Kind, s.Value)
		}
	}
	f.Fuzz(func(t *testing.T, kind, value string) {
		if _, ok := keys.open(Kind(kind), value); ok && !b64urltest.SameAsReencoded(strings.SplitN(value, ".", 3)[2]) {
			t.Fatalf("opened a value that is not spelled canonically: %q", value)
		}
	})
}
