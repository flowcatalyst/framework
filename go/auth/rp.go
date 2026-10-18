package auth

import (
	"context"
	"crypto/sha256"
	"encoding/base64"
	"net/url"
	"regexp"
	"strings"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
)

var errorCode = regexp.MustCompile(`^[a-z_]{1,64}$`)

// relyingParty is the OIDC relying party (contract sections 4.1, 5.4 and 5.5): the authorize URL,
// the code exchange and the id_token checks. A public client sends no secret; a confidential one
// uses HTTP Basic.
type relyingParty struct {
	platform     *platform
	clientID     string
	clientSecret string // "" for a PUBLIC client
	redirectURI  string
	scope        string
	theme        string // "" for none
	clock        fw.Clock
}

// exchange is the token endpoint's answer to a code: the id_token, or the platform's error code.
type exchange struct {
	idToken string
	refused string
}

// verified is the id_token checks' outcome: the principal and auth time, or the refusal reason.
type verified struct {
	principal Principal
	authTime  time.Time
	reason    string
}

// challenge is the PKCE challenge of a verifier: base64url of SHA-256 of its ASCII bytes.
func challenge(verifier string) string {
	sum := sha256.Sum256([]byte(verifier))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

// loginData is a login in progress (contract section 6.2).
type loginData struct {
	state, nonce, codeVerifier, returnTo string
	createdAt                            time.Time
}

// authorizeURL is the redirect that starts a login.
func (rp *relyingParty) authorizeURL(ctx context.Context, login loginData) (string, error) {
	d, err := rp.platform.discoveryDoc(ctx)
	if err != nil {
		return "", err
	}
	params := [][2]string{
		{"response_type", "code"},
		{"client_id", rp.clientID},
		{"redirect_uri", rp.redirectURI},
		{"scope", rp.scope},
		{"state", login.state},
		{"nonce", login.nonce},
		{"code_challenge", challenge(login.codeVerifier)},
		{"code_challenge_method", "S256"},
	}
	if rp.theme != "" {
		params = append(params, [2]string{"client", rp.theme})
	}
	return withQuery(d.authorizationEndpoint, params), nil
}

// exchange trades the code for the id_token (contract section 5.4).
func (rp *relyingParty) exchange(ctx context.Context, code, verifier string) (exchange, error) {
	d, err := rp.platform.discoveryDoc(ctx)
	if err != nil {
		return exchange{}, err
	}
	form := url.Values{}
	form.Set("grant_type", "authorization_code")
	form.Set("code", code)
	form.Set("redirect_uri", rp.redirectURI)
	form.Set("client_id", rp.clientID)
	form.Set("code_verifier", verifier)
	authorization := ""
	if rp.clientSecret != "" {
		pair := formEncode(rp.clientID) + ":" + formEncode(rp.clientSecret)
		authorization = "Basic " + base64.StdEncoding.EncodeToString([]byte(pair))
	}
	a, err := rp.platform.postForm(ctx, d.tokenEndpoint, form, authorization)
	if err != nil {
		return exchange{}, err
	}
	if a.status >= 500 {
		return exchange{}, unavailable("the token endpoint answered %d", a.status)
	}
	if a.status >= 400 {
		e := ""
		if a.json != nil {
			e, _ = a.json["error"].(string)
		}
		if !errorCode.MatchString(e) {
			e = "invalid"
		}
		return exchange{refused: e}, nil
	}
	if a.status != 200 || a.json == nil {
		return exchange{}, unavailable("the token endpoint answered %d without JSON", a.status)
	}
	idToken, _ := a.json["id_token"].(string)
	if idToken == "" {
		return exchange{refused: "no_id_token"}, nil
	}
	return exchange{idToken: idToken}, nil
}

// verify checks an id_token against the login's nonce (contract section 5.5). The error is non-nil
// only when the platform's keys cannot be read.
func (rp *relyingParty) verify(ctx context.Context, idToken, nonce string) (verified, error) {
	c, err := verifyJWT(ctx, idToken, rp.platform)
	if err != nil {
		return verified{}, err
	}
	if c.reason != "" {
		return verified{reason: c.reason}, nil
	}
	now := rp.clock.Now()
	if r := checkIDToken(c.claims, rp.platform.issuer, rp.clientID, nonce, now); r != "" {
		return verified{reason: r}, nil
	}
	authTime := now
	if at, ok := c.claims["auth_time"].(float64); ok {
		authTime = seconds(at)
	}
	principal, ok := principalOf(c.claims, false)
	if !ok {
		return verified{reason: "sub"}, nil
	}
	return verified{principal: principal, authTime: authTime}, nil
}

// endSessionURL is the platform's end-session URL for this client, or "" when it has none or is
// unreachable.
func (rp *relyingParty) endSessionURL(ctx context.Context, postLogoutRedirectURI string) string {
	d, err := rp.platform.discoveryDoc(ctx)
	if err != nil || d.endSessionEndpoint == "" {
		return ""
	}
	params := [][2]string{{"client_id", rp.clientID}}
	if postLogoutRedirectURI != "" {
		params = append(params, [2]string{"post_logout_redirect_uri", postLogoutRedirectURI})
	}
	return withQuery(d.endSessionEndpoint, params)
}

// withQuery appends application/x-www-form-urlencoded parameters in the given order.
func withQuery(base string, params [][2]string) string {
	var q strings.Builder
	for i, kv := range params {
		if i > 0 {
			q.WriteByte('&')
		}
		q.WriteString(formEncode(kv[0]) + "=" + formEncode(kv[1]))
	}
	sep := "?"
	if strings.Contains(base, "?") {
		sep = "&"
	}
	return base + sep + q.String()
}

// formEncode is Java's URLEncoder.encode(value, UTF-8): QueryEscape, except that Java leaves '*'
// alone and encodes '~'.
func formEncode(v string) string {
	s := url.QueryEscape(v)
	s = strings.ReplaceAll(s, "%2A", "*")
	return strings.ReplaceAll(s, "~", "%7E")
}
