package flowcatalyst_test

import (
	"errors"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
)

// PlatformClient.BaseURL and SDK (fulfil-go port, item 11): the platform's address, and a go-sdk
// client over the application's own token for the platform APIs the wrapper does not cover.

func sdkRig(t *testing.T, p *fakeplatform.Platform) *rig {
	t.Helper()
	return startRig(t, rigOptions{env: map[string]string{
		"FLOWCATALYST_BASE_URL":      p.BaseURL() + "/",
		"FLOWCATALYST_CLIENT_ID":     fakeplatform.ClientID,
		"FLOWCATALYST_CLIENT_SECRET": fakeplatform.ClientSecret,
	}})
}

func TestBaseURLIsThePlatformsAddress(t *testing.T) {
	p := fakeplatform.Start()
	t.Cleanup(p.Close)
	r := sdkRig(t, p)
	got, err := r.fc.Client().BaseURL()
	if err != nil || got != p.BaseURL() {
		t.Fatalf("BaseURL %q %v, want %q (no trailing slash)", got, err, p.BaseURL())
	}
}

func TestTheSDKClientCallsThePlatformWithTheApplicationsToken(t *testing.T) {
	p := fakeplatform.Start()
	t.Cleanup(p.Close)
	p.RespondGet("/api/clients/by-identifier/acme", http.StatusOK, `{"id":"clt_0ACME","identifier":"acme","name":"Acme"}`)
	r := sdkRig(t, p)
	sdk, err := r.fc.Client().SDK()
	if err != nil {
		t.Fatal(err)
	}
	c, err := sdk.Clients().GetByIdentifier(t.Context(), "acme")
	if err != nil || c.ID != "clt_0ACME" {
		t.Fatalf("client %+v %v", c, err)
	}
	if sdk.BaseURL() != p.BaseURL() {
		t.Errorf("the SDK's base URL %q", sdk.BaseURL())
	}
}

func TestTheSDKClientClearsARejectedTokenAndRetriesOnce(t *testing.T) {
	p := fakeplatform.Start()
	t.Cleanup(p.Close)
	var calls atomic.Int32
	var mu sync.Mutex
	var seen [3]string
	p.RouteMatching("GET", `/api/clients/by-identifier/acme`, func(rec fakeplatform.Recorded) fakeplatform.Reply {
		n := calls.Add(1)
		if n <= 3 {
			mu.Lock()
			seen[n-1] = rec.Header.Get("Authorization")
			mu.Unlock()
		}
		if n == 1 { // the platform no longer accepts the cached token
			return fakeplatform.Reply{Status: http.StatusUnauthorized, Body: `{"error":"UNAUTHENTICATED"}`}
		}
		return fakeplatform.Reply{Status: http.StatusOK, Body: `{"id":"clt_0ACME","identifier":"acme"}`}
	})
	r := sdkRig(t, p)
	if _, err := r.fc.Client().AccessToken(t.Context()); err != nil { // the token is cached before the call
		t.Fatal(err)
	}
	before := p.TokenRequests()
	sdk, err := r.fc.Client().SDK()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := sdk.Clients().GetByIdentifier(t.Context(), "acme"); err != nil {
		t.Fatalf("the retry after a fresh token failed: %v", err)
	}
	if calls.Load() != 2 {
		t.Errorf("%d calls, want 2 (one retry)", calls.Load())
	}
	if p.TokenRequests() != before+1 {
		t.Errorf("tokens fetched: %d, want one more than %d (the cached token was cleared)", p.TokenRequests(), before)
	}
	mu.Lock()
	first, second := seen[0], seen[1]
	mu.Unlock()
	if first == "" || first == second {
		t.Errorf("the retry must carry the new token: %q then %q", first, second)
	}

	// a second 401 is the answer: one retry only
	var refused atomic.Int32
	p.RouteMatching("GET", `/api/clients/by-identifier/globex`, func(fakeplatform.Recorded) fakeplatform.Reply {
		refused.Add(1)
		return fakeplatform.Reply{Status: http.StatusUnauthorized, Body: `{"error":"UNAUTHENTICATED"}`}
	})
	_, err = sdk.Clients().GetByIdentifier(t.Context(), "globex")
	if err == nil || !strings.Contains(err.Error(), "401") {
		t.Fatalf("a token refused twice: %v", err)
	}
	if refused.Load() != 2 {
		t.Errorf("%d calls, want 2 (the call and one retry)", refused.Load())
	}
}

func TestADisabledClientHasNoBaseURLAndNoSDK(t *testing.T) {
	r := startRig(t, rigOptions{env: map[string]string{"FLOWCATALYST_ENABLED": "false"}})
	if _, err := r.fc.Client().BaseURL(); !errors.Is(err, flowcatalyst.ErrDisabled) {
		t.Errorf("BaseURL: %v", err)
	}
	if _, err := r.fc.Client().SDK(); !errors.Is(err, flowcatalyst.ErrDisabled) {
		t.Errorf("SDK: %v", err)
	}
}
