package main

import (
	"bytes"
	"context"
	"encoding/json/v2"
	"io"
	"net/http"
	"net/http/cookiejar"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/internal/pgembed"
)

// lockedBuffer is written by the launcher and polled by the test.
type lockedBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (l *lockedBuffer) Write(p []byte) (int, error) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.Write(p)
}

func (l *lockedBuffer) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.b.String()
}

// The browser launcher (e2e.go) answers the kit's control endpoints and signs the next sign-in in as
// the person it was told, through the real login round trip.
func TestTheE2eLauncherDrivesSignInThroughTheControlEndpoints(t *testing.T) {
	t.Parallel()
	appPort, err := pgembed.FreePort()
	if err != nil {
		t.Fatal(err)
	}
	controlPort, err := pgembed.FreePort()
	if err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(t.Context())
	out := &lockedBuffer{}
	done := make(chan error, 1)
	go func() {
		done <- runE2e(ctx, []string{strconv.Itoa(appPort), strconv.Itoa(controlPort)}, out)
	}()
	t.Cleanup(func() {
		cancel()
		select {
		case <-done:
		case <-time.After(60 * time.Second):
			t.Error("the launcher did not stop")
		}
	})
	deadline := time.Now().Add(3 * time.Minute)
	for !strings.Contains(out.String(), "READY") {
		select {
		case err := <-done:
			t.Fatalf("the launcher ended before READY: %v", err)
		default:
		}
		if time.Now().After(deadline) {
			t.Fatal("no READY line")
		}
		time.Sleep(100 * time.Millisecond)
	}
	app := "http://127.0.0.1:" + strconv.Itoa(appPort)
	ctl := "http://127.0.0.1:" + strconv.Itoa(controlPort)

	jar, err := cookiejar.New(nil)
	if err != nil {
		t.Fatal(err)
	}
	client := &http.Client{Jar: jar}
	do := func(method, url string, headers ...string) (int, string) {
		t.Helper()
		req, err := http.NewRequestWithContext(t.Context(), method, url, nil)
		if err != nil {
			t.Fatal(err)
		}
		for i := 0; i+1 < len(headers); i += 2 {
			req.Header.Set(headers[i], headers[i+1])
		}
		res, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer res.Body.Close()
		b, _ := io.ReadAll(res.Body)
		return res.StatusCode, string(b)
	}
	if status, body := do("GET", ctl+"/control/ready"); status != 200 || body != "ready" {
		t.Fatalf("ready: %d %s", status, body)
	}
	// The default person is a clerk of no client; signing in works and the session names her.
	if status, body := do("GET", app+"/auth/login?returnTo=%2Forders"); status != 200 || !strings.Contains(body, "<html") {
		t.Fatalf("sign-in ended at %d %.80s", status, body)
	}
	if status, body := do("GET", app+"/auth/session"); status != 200 || !strings.Contains(body, `"authenticated":true`) || !strings.Contains(body, "Ada Lovelace") ||
		!strings.Contains(body, "orders-demo:clerk") {
		t.Fatalf("session: %d %s", status, body)
	}
	if status, body := do("POST", app+"/api/orders", "Content-Type", "application/json", "Sec-Fetch-Site", "same-origin"); status == 401 || status == 403 {
		t.Fatalf("a clerk may place orders: %d %s", status, body)
	}
	// end-sessions: the cookie no longer opens anything.
	if status, _ := do("POST", ctl+"/control/end-sessions"); status != 200 {
		t.Fatalf("end-sessions: %d", status)
	}
	if status, _ := do("GET", app+"/api/orders", "Accept", "application/json"); status != 401 {
		t.Fatalf("after end-sessions: %d", status)
	}
	// sign-in-as: a viewer; the next sign-in is that person.
	if status, _ := do("POST", ctl+"/control/sign-in-as?roles=orders-demo:viewer&name=Grace"); status != 200 {
		t.Fatalf("sign-in-as: %d", status)
	}
	do("GET", app+"/auth/login?returnTo=%2Forders")
	if status, body := do("GET", app+"/auth/session"); !strings.Contains(body, "Grace") || !strings.Contains(body, "orders-demo:viewer") || status != 200 {
		t.Fatalf("viewer session: %d %s", status, body)
	}
	if status, _ := do("POST", app+"/api/orders", "Content-Type", "application/json", "Sec-Fetch-Site", "same-origin"); status != 403 {
		t.Fatalf("a viewer may not place orders: %d", status)
	}
	// sign out: the answer names the platform's end-session URL on the provider's origin, which sends the
	// browser on to the application's signed-out page.
	status, body := do("POST", app+"/auth/logout", "Sec-Fetch-Site", "same-origin")
	if status != 200 {
		t.Fatalf("logout: %d %s", status, body)
	}
	var ended struct {
		EndSessionURL string `json:"endSessionUrl"`
	}
	if err := json.Unmarshal([]byte(body), &ended); err != nil || ended.EndSessionURL == "" {
		t.Fatalf("logout names no end-session URL: %s (%v)", body, err)
	}
	endReq, err := http.NewRequestWithContext(t.Context(), "GET", ended.EndSessionURL, nil)
	if err != nil {
		t.Fatal(err)
	}
	endRes, err := (&http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}).Do(endReq)
	if err != nil {
		t.Fatal(err)
	}
	_ = endRes.Body.Close()
	if endRes.StatusCode != 302 || endRes.Header.Get("Location") != app+"/signed-out" {
		t.Fatalf("end-session: %d -> %q", endRes.StatusCode, endRes.Header.Get("Location"))
	}
	// authorize-error sends the browser to the application's page for it.
	do("POST", ctl+"/control/end-sessions")
	do("POST", ctl+"/control/authorize-error?value=access_denied")
	do("GET", app+"/auth/login")
	if status, body := do("GET", app+"/auth/session"); status != 200 || !strings.Contains(body, `"authenticated":false`) {
		t.Fatalf("a refused sign-in leaves no session: %d %s", status, body)
	}
	do("POST", ctl+"/control/authorize-error?value=")
	if status, _ := do("POST", ctl+"/control/reset"); status != 200 {
		t.Fatalf("reset: %d", status)
	}
	if status, body := do("POST", ctl+"/control/login-rate-limit?value=true"); status != 200 {
		t.Fatalf("login-rate-limit: %d %s", status, body)
	}
	noFollow := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	codes := make([]int, 0, 2)
	for range 2 {
		req, err := http.NewRequestWithContext(t.Context(), "GET", app+"/auth/login", nil)
		if err != nil {
			t.Fatal(err)
		}
		res, err := noFollow.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		_ = res.Body.Close()
		codes = append(codes, res.StatusCode)
	}
	if len(codes) != 2 || codes[0] != 302 || codes[1] != 429 {
		t.Errorf("one sign-in a minute: %v", codes)
	}
	if status, _ := do("POST", ctl+"/control/provider-down?value=true"); status != 200 {
		t.Fatalf("provider-down: %d", status)
	}
}
