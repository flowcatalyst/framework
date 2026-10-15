package httpclient_test

import (
	"context"
	"encoding/base64"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/httpclient"
)

const secret = "s3cr3t-value-0xDEAD"

type authHarness struct {
	*harness
	issued atomic.Int32
}

func newAuthHarness(t *testing.T) *authHarness {
	h := &authHarness{harness: newHarness(t)}
	h.f.onScript("/oauth/token", func(recorded, int) answer {
		n := h.issued.Add(1)
		return ok(fmt.Sprintf(`{"access_token":"tok-%d","token_type":"Bearer","expires_in":60}`, n))
	})
	h.f.onScript("/api/me", func(r recorded, _ int) answer {
		return ok(fmt.Sprintf(`{"auth":%q}`, r.header.Get("Authorization")))
	})
	return h
}

func (h *authHarness) client(a httpclient.Auth, opts ...httpclient.ServiceOption) *httpclient.Service {
	h.t.Helper()
	all := append([]httpclient.ServiceOption{httpclient.WithAuth(a), httpclient.WithObserver(h.seen), httpclient.Retry(httpclient.DefaultRetry().WithBackoff(0, 0))}, opts...)
	s, err := h.engine.Service("api", h.f.base(""), all...)
	if err != nil {
		h.t.Fatal(err)
	}
	return s
}

func (h *authHarness) credentials(opts ...httpclient.ClientCredentialsOption) httpclient.ClientCredentials {
	h.t.Helper()
	cc, err := httpclient.NewClientCredentials(h.f.base("/oauth/token"), "orders-service", httpclient.Static(secret), opts...)
	if err != nil {
		h.t.Fatal(err)
	}
	return cc
}

func (h *authHarness) authOf(s *httpclient.Service) string {
	h.t.Helper()
	return send(h.t, s.Get("/api/me")).Text()
}

func TestBearerBasicAndHeaderStrategies(t *testing.T) {
	h := newAuthHarness(t)
	var asked atomic.Int32
	bearer := httpclient.Bearer(func(context.Context) (string, error) { return fmt.Sprintf("t-%d", asked.Add(1)), nil })
	if got := h.authOf(h.client(bearer)); !strings.Contains(got, "Bearer t-1") {
		t.Fatalf("bearer: %s", got)
	}
	if got := h.authOf(h.client(bearer)); !strings.Contains(got, "Bearer t-2") {
		t.Fatalf("the credential is asked on every attempt: %s", got)
	}
	basic, err := httpclient.Basic("ada", httpclient.Static("pw"))
	if err != nil {
		t.Fatal(err)
	}
	if got := h.authOf(h.client(basic)); !strings.Contains(got, "Basic "+base64.StdEncoding.EncodeToString([]byte("ada:pw"))) {
		t.Fatalf("basic: %s", got)
	}
	key, err := httpclient.APIKey("X-Api-Key", httpclient.Static("k-1"))
	if err != nil {
		t.Fatal(err)
	}
	send(t, h.client(key).Get("/api/me"))
	if v := h.f.requests()[len(h.f.requests())-1].header.Get("X-Api-Key"); v != "k-1" {
		t.Fatalf("api key header %q", v)
	}
	if got := h.authOf(h.client(httpclient.NoAuth())); !strings.Contains(got, `"auth":""`) {
		t.Fatalf("none: %s", got)
	}
	if _, err := httpclient.Basic("a:b", httpclient.Static("x")); err == nil {
		t.Error("a basic-auth user with ':' accepted")
	}
	if _, err := httpclient.APIKey("Host", httpclient.Static("x")); err == nil {
		t.Error("a reserved API-key header accepted")
	}
}

func TestABearerWithACallbackIsRefreshedOnceAfterA401(t *testing.T) {
	h := newAuthHarness(t)
	h.f.onScript("/api/strict", func(r recorded, _ int) answer {
		if r.header.Get("Authorization") == "Bearer good" {
			return ok("{}")
		}
		return status(401, "{}")
	})
	var mu sync.Mutex
	tokens := []string{"stale", "good"}
	var cleared atomic.Int32
	c := h.client(httpclient.BearerRefreshing(
		func(context.Context) (string, error) { mu.Lock(); defer mu.Unlock(); return tokens[0], nil },
		func() { cleared.Add(1); mu.Lock(); tokens = tokens[1:]; mu.Unlock() }))
	if send(t, c.Post("/api/strict")).Status() != 200 { // even a POST: a 401 was not processed
		t.Fatal("not refreshed")
	}
	if cleared.Load() != 1 || h.f.count("/api/strict") != 2 {
		t.Fatalf("cleared %d, requests %d", cleared.Load(), h.f.count("/api/strict"))
	}
	// a second 401 is returned, not looped on
	never := h.client(httpclient.BearerRefreshing(httpclient.Static("bad"), func() { cleared.Add(1) }))
	if send(t, never.Get("/api/strict")).Status() != 401 || h.f.count("/api/strict") != 4 {
		t.Fatalf("requests %d", h.f.count("/api/strict"))
	}
	// without a callback a 401 is simply returned
	if send(t, h.client(httpclient.Bearer(httpclient.Static("bad"))).Get("/api/strict")).Status() != 401 {
		t.Fatal("401 not returned")
	}
}

func TestClientCredentialsFetchOneTokenAndReuseItUntilNearExpiry(t *testing.T) {
	h := newAuthHarness(t)
	c := h.client(httpclient.OAuthClientCredentials(h.credentials(httpclient.Scope("billing"))))
	for range 3 {
		if got := h.authOf(c); !strings.Contains(got, "Bearer tok-1") {
			t.Fatalf("got %s", got)
		}
	}
	if h.issued.Load() != 1 {
		t.Fatalf("%d tokens issued, want 1", h.issued.Load())
	}
	tok := h.f.requests()[0]
	if tok.method != "POST" || tok.header.Get("Content-Type") != "application/x-www-form-urlencoded" {
		t.Fatalf("token request %+v", tok)
	}
	if want := "grant_type=client_credentials&scope=billing&client_id=orders-service&client_secret=" + secret; tok.body != want {
		t.Fatalf("token request body %q, want %q", tok.body, want)
	}
	// expires_in 60 s, skew min(30 s, 6 s) = 6 s: still valid at 53 s, renewed at 54 s
	h.clock.Advance(53 * time.Second)
	if got := h.authOf(c); !strings.Contains(got, "Bearer tok-1") {
		t.Fatalf("at 53s: %s", got)
	}
	h.clock.Advance(time.Second)
	if got := h.authOf(c); !strings.Contains(got, "Bearer tok-2") {
		t.Fatalf("at 54s: %s", got)
	}
}

func TestTheSkewIsAtMost30SecondsAndTheDefaultLifetimeIsFiveMinutes(t *testing.T) {
	h := newAuthHarness(t)
	h.f.onScript("/oauth/token", func(recorded, int) answer {
		n := h.issued.Add(1)
		return ok(fmt.Sprintf(`{"access_token":"tok-%d","expires_in":3600}`, n))
	})
	c := h.client(httpclient.OAuthClientCredentials(h.credentials()))
	h.authOf(c)
	h.clock.Advance(3600*time.Second - 30*time.Second - time.Second)
	if got := h.authOf(c); !strings.Contains(got, "tok-1") {
		t.Fatalf("one second before the skew: %s", got)
	}
	h.clock.Advance(time.Second)
	if got := h.authOf(c); !strings.Contains(got, "tok-2") {
		t.Fatalf("at the skew: %s", got)
	}
	h2 := newAuthHarness(t)
	h2.f.onScript("/oauth/token", func(recorded, int) answer {
		n := h2.issued.Add(1)
		return ok(fmt.Sprintf(`{"access_token":"tok-%d"}`, n)) // no expires_in
	})
	c2 := h2.client(httpclient.OAuthClientCredentials(h2.credentials()))
	h2.authOf(c2)
	h2.clock.Advance(4*time.Minute + 30*time.Second - time.Second) // 5 minutes less the 30 s skew
	if h2.authOf(c2); h2.issued.Load() != 1 {
		t.Fatalf("a token without expires_in is renewed too early: issued %d", h2.issued.Load())
	}
	h2.clock.Advance(time.Second)
	if h2.authOf(c2); h2.issued.Load() != 2 {
		t.Fatalf("a token without expires_in is not renewed at 4m30s: issued %d", h2.issued.Load())
	}
}

func TestBasicClientAuthenticationSendsTheSecretInAHeader(t *testing.T) {
	h := newAuthHarness(t)
	h.authOf(h.client(httpclient.OAuthClientCredentials(h.credentials(httpclient.Method(httpclient.GrantBasic)))))
	tok := h.f.requests()[0]
	if tok.body != "grant_type=client_credentials" {
		t.Fatalf("body %q", tok.body)
	}
	want := "Basic " + base64.StdEncoding.EncodeToString([]byte("orders-service:"+secret))
	if got := tok.header.Get("Authorization"); got != want {
		t.Fatalf("Authorization %q, want %q", got, want)
	}
}

func TestAudienceAndAnEscapedSecretTravelInTheForm(t *testing.T) {
	h := newAuthHarness(t)
	cc, err := httpclient.NewClientCredentials(h.f.base("/oauth/token"), "id with space", httpclient.Static("a&b=c d"), httpclient.Audience("https://api.example"))
	if err != nil {
		t.Fatal(err)
	}
	h.authOf(h.client(httpclient.OAuthClientCredentials(cc)))
	if want := "grant_type=client_credentials&audience=https%3A%2F%2Fapi.example&client_id=id+with+space&client_secret=a%26b%3Dc+d"; h.f.requests()[0].body != want {
		t.Fatalf("body %q", h.f.requests()[0].body)
	}
}

func TestA401DropsTheTokenAndTheRequestIsSentOnceMoreWithANewOne(t *testing.T) {
	h := newAuthHarness(t)
	h.f.onScript("/api/fresh", func(r recorded, _ int) answer {
		if r.header.Get("Authorization") == "Bearer tok-1" {
			return status(401, "{}")
		}
		return ok("{}")
	})
	c := h.client(httpclient.OAuthClientCredentials(h.credentials()))
	if send(t, c.Post("/api/fresh")).Status() != 200 {
		t.Fatal("not repeated")
	}
	if h.issued.Load() != 2 || h.f.count("/api/fresh") != 2 {
		t.Fatalf("issued %d, requests %d", h.issued.Load(), h.f.count("/api/fresh"))
	}
	// the repeat after a refused credential is not a retry: both attempts are numbered 1
	for _, a := range h.seen.get() {
		if !strings.HasSuffix(a, "#1") {
			t.Fatalf("attempts %v: the repeat after a 401 counted as a retry", h.seen.get())
		}
	}
}

func TestAnInvalidatedTokenIsNotDroppedIfAnotherCallerAlreadyReplacedIt(t *testing.T) {
	h := newAuthHarness(t)
	h.f.onScript("/api/fresh", func(r recorded, _ int) answer {
		if r.header.Get("Authorization") == "Bearer tok-1" {
			return status(401, "{}")
		}
		return ok("{}")
	})
	c := h.client(httpclient.OAuthClientCredentials(h.credentials()))
	send(t, c.Get("/api/fresh")) // tok-1 refused, tok-2 fetched and accepted
	h.authOf(c)                  // reuses tok-2
	if h.issued.Load() != 2 {
		t.Fatalf("issued %d, want 2", h.issued.Load())
	}
}

func TestARefusedTokenIsDroppedButNeverANewerOneAnotherCallerFetched(t *testing.T) {
	h := newAuthHarness(t)
	var arrivals atomic.Int32
	barrier := make(chan struct{})
	released := make(chan struct{})
	h.f.onScript("/api/gate", func(r recorded, _ int) answer {
		if r.header.Get("Authorization") != "Bearer tok-1" {
			return ok("{}")
		}
		switch arrivals.Add(1) {
		case 1:
			<-barrier // wait for the second caller to have sent tok-1 as well
			return status(401, "{}")
		case 2:
			close(barrier)
			<-released // refused only after the first caller has its new token in use
			return status(401, "{}")
		}
		return status(401, "{}")
	})
	c := h.client(httpclient.OAuthClientCredentials(h.credentials()))
	var wg sync.WaitGroup
	statuses := make([]int, 2)
	for i := range 2 {
		wg.Go(func() {
			resp, err := c.Get("/api/gate").Send(t.Context())
			if err != nil {
				t.Error(err)
				return
			}
			statuses[i] = resp.Status()
		})
	}
	// release the held caller once one of the two has finished its repeat
	deadline := time.After(10 * time.Second)
	for h.f.count("/api/gate") < 3 {
		select {
		case <-deadline:
			t.Fatal("the first caller never repeated its request")
		case <-time.After(5 * time.Millisecond):
		}
	}
	time.Sleep(50 * time.Millisecond) // its repeat is answered and its new token is cached
	close(released)
	wg.Wait()
	if statuses[0] != 200 || statuses[1] != 200 {
		t.Fatalf("statuses %v", statuses)
	}
	if h.issued.Load() != 2 {
		t.Fatalf("%d tokens issued, want 2: a refused token dropped the newer one", h.issued.Load())
	}
}

func TestConcurrentCallersShareOneTokenRequest(t *testing.T) {
	h := newAuthHarness(t)
	var issued atomic.Int32
	h.f.onScript("/oauth/token", func(recorded, int) answer {
		issued.Add(1)
		return ok(`{"access_token":"shared","expires_in":300}`).delayed(200 * time.Millisecond)
	})
	c := h.client(httpclient.OAuthClientCredentials(h.credentials()))
	var wg sync.WaitGroup
	var good atomic.Int32
	start := make(chan struct{})
	for range 20 {
		wg.Go(func() {
			<-start
			if strings.Contains(h.authOf(c), "Bearer shared") {
				good.Add(1)
			}
		})
	}
	close(start)
	wg.Wait()
	if good.Load() != 20 || issued.Load() != 1 {
		t.Fatalf("good %d, token requests %d", good.Load(), issued.Load())
	}
}

func TestAFailingTokenEndpointIsAnAuthFailureThatNeverShowsTheSecretOrTheAnswer(t *testing.T) {
	h := newAuthHarness(t)
	h.f.on("/oauth/token", status(401, `{"error":"invalid_client","echo":"`+secret+`"}`))
	_, err := h.client(httpclient.OAuthClientCredentials(h.credentials())).Get("/api/me").Send(t.Context())
	if kindOf(err) != httpclient.KindAuth {
		t.Fatalf("err = %v", err)
	}
	msg := err.Error()
	if !strings.Contains(msg, "127.0.0.1") || !strings.Contains(msg, "401") || strings.Contains(msg, secret) || strings.Contains(msg, "invalid_client") {
		t.Fatalf("message %q", msg)
	}
	if h.f.count("/api/me") != 0 {
		t.Fatal("the request was sent without a token")
	}
}

func TestMalformedTokenAnswersAreRefused(t *testing.T) {
	for _, body := range []string{
		"not json", "{}", `{"access_token":""}`, `{"access_token":"  "}`, `{"access_token":7}`, `{"access_token":"t","token_type":"mac"}`,
		`{"access_token":"t","token_type":7}`, `{"access_token":"t","expires_in":-5}`, `{"access_token":"t","expires_in":0}`,
		`{"access_token":"t","expires_in":"60"}`, `{"access_token":"a\r\nX-Evil: 1"}`, `[]`,
	} {
		h := newAuthHarness(t)
		h.f.on("/oauth/token", ok(body))
		_, err := h.client(httpclient.OAuthClientCredentials(h.credentials())).Get("/api/me").Send(t.Context())
		if kindOf(err) != httpclient.KindAuth {
			t.Errorf("%s: err = %v", body, err)
		}
	}
	// a case-insensitive Bearer is fine
	h := newAuthHarness(t)
	h.f.on("/oauth/token", ok(`{"access_token":"t","token_type":"BEARER","expires_in":60}`))
	if got := h.authOf(h.client(httpclient.OAuthClientCredentials(h.credentials()))); !strings.Contains(got, "Bearer t") {
		t.Fatalf("got %s", got)
	}
}

func TestTokenURLsMustBeHTTPSOrLoopback(t *testing.T) {
	mustURL := func(s string) *url.URL { u, _ := url.Parse(s); return u }
	for _, bad := range []string{"http://login.example.com/token", "https://u:p@login.example.com/token", "ftp://x/y", "https:///token", "/token"} {
		if _, err := httpclient.NewClientCredentials(mustURL(bad), "c", httpclient.Static("s")); err == nil {
			t.Errorf("token URL %q accepted", bad)
		}
	}
	for _, good := range []string{"https://login.example.com/token", "http://localhost:8080/token", "http://127.0.0.1/t", "http://[::1]:9/t"} {
		if _, err := httpclient.NewClientCredentials(mustURL(good), "c", httpclient.Static("s")); err != nil {
			t.Errorf("token URL %q refused: %v", good, err)
		}
	}
	cc, _ := httpclient.NewClientCredentials(mustURL("https://login.example.com/token"), "c", httpclient.Static(secret))
	if strings.Contains(fmt.Sprint(cc), secret) || strings.Contains(fmt.Sprintf("%+v", cc), secret) {
		t.Error("ClientCredentials prints its secret")
	}
	if _, err := httpclient.NewClientCredentials(mustURL("https://x/t"), "  ", httpclient.Static("s")); err == nil {
		t.Error("a blank client id accepted")
	}
}

func TestACredentialThatFailsIsAnAuthFailure(t *testing.T) {
	h := newAuthHarness(t)
	c := h.client(httpclient.Bearer(func(context.Context) (string, error) { return "", errors.New("vault down: " + secret) }))
	_, err := c.Get("/api/me").Send(t.Context())
	if kindOf(err) != httpclient.KindAuth || strings.Contains(err.Error(), secret) || errors.Unwrap(err) != nil {
		t.Fatalf("err = %v (cause %v)", err, errors.Unwrap(err))
	}
	if h.f.count("/api/me") != 0 {
		t.Fatal("the request was sent")
	}
	// a header value with CRLF from a credential is refused too
	bad := h.client(httpclient.Bearer(httpclient.Static("a\r\nX-Evil: 1")))
	if _, err := bad.Get("/api/me").Send(t.Context()); kindOf(err) != httpclient.KindAuth {
		t.Fatalf("CRLF credential: %v", err)
	}
}
