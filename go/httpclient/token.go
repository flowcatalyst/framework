package httpclient

import (
	"context"
	"encoding/base64"
	"encoding/json/v2"
	"fmt"
	"math"
	"net/http"
	"strings"
	"time"
)

const (
	defaultTokenLifetime = 5 * time.Minute
	maxTokenSkew         = 30 * time.Second
	maxTokenAnswer       = 64 * 1024
)

// tokenCache is the client-credentials token of one service: fetched once for all concurrent
// callers (the semaphore is held while fetching), reused until expires_in minus a skew, dropped
// after a 401. Messages never contain the secret, the token or the endpoint's answer.
type tokenCache struct {
	cc  ClientCredentials
	svc *Service
	sem chan struct{}

	token     string
	expiresAt time.Time
}

func newTokenCache(cc ClientCredentials, svc *Service) *tokenCache {
	return &tokenCache{cc: cc, svc: svc, sem: make(chan struct{}, 1)}
}

// get returns a valid token, fetching one when there is none or it is about to expire.
func (t *tokenCache) get(ctx context.Context) (string, error) {
	select {
	case t.sem <- struct{}{}:
	case <-ctx.Done():
		return "", ctx.Err()
	}
	defer func() { <-t.sem }()
	if t.token != "" && t.svc.engine.clock.Now().Before(t.expiresAt) {
		return t.token, nil
	}
	return t.fetch(ctx)
}

// invalidate drops the token a request was refused with (not a newer one another caller fetched).
func (t *tokenCache) invalidate(used string) {
	t.sem <- struct{}{}
	defer func() { <-t.sem }()
	if t.token == used {
		t.token = ""
	}
}

func authFailure(msg string) *Error { return &Error{Kind: KindAuth, msg: msg} }

func (t *tokenCache) fetch(ctx context.Context) (string, error) {
	host := t.cc.tokenURL.Hostname()
	form := [][2]string{{"grant_type", "client_credentials"}}
	if t.cc.scope != "" {
		form = append(form, [2]string{"scope", t.cc.scope})
	}
	if t.cc.audience != "" {
		form = append(form, [2]string{"audience", t.cc.audience})
	}
	headers := http.Header{}
	headers.Set("Content-Type", "application/x-www-form-urlencoded")
	headers.Set("Accept", "application/json")
	headers.Set("User-Agent", userAgent)
	secret, err := t.cc.secret(ctx)
	if err != nil {
		return "", authFailure("the client secret could not be read")
	}
	switch t.cc.method {
	case GrantPost:
		form = append(form, [2]string{"client_id", t.cc.clientID}, [2]string{"client_secret", secret})
	case GrantBasic:
		pair := formEscape(t.cc.clientID) + ":" + formEscape(secret)
		headers.Set("Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(pair)))
	}
	var body strings.Builder
	for i, kv := range form {
		if i > 0 {
			body.WriteByte('&')
		}
		body.WriteString(formEscape(kv[0]) + "=" + formEscape(kv[1]))
	}
	started := t.svc.engine.clock.Now()
	answer, fail := t.svc.engine.exchange(ctx, outgoing{
		method: http.MethodPost, target: t.cc.tokenURL.String(), headers: headers, body: []byte(body.String()),
		connectTimeout: t.svc.opts.connectTimeout, timeout: t.svc.opts.requestTimeout, maxBytes: maxTokenAnswer,
	})
	if fail != nil {
		if fail.kind == KindCanceled {
			return "", &Error{Kind: KindCanceled, msg: t.svc.name + ": canceled", cause: ctx.Err()}
		}
		return "", authFailure(fmt.Sprintf("the token request to %s failed (%s)", host, strings.ToUpper(fail.kind.String())))
	}
	if answer.status < 200 || answer.status >= 300 {
		return "", authFailure(fmt.Sprintf("the token endpoint %s answered %d", host, answer.status))
	}
	var tree map[string]any
	if err := json.Unmarshal(answer.body, &tree); err != nil {
		return "", authFailure("the token endpoint " + host + " sent an answer that is not JSON")
	}
	access, ok := tree["access_token"].(string)
	if !ok || strings.TrimSpace(access) == "" {
		return "", authFailure("the token endpoint " + host + " sent no access_token")
	}
	if typ, present := tree["token_type"]; present {
		if s, isString := typ.(string); !isString || !strings.EqualFold(s, "bearer") {
			return "", authFailure("the token endpoint " + host + " sent a token that is not a bearer token")
		}
	}
	lifetime := defaultTokenLifetime
	if exp, present := tree["expires_in"]; present {
		f, isNumber := exp.(float64)
		if !isNumber || f < 1 || math.IsInf(f, 0) {
			return "", authFailure("the token endpoint " + host + " sent an invalid expires_in")
		}
		lifetime = time.Duration(min(f, float64(math.MaxInt32))) * time.Second
	}
	skew := min(lifetime/10, maxTokenSkew)
	if err := checkHeaderValue("Authorization", access); err != nil {
		return "", authFailure("the token endpoint " + host + " sent an access_token that is not a valid header value")
	}
	t.token = access
	t.expiresAt = started.Add(lifetime - skew)
	return access, nil
}
