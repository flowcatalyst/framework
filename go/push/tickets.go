package push

import (
	"crypto/ed25519"
	"encoding/base64"
	"encoding/json/v2"
	"errors"
	"fmt"
	"strings"
	"time"
)

// Ticket facts of push contract section 6.4.
const (
	// TicketAudience is every ticket's aud.
	TicketAudience = "fw-push"
	// TicketTTL is how long a minted ticket may be used to subscribe (exp - iat).
	TicketTTL = 2 * time.Minute
	// MaxTicketTTL is the longest exp - iat a ticket may have.
	MaxTicketTTL = 5 * time.Minute
	// MaxTicketTopics is the most patterns one ticket carries.
	MaxTicketTopics = 100
)

// The reasons a ticket grants nothing (conformance/push/tickets.json); its topics are denied with
// TICKET_INVALID.
const (
	reasonAlg            = "alg"
	reasonKid            = "kid"
	reasonSignature      = "signature"
	reasonIss            = "iss"
	reasonAud            = "aud"
	reasonTimes          = "times"
	reasonExpired        = "expired"
	reasonClient         = "client"
	reasonTopics         = "topics"
	reasonOutsideIss     = "topic-outside-iss"
	reasonCnf            = "cnf"
	reasonStreamExp      = "stream-exp"
	reasonClientMismatch = "client-mismatch"
)

// errMalformedTicket is a ticket that is not a JWS compact with JSON header and claims: 400.
var errMalformedTicket = errors.New("a ticket is malformed")

type ticketKey struct {
	kid     string
	private ed25519.PrivateKey
	public  ed25519.PublicKey
}

// ticketKeys are push.ticket.key: the signing key first, then the previous one (verification only).
type ticketKeys struct {
	keys []ticketKey
}

// parseTicketKeys reads "<kid>:<base64url of a 32-byte Ed25519 seed>", comma-separated, the
// signing key first, at most two, kids [A-Za-z0-9._-]{1,64} and distinct.
func parseTicketKeys(text string) (*ticketKeys, error) {
	var out ticketKeys
	for entry := range strings.SplitSeq(text, ",") {
		entry = strings.TrimSpace(entry)
		kid, seed, ok := strings.Cut(entry, ":")
		if !ok || !validKid(kid) {
			return nil, errors.New("each key is <kid>:<base64url of a 32-byte Ed25519 seed>, the kid [A-Za-z0-9._-]{1,64}")
		}
		raw, err := base64.RawURLEncoding.DecodeString(seed)
		if err != nil || len(raw) != ed25519.SeedSize {
			return nil, fmt.Errorf("key '%s' is not the base64url of a 32-byte Ed25519 seed", kid)
		}
		for _, k := range out.keys {
			if k.kid == kid {
				return nil, fmt.Errorf("the kid '%s' is given twice", kid)
			}
		}
		priv := ed25519.NewKeyFromSeed(raw)
		pub, _ := priv.Public().(ed25519.PublicKey)
		out.keys = append(out.keys, ticketKey{kid: kid, private: priv, public: pub})
	}
	if len(out.keys) > 2 {
		return nil, errors.New("at most two keys: the current one and the previous one")
	}
	return &out, nil
}

func validKid(s string) bool {
	if s == "" || len(s) > 64 {
		return false
	}
	for i := range len(s) {
		c := s[i]
		ok := (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
		if !ok {
			return false
		}
	}
	return true
}

func (k *ticketKeys) byKid(kid string) (ticketKey, bool) {
	for _, key := range k.keys {
		if key.kid == kid {
			return key, true
		}
	}
	return ticketKey{}, false
}

type jwk struct {
	Kty string `json:"kty"`
	Crv string `json:"crv"`
	Kid string `json:"kid"`
	X   string `json:"x"`
	Alg string `json:"alg"`
	Use string `json:"use"`
}

// jwks is GET {base}/push/keys: the public keys, current first.
func (k *ticketKeys) jwks() ([]byte, error) {
	set := struct {
		Keys []jwk `json:"keys"`
	}{Keys: []jwk{}}
	for _, key := range k.keys {
		set.Keys = append(set.Keys, jwk{
			Kty: "OKP", Crv: "Ed25519", Kid: key.kid,
			X: base64.RawURLEncoding.EncodeToString(key.public), Alg: "EdDSA", Use: "sig",
		})
	}
	return json.Marshal(set)
}

type jwsHeader struct {
	Alg string `json:"alg"`
	Kid string `json:"kid"`
}

type cnfClaim struct {
	JKT string `json:"jkt"`
}

// ticketClaims are a ticket's claims, in the order they are written.
type ticketClaims struct {
	Iss       string    `json:"iss"`
	Aud       string    `json:"aud"`
	Client    string    `json:"client"`
	Topics    []string  `json:"topics"`
	Sub       string    `json:"sub,omitempty"`
	Cnf       *cnfClaim `json:"cnf,omitzero"`
	Iat       int64     `json:"iat"`
	Exp       int64     `json:"exp"`
	StreamExp int64     `json:"stream-exp,omitzero"`
}

// sign returns the claims as a JWS compact, EdDSA, with the signing key's kid.
func (k *ticketKeys) sign(c ticketClaims) (string, error) {
	key := k.keys[0]
	header, err := json.Marshal(jwsHeader{Alg: "EdDSA", Kid: key.kid})
	if err != nil {
		return "", err
	}
	claims, err := json.Marshal(c)
	if err != nil {
		return "", err
	}
	signed := b64(header) + "." + b64(claims)
	return signed + "." + b64(ed25519.Sign(key.private, []byte(signed))), nil
}

func b64(b []byte) string { return base64.RawURLEncoding.EncodeToString(b) }

// ticket is a checked ticket. An invalid one grants nothing; pats then holds the patterns it names
// (unverified), so its topics are denied TICKET_INVALID rather than TOPIC_FORBIDDEN.
type ticket struct {
	valid     bool
	reason    string
	client    string
	sub       string
	pats      []pattern
	streamExp time.Time
}

// ticketCheck is what a ticket is checked against.
type ticketCheck struct {
	application string        // the host's application code: the only issuer it knows
	now         time.Time     // the host's clock
	proofKey    string        // the thumbprint of the request's verified DPoP key, "" for none
	maxLifetime time.Duration // push.stream.max-lifetime: the default stream-exp is exp + it
}

// check verifies one ticket (push contract 6.4). It returns errMalformedTicket for a ticket that is
// not a JWS compact with a JSON header and JSON claims.
func (k *ticketKeys) check(raw string, c ticketCheck) (ticket, error) {
	parts := strings.Split(raw, ".")
	if len(parts) != 3 || len(raw) > 64*1024 {
		return ticket{}, errMalformedTicket
	}
	headerJSON, err1 := base64.RawURLEncoding.DecodeString(parts[0])
	claimsJSON, err2 := base64.RawURLEncoding.DecodeString(parts[1])
	sig, err3 := base64.RawURLEncoding.DecodeString(parts[2])
	var header, claims map[string]any
	if err1 != nil || err2 != nil || err3 != nil ||
		json.Unmarshal(headerJSON, &header) != nil || json.Unmarshal(claimsJSON, &claims) != nil || header == nil || claims == nil {
		return ticket{}, errMalformedTicket
	}
	t := ticket{pats: namedPatterns(claims["topics"])}
	bad := func(reason string) (ticket, error) {
		t.valid, t.reason = false, reason
		return t, nil
	}
	if alg, _ := header["alg"].(string); alg != "EdDSA" {
		return bad(reasonAlg)
	}
	if _, crit := header["crit"]; crit {
		return bad(reasonAlg)
	}
	kid, _ := header["kid"].(string)
	key, ok := k.byKid(kid)
	if !ok {
		return bad(reasonKid)
	}
	if len(sig) != ed25519.SignatureSize || !ed25519.Verify(key.public, []byte(parts[0]+"."+parts[1]), sig) {
		return bad(reasonSignature)
	}
	if iss, _ := claims["iss"].(string); iss != c.application {
		return bad(reasonIss)
	}
	if aud, _ := claims["aud"].(string); aud != TicketAudience {
		return bad(reasonAud)
	}
	iat, okIat := seconds(claims["iat"])
	exp, okExp := seconds(claims["exp"])
	if !okIat || !okExp || !exp.After(iat) || exp.Sub(iat) > MaxTicketTTL || iat.After(c.now.Add(time.Minute)) {
		return bad(reasonTimes)
	}
	if !c.now.Before(exp) {
		return bad(reasonExpired)
	}
	client, _ := claims["client"].(string)
	if !validClient(client) {
		return bad(reasonClient)
	}
	topics, ok := claims["topics"].([]any)
	if !ok || len(topics) == 0 || len(topics) > MaxTicketTopics || len(t.pats) != len(topics) {
		return bad(reasonTopics)
	}
	for _, p := range t.pats {
		if p.application() != c.application {
			return bad(reasonOutsideIss)
		}
	}
	if cnf, has := claims["cnf"]; has {
		m, _ := cnf.(map[string]any)
		jkt, _ := m["jkt"].(string)
		if jkt == "" || c.proofKey == "" || jkt != c.proofKey {
			return bad(reasonCnf)
		}
	}
	t.streamExp = exp.Add(c.maxLifetime)
	if v, has := claims["stream-exp"]; has {
		se, ok := seconds(v)
		if !ok || !c.now.Before(se) {
			return bad(reasonStreamExp)
		}
		t.streamExp = se
	}
	t.client = client
	t.sub, _ = claims["sub"].(string)
	t.valid = true
	return t, nil
}

// namedPatterns reads the topics claim as patterns, leaving out any that is not one.
func namedPatterns(v any) []pattern {
	list, _ := v.([]any)
	var out []pattern
	for _, item := range list {
		s, _ := item.(string)
		if p, err := parsePattern(s); err == nil {
			out = append(out, p)
		}
	}
	return out
}

// seconds reads a NumericDate.
func seconds(v any) (time.Time, bool) {
	f, ok := v.(float64)
	if !ok || f < 0 || f > 1e11 {
		return time.Time{}, false
	}
	return time.Unix(int64(f), 0).UTC(), true
}

// validClient checks a client value: [A-Za-z0-9._-]{1,100}, as fw.ClientID.
func validClient(s string) bool {
	if s == "" || len(s) > 100 {
		return false
	}
	for i := range len(s) {
		c := s[i]
		ok := (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-'
		if !ok {
			return false
		}
	}
	return true
}
