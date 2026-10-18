package auth

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// A request whose client hung up must not cancel the shared key read, nor start the back-off
// that refuses new signing keys (security review F1).

var madeUpToken = b64(`{"alg":"RS256","kid":"made-up"}`) + "." + b64(`{"sub":"x"}`) + "." + "AAAA"

func cancelledContext() context.Context {
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	return ctx
}

func TestCancelledRequestDoesNotPoisonTheFirstKeyRead(t *testing.T) {
	f := newTokens(t)
	f.tokens.Verify(cancelledContext(), madeUpToken)
	if _, ok := f.tokens.Verify(context.Background(), f.provider.AccessToken(nil)).(Valid); !ok {
		t.Fatal("a valid token was refused right after a cancelled request")
	}
}

func TestCancelledRequestDoesNotBlockAKeyRotation(t *testing.T) {
	f := newTokens(t)
	f.valid(t, f.provider.AccessToken(nil))
	f.clock.Advance(2 * time.Minute)
	f.provider.RotateKey()
	f.tokens.Verify(cancelledContext(), madeUpToken)
	f.valid(t, f.provider.AccessToken(nil)) // signed with the rotated key
}

func TestFailureOfACancelledCallerStartsNoBackoff(t *testing.T) {
	f := newTokens(t)
	f.provider.Down(true)
	f.tokens.Verify(cancelledContext(), madeUpToken)
	f.provider.Down(false)
	f.valid(t, f.provider.AccessToken(nil)) // no 5 s wait after a cancelled caller
}

func TestPlainHTTPIssuerIsRefusedUnlessAllowed(t *testing.T) {
	f := newTokens(t)
	if _, err := NewPlatformTokens(f.provider.Issuer(), time.Second, f.clock, nil); err == nil {
		t.Fatal("an http issuer must be refused by default")
	}
	if _, err := NewPlatformTokens(f.provider.Issuer(), time.Second, f.clock, nil, AllowPlainHTTP()); err != nil {
		t.Fatal(err)
	}
	if _, err := NewPlatformTokens("https://platform.example.com", time.Second, f.clock, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := NewPlatformTokens("ftp://platform.example.com", time.Second, f.clock, nil, AllowPlainHTTP()); err == nil {
		t.Fatal("only http(s) issuers")
	}
}

func TestDiscoveryEndpointsMustBeHTTPSOnTheRightOrigin(t *testing.T) {
	ctx := context.Background()
	f := newRP(t, "")
	base := f.provider.Issuer()
	// end_session_endpoint: wrong scheme or another origin is ignored, never followed
	for _, end := range []string{"javascript:alert(1)", "data:text/html,x", "https://evil.example/logout", base + "@evil.example/x", "ftp://127.0.0.1/x"} {
		f.provider.DiscoveryChanges(map[string]any{"end_session_endpoint": end})
		p, err := newPlatform(base, time.Second, f.clock, nil, true)
		if err != nil {
			t.Fatal(err)
		}
		d, err := p.discoveryDoc(ctx)
		if err != nil {
			t.Fatalf("%s: %v", end, err)
		}
		if d.endSessionEndpoint != "" {
			t.Errorf("end_session_endpoint %q was accepted", end)
		}
	}
	// authorization_endpoint: the browser is sent there
	for _, auth := range []string{"ftp://127.0.0.1/a", "data://x/y", "javascript://x/%0aalert(1)"} {
		f.provider.DiscoveryChanges(map[string]any{"authorization_endpoint": auth})
		p, perr := newPlatform(base, time.Second, f.clock, nil, true)
		if perr != nil {
			t.Fatal(perr)
		}
		if _, err := p.discoveryDoc(ctx); err == nil {
			t.Errorf("authorization_endpoint %q was accepted", auth)
		}
	}
}

func TestSafeSchemeIsHTTPSUnlessPlainHTTPIsAllowed(t *testing.T) {
	strict := &platform{}
	loose := &platform{allowHTTP: true}
	if !strict.safeScheme("https://a.example/x") || strict.safeScheme("http://a.example/x") || strict.safeScheme("javascript:alert(1)") {
		t.Fatal("strict")
	}
	if !loose.safeScheme("http://a.example/x") || loose.safeScheme("data:text/html,x") {
		t.Fatal("loose")
	}
}

func TestDefaultRateLimitWarnsOnceWhenBehindAProxy(t *testing.T) {
	var out strings.Builder
	key := peerKeyWarningOnForwarded(slog.New(slog.NewTextHandler(&out, nil)))
	plain := httptest.NewRequest("GET", "/auth/login", http.NoBody)
	plain.RemoteAddr = "10.0.0.1:5000"
	if key(plain); out.Len() != 0 {
		t.Fatal("no warning without the header")
	}
	proxied := httptest.NewRequest("GET", "/auth/login", http.NoBody)
	proxied.RemoteAddr = "10.0.0.1:5000"
	proxied.Header.Set("X-Forwarded-For", "203.0.113.9")
	if key(proxied) != key(plain) {
		t.Fatal("still keyed by the peer")
	}
	key(proxied)
	if n := strings.Count(out.String(), "X-Forwarded-For"); n != 1 {
		t.Fatalf("want one warning, got %d: %s", n, out.String())
	}
}
