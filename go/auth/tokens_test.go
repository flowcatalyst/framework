package auth

import (
	"context"
	"encoding/base64"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth/authtest"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/fwtest"
)

type tokenFixture struct {
	clock    *fwtest.Clock
	provider *authtest.Provider
	tokens   *PlatformTokens
}

func newTokens(t *testing.T) *tokenFixture {
	t.Helper()
	clock := fwtest.NewClock(start)
	provider := authtest.Start(clock)
	t.Cleanup(provider.Close)
	tokens, err := NewPlatformTokens(provider.Issuer(), 5*time.Second, clock, nil, AllowPlainHTTP())
	if err != nil {
		t.Fatal(err)
	}
	return &tokenFixture{clock: clock, provider: provider, tokens: tokens}
}

func (f *tokenFixture) invalid(t *testing.T, token string) string {
	t.Helper()
	r, ok := f.tokens.Verify(context.Background(), token).(Invalid)
	if !ok {
		t.Fatalf("expected an invalid token")
	}
	return r.Reason
}

func (f *tokenFixture) valid(t *testing.T, token string) AccessToken {
	t.Helper()
	switch r := f.tokens.Verify(context.Background(), token).(type) {
	case Valid:
		return r.Token
	case Invalid:
		t.Fatalf("expected a valid token, refused: %s", r.Reason)
	case Unavailable:
		t.Fatalf("expected a valid token, unavailable: %s", r.Reason)
	}
	return AccessToken{}
}

func b64(s string) string { return base64.RawURLEncoding.EncodeToString([]byte(s)) }

func TestAccessTokenRules(t *testing.T) {
	f := newTokens(t)
	now := start.Unix()
	for _, c := range []struct {
		name    string
		changes map[string]any
		reason  string
	}{
		{"identity token", map[string]any{"token_use": "identity"}, "token_use"},
		{"no token_use", map[string]any{"token_use": nil}, "token_use"},
		{"audience is a login client", map[string]any{"aud": "app-client"}, "aud"},
		{"other issuer", map[string]any{"iss": "https://evil.example"}, "iss"},
		{"other audience", map[string]any{"aud": "https://evil.example"}, "aud"},
		{"expired", map[string]any{"exp": now - 61}, "expired"},
		{"not yet valid", map[string]any{"nbf": now + 61}, "not_yet_valid"},
		{"no sub", map[string]any{"sub": nil}, "sub"},
	} {
		if got := f.invalid(t, f.provider.AccessToken(c.changes)); got != c.reason {
			t.Errorf("%s: reason %q, want %q", c.name, got, c.reason)
		}
	}
	f.valid(t, f.provider.AccessToken(map[string]any{"aud": []any{"x", f.provider.Issuer()}}))
	f.valid(t, f.provider.AccessToken(map[string]any{"exp": now - 59}))
}

func TestMalformedTokens(t *testing.T) {
	f := newTokens(t)
	for _, tok := range []string{"", "a.b", "a.b.c.d", strings.Repeat("x", 16_385)} {
		if got := f.invalid(t, tok); got != "malformed" {
			t.Errorf("%q: %s", tok[:min(len(tok), 10)], got)
		}
	}
	p := strings.Split(f.provider.AccessToken(nil), ".")
	if got := f.invalid(t, p[0]+"=."+p[1]+"."+p[2]); got != "malformed" {
		t.Errorf("padding: %s", got)
	}
	if got := f.invalid(t, p[0]+"\n."+p[1]+"."+p[2]); got != "malformed" {
		t.Errorf("newline in a part: %s", got)
	}
	if got := f.invalid(t, "bm90IGpzb24."+p[1]+"."+p[2]); got != "malformed" {
		t.Errorf("not json: %s", got)
	}
	if got := f.invalid(t, p[0]+"."+p[1]+"."); got != "signature" {
		t.Errorf("empty signature with RS256: %s", got)
	}
}

func TestAValidTokenSaysWhoAndWhat(t *testing.T) {
	f := newTokens(t)
	a := f.valid(t, f.provider.AccessToken(map[string]any{
		"sub": "prn_SVC", "scope": "ingestor:ingestion:manage ingestor:ingestion:view",
		"clients": []any{"clt_1:acme"}, "client_id": "clt_1",
	}))
	if a.Principal.ID != "prn_SVC" || a.Principal.Kind != fw.PrincipalService {
		t.Fatalf("%+v", a.Principal)
	}
	if !a.Principal.HasScope("ingestor:ingestion:manage") || a.Principal.HasScope("ingestor:ingestion") {
		t.Fatal("scopes are exact names")
	}
	for client, want := range map[string]bool{"clt_1": true, "acme": true, "clt_2": false, "clt": false, "": false} {
		if a.MayActFor(client) != want {
			t.Errorf("MayActFor(%q) = %v", client, !want)
		}
	}
	if a.AuthorizedParty != "svc-client" || !a.ExpiresAt.Equal(start.Add(time.Hour)) {
		t.Fatalf("%+v", a)
	}
}

func TestUnknownKidsTriggerAtMostOneKeyReadAMinute(t *testing.T) {
	f := newTokens(t)
	f.valid(t, f.provider.AccessToken(nil))
	if f.provider.KeyFetches() != 1 {
		t.Fatalf("fetches %d", f.provider.KeyFetches())
	}
	p := strings.Split(f.provider.AccessToken(nil), ".")
	if got := f.invalid(t, b64(`{"alg":"RS256","kid":"early"}`)+"."+p[1]+"."+p[2]); got != "unknown_kid" {
		t.Fatal(got)
	}
	if f.provider.KeyFetches() != 1 {
		t.Fatal("the keys were read a moment ago: an unknown kid must not read them again yet")
	}
	f.clock.Advance(61 * time.Second)
	for i := range 50 {
		kid := `{"alg":"RS256","kid":"made-up-` + string(rune('a'+i%26)) + `"}`
		if got := f.invalid(t, b64(kid)+"."+p[1]+"."+p[2]); got != "unknown_kid" {
			t.Fatal(got)
		}
	}
	if f.provider.KeyFetches() != 2 {
		t.Fatalf("fifty made-up kids read the keys once: %d", f.provider.KeyFetches())
	}
	f.clock.Advance(61 * time.Second)
	f.provider.RotateKey()
	f.valid(t, f.provider.AccessToken(nil))
	if f.provider.KeyFetches() != 3 {
		t.Fatalf("a token under the new kid after the minute: one more read: %d", f.provider.KeyFetches())
	}
}

func TestManyRequestsWithUnknownKidsAtOnceReadTheKeysOnce(t *testing.T) {
	f := newTokens(t)
	f.valid(t, f.provider.AccessToken(nil))
	f.clock.Advance(61 * time.Second)
	p := strings.Split(f.provider.AccessToken(nil), ".")
	var wg sync.WaitGroup
	start := make(chan struct{})
	for i := range 32 {
		wg.Go(func() {
			<-start
			kid := `{"alg":"RS256","kid":"x` + string(rune('a'+i%26)) + `"}`
			if _, ok := f.tokens.Verify(context.Background(), b64(kid)+"."+p[1]+"."+p[2]).(Invalid); !ok {
				t.Error("expected invalid")
			}
		})
	}
	close(start)
	wg.Wait()
	if f.provider.KeyFetches() != 2 {
		t.Fatalf("fetches %d", f.provider.KeyFetches())
	}
}

func TestKeysAreReadAgainAfterAnHourAndKeptWhenThePlatformIsDown(t *testing.T) {
	f := newTokens(t)
	f.valid(t, f.provider.AccessToken(nil))
	f.clock.Advance(61 * time.Minute)
	f.provider.Down(true)
	f.valid(t, f.provider.AccessToken(nil))
	f.valid(t, f.provider.AccessToken(nil))
	if f.provider.KeyFetches() != 2 {
		t.Fatalf("the read after an hour fails once: %d", f.provider.KeyFetches())
	}
	f.provider.Down(false)
	f.clock.Advance(2 * time.Minute)
	f.valid(t, f.provider.AccessToken(nil))
	if f.provider.KeyFetches() != 3 {
		t.Fatalf("fetches %d", f.provider.KeyFetches())
	}
}

func TestWithNoKeysAndThePlatformDownATokenIsUncheckableNotInvalid(t *testing.T) {
	f := newTokens(t)
	f.provider.Down(true)
	token := f.provider.AccessToken(nil)
	if _, ok := f.tokens.Verify(context.Background(), token).(Unavailable); !ok {
		t.Fatal("expected unavailable")
	}
	discoveries := f.provider.DiscoveryFetches()
	if _, ok := f.tokens.Verify(context.Background(), token).(Unavailable); !ok {
		t.Fatal("expected unavailable")
	}
	if f.provider.DiscoveryFetches() != discoveries {
		t.Fatal("within five seconds the platform is not called again")
	}
	f.provider.Down(false)
	f.clock.Advance(6 * time.Second)
	f.valid(t, token)
}

func TestAllowPlainHTTPIn(t *testing.T) {
	for env, want := range map[string]bool{"local": true, "test": true, "production": false, "": false} {
		var o tokensOptions
		AllowPlainHTTPIn(env)(&o)
		if o.allowHTTP != want {
			t.Errorf("env %q: allowHTTP = %v, want %v", env, o.allowHTTP, want)
		}
	}
}
