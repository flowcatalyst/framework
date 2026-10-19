package auth

import (
	"context"
	"crypto/rsa"
	"encoding/json/v2"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"math/big"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

const (
	keysLast            = time.Hour
	refetchAtMostEvery  = time.Minute
	firstReadRetryAfter = 5 * time.Second
	maxPlatformBody     = 1_048_576
	minModulusBits      = 2048
)

// ErrPlatformUnavailable is wrapped by every failure to read the platform: its discovery
// document, its keys, its token endpoint. A token is then "unavailable", not invalid.
var ErrPlatformUnavailable = errors.New("auth: the platform cannot be reached")

func unavailable(format string, args ...any) error {
	return fmt.Errorf("%w: %s", ErrPlatformUnavailable, fmt.Sprintf(format, args...))
}

// discovery is what the platform's discovery document says, checked.
type discovery struct {
	authorizationEndpoint string
	tokenEndpoint         string
	jwksURI               string
	endSessionEndpoint    string // "" when none
}

// platform is the platform as a relying party sees it (contract sections 5.1 and 5.2): its
// discovery document, its signing keys, and form posts to its token endpoint. Only the server
// reads these; nothing a token says is ever fetched.
type platform struct {
	issuer    string
	issuerURL *url.URL
	timeout   time.Duration
	clock     fw.Clock
	http      *http.Client
	log       *slog.Logger
	allowHTTP bool // plain http endpoints are accepted (local and test only)

	fetch sync.Mutex // serialises reads of the platform (network)

	mu          sync.Mutex // guards the fields below; never held across a network call
	disc        *discovery
	keys        map[string]*rsa.PublicKey
	fetchedAt   *time.Time
	attemptedAt *time.Time
}

// normaliseIssuer removes trailing slashes.
func normaliseIssuer(issuer string) string { return strings.TrimRight(issuer, "/") }

func newPlatform(issuer string, timeout time.Duration, clock fw.Clock, log *slog.Logger, allowHTTP bool) (*platform, error) {
	issuer = normaliseIssuer(issuer)
	u, err := url.Parse(issuer)
	if err != nil || u.Hostname() == "" || (!strings.EqualFold(u.Scheme, "https") && !strings.EqualFold(u.Scheme, "http")) {
		return nil, fmt.Errorf("the issuer must be an absolute http(s) URL: %q", issuer)
	}
	if strings.EqualFold(u.Scheme, "http") && !allowHTTP {
		return nil, fmt.Errorf("the issuer %q is plain http, which NewPlatformTokens refuses by default: pass AllowPlainHTTPIn(env), which allows it only when APP_ENV is local or test, or AllowPlainHTTP", issuer)
	}
	if log == nil {
		log = slog.New(slog.DiscardHandler)
	}
	return &platform{
		issuer: issuer, issuerURL: u, allowHTTP: allowHTTP, timeout: timeout, clock: clock, log: log, keys: map[string]*rsa.PublicKey{},
		http: &http.Client{
			Timeout:       timeout,
			CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
		},
	}, nil
}

// discoveryDoc is the checked discovery document; read once, a failed read is not kept.
func (p *platform) discoveryDoc(ctx context.Context) (*discovery, error) {
	if d := p.cachedDiscovery(); d != nil {
		return d, nil
	}
	p.fetch.Lock()
	defer p.fetch.Unlock()
	return p.readDiscovery(ctx)
}

func (p *platform) cachedDiscovery() *discovery {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.disc
}

// readDiscovery is discoveryDoc with p.fetch held.
func (p *platform) readDiscovery(ctx context.Context) (*discovery, error) {
	if d := p.cachedDiscovery(); d != nil {
		return d, nil
	}
	// the read is shared by every caller and cached: a caller that hangs up must not cancel it
	// (each request is still bounded by the http client's timeout)
	doc, err := p.getJSON(context.WithoutCancel(ctx), p.issuer+"/.well-known/openid-configuration")
	if err != nil {
		return nil, err
	}
	if declared, _ := doc["issuer"].(string); declared != p.issuer {
		return nil, p.refused("its issuer is %q, not the configured %q", declared, p.issuer)
	}
	var d discovery
	for _, e := range []struct {
		name string
		to   *string
	}{
		{"authorization_endpoint", &d.authorizationEndpoint},
		{"token_endpoint", &d.tokenEndpoint},
		{"jwks_uri", &d.jwksURI},
	} {
		v, _ := doc[e.name].(string)
		if v == "" {
			return nil, p.refused("it names no %s", e.name)
		}
		u, err := url.Parse(v)
		if err != nil || u.Scheme == "" || u.Hostname() == "" {
			return nil, p.refused("%s is not an absolute URL", e.name)
		}
		*e.to = v
	}
	if !p.safeScheme(d.authorizationEndpoint) {
		return nil, p.refused("authorization_endpoint must be https")
	}
	if !p.sameOrigin(d.tokenEndpoint) || !p.sameOrigin(d.jwksURI) {
		return nil, p.refused("token_endpoint and jwks_uri must be on the issuer's origin")
	}
	if methods, ok := doc["code_challenge_methods_supported"].([]any); ok {
		s256 := false
		for _, m := range methods {
			if s, _ := m.(string); s == "S256" {
				s256 = true
			}
		}
		if !s256 {
			return nil, p.refused("it does not support PKCE S256")
		}
	}
	if end, _ := doc["end_session_endpoint"].(string); end != "" {
		// the browser is sent here after logout: https on the issuer's origin, nothing else
		if u, err := url.Parse(end); err == nil && p.safeScheme(end) && p.sameOrigin(end) && u.User == nil {
			d.endSessionEndpoint = end
		} else {
			p.log.Warn("the platform's end_session_endpoint is ignored: it must be https on the issuer's origin")
		}
	}
	p.mu.Lock()
	p.disc = &d
	p.mu.Unlock()
	return &d, nil
}

// safeScheme is true for https, and for http when plain http is allowed (local and test).
func (p *platform) safeScheme(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Scheme, "https") || (p.allowHTTP && strings.EqualFold(u.Scheme, "http"))
}

func (p *platform) sameOrigin(raw string) bool {
	u, err := url.Parse(raw)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Scheme, p.issuerURL.Scheme) && strings.EqualFold(u.Hostname(), p.issuerURL.Hostname()) &&
		port(u) == port(p.issuerURL)
}

func port(u *url.URL) string {
	if pt := u.Port(); pt != "" {
		return pt
	}
	if strings.EqualFold(u.Scheme, "https") {
		return "443"
	}
	return "80"
}

func (p *platform) refused(format string, args ...any) error {
	why := fmt.Sprintf(format, args...)
	p.log.Warn("the platform's discovery document is refused: " + why)
	return unavailable("the platform's discovery document is refused: %s", why)
}

// key is the signing key with this id. Keys are read on first use, again after an hour, and again
// when a token names an unknown key, at most once a minute; keys already read stay usable when a
// later read fails. It returns nil when the platform does not publish the key, and an error when
// no keys have ever been read and they cannot be.
func (p *platform) key(ctx context.Context, kid string) (*rsa.PublicKey, error) {
	now := p.clock.Now()
	p.mu.Lock()
	fetched, attempted, known := p.fetchedAt, p.attemptedAt, p.keys[kid] != nil
	p.mu.Unlock()
	switch {
	case fetched == nil:
		if err := p.refresh(ctx, true); err != nil {
			return nil, err
		}
	case !known || !now.Before(fetched.Add(keysLast)):
		if attempted == nil || !now.Before(attempted.Add(refetchAtMostEvery)) {
			if err := p.refresh(ctx, false); err != nil {
				p.log.Warn(fmt.Sprintf("the platform's keys could not be read again; keeping the keys already read: %v", err))
			}
		}
	}
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.keys[kid], nil
}

func (p *platform) refresh(ctx context.Context, firstTime bool) error {
	callerCtx := ctx
	// the keys are shared by every caller: a caller that hangs up must not cancel the read nor
	// start the back-off (each request is bounded by the http client's timeout)
	ctx = context.WithoutCancel(ctx)
	p.fetch.Lock()
	defer p.fetch.Unlock()
	now := p.clock.Now()
	p.mu.Lock()
	fetched, attempted := p.fetchedAt, p.attemptedAt
	p.mu.Unlock()
	if firstTime && fetched != nil {
		return nil
	}
	if firstTime && attempted != nil && now.Before(attempted.Add(firstReadRetryAfter)) {
		// the platform is down and no key was ever read: do not call it on every request
		return unavailable("the platform's keys could not be read moments ago")
	}
	if !firstTime && attempted != nil && now.Before(attempted.Add(refetchAtMostEvery)) {
		return nil
	}
	p.mu.Lock()
	p.attemptedAt = &now
	p.mu.Unlock()
	// a failure while the caller itself is gone says nothing about the platform: no back-off
	forgive := func(err error) error {
		if callerCtx.Err() != nil {
			p.mu.Lock()
			p.attemptedAt = attempted
			p.mu.Unlock()
		}
		return err
	}
	d, err := p.readDiscovery(ctx)
	if err != nil {
		return forgive(err)
	}
	set, err := p.getJSON(ctx, d.jwksURI)
	if err != nil {
		return forgive(err)
	}
	read := map[string]*rsa.PublicKey{}
	list, _ := set["keys"].([]any)
	for _, e := range list {
		jwk, ok := e.(map[string]any)
		if !ok {
			continue
		}
		if k := p.rsaKey(jwk); k != nil {
			kid, _ := jwk["kid"].(string)
			read[kid] = k
		}
	}
	p.mu.Lock()
	p.keys, p.fetchedAt = read, &now
	p.mu.Unlock()
	return nil
}

// rsaKey reads one JWK: only RSA, use absent or sig, alg absent or RS256, a non-empty kid and a
// modulus of at least 2048 bits are kept.
func (p *platform) rsaKey(jwk map[string]any) *rsa.PublicKey {
	if kty, _ := jwk["kty"].(string); kty != "RSA" {
		return nil
	}
	use, alg := "sig", "RS256"
	if v, ok := jwk["use"]; ok {
		use, _ = v.(string)
	}
	if v, ok := jwk["alg"]; ok {
		alg, _ = v.(string)
	}
	kid, _ := jwk["kid"].(string)
	if use != "sig" || alg != "RS256" || kid == "" {
		return nil
	}
	nb, ok1 := decodeB64(strField(jwk, "n"))
	eb, ok2 := decodeB64(strField(jwk, "e"))
	if !ok1 || !ok2 {
		p.log.Warn("ignored the platform key " + kid + ": it cannot be read")
		return nil
	}
	n := new(big.Int).SetBytes(nb)
	e := new(big.Int).SetBytes(eb)
	if n.BitLen() < minModulusBits || e.Sign() <= 0 || !e.IsInt64() || e.Int64() > 1<<31-1 {
		p.log.Warn(fmt.Sprintf("ignored the platform key %s: modulus under %d bits or bad exponent", kid, minModulusBits))
		return nil
	}
	return &rsa.PublicKey{N: n, E: int(e.Int64())}
}

func strField(m map[string]any, name string) string { s, _ := m[name].(string); return s }

// answer is a form post's answer.
type answer struct {
	status int
	json   map[string]any // nil when the body was not a JSON object
}

// postForm posts a form; any status is returned, a body that is not JSON gives a nil object.
func (p *platform) postForm(ctx context.Context, endpoint string, form url.Values, authorization string) (answer, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, endpoint, strings.NewReader(form.Encode()))
	if err != nil {
		return answer{}, unavailable("POST %s failed", pathOf(endpoint))
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("Accept", "application/json")
	if authorization != "" {
		req.Header.Set("Authorization", authorization)
	}
	status, body, err := p.send(req)
	if err != nil {
		return answer{}, err
	}
	var obj map[string]any
	if json.Unmarshal(body, &obj) != nil {
		obj = nil
	}
	return answer{status: status, json: obj}, nil
}

func (p *platform) getJSON(ctx context.Context, endpoint string) (map[string]any, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, http.NoBody)
	if err != nil {
		return nil, unavailable("GET %s failed", pathOf(endpoint))
	}
	req.Header.Set("Accept", "application/json")
	status, body, err := p.send(req)
	if err != nil {
		return nil, err
	}
	if status != http.StatusOK {
		return nil, unavailable("GET %s answered %d", pathOf(endpoint), status)
	}
	var obj map[string]any
	if err := json.Unmarshal(body, &obj); err != nil || obj == nil {
		return nil, unavailable("GET %s did not answer with a JSON object", pathOf(endpoint))
	}
	return obj, nil
}

func (p *platform) send(req *http.Request) (int, []byte, error) {
	resp, err := p.http.Do(req)
	if err != nil || resp == nil {
		return 0, nil, unavailable("%s %s failed", req.Method, req.URL.Path)
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, maxPlatformBody+1))
	if err != nil {
		return 0, nil, unavailable("the platform's answer could not be read")
	}
	if len(b) > maxPlatformBody {
		return 0, nil, unavailable("the platform's answer is over %d bytes", maxPlatformBody)
	}
	return resp.StatusCode, b, nil
}

func pathOf(raw string) string {
	if u, err := url.Parse(raw); err == nil {
		return u.Path
	}
	return ""
}
