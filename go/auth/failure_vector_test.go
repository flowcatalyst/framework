package auth_test

import (
	"encoding/json/v2"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

type vectorCase struct {
	Name      string `json:"name"`
	Condition string `json:"condition"`
	Request   struct {
		Method  string            `json:"method"`
		Path    string            `json:"path"`
		Headers map[string]string `json:"headers"`
	} `json:"request"`
	Expect struct {
		Status   int    `json:"status"`
		Location string `json:"location"`
		Error    string `json:"error"`
	} `json:"expect"`
}

// TestLoginFailureVector runs conformance/auth/login-failure.json (contract 4.1.1): a sign-in that
// cannot start is answered by the failure handler for a browser navigation and by the JSON
// envelope for everyone else.
func TestLoginFailureVector(t *testing.T) {
	raw, err := os.ReadFile("../../conformance/auth/login-failure.json")
	if err != nil {
		t.Fatal(err)
	}
	var doc struct {
		FailurePage string       `json:"failurePage"`
		Cases       []vectorCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatal(err)
	}
	if len(doc.Cases) != 20 {
		t.Fatalf("the vector has %d cases, expected 20", len(doc.Cases))
	}
	handler, err := auth.RedirectFailuresTo(doc.FailurePage)
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range doc.Cases {
		t.Run(c.Name, func(t *testing.T) {
			// a limiter of one request a minute: the first request below is the one that passes
			l, err := ratelimit.NewMemory("auth", ratelimit.MustPolicy(1, time.Minute, 1))
			if err != nil {
				t.Fatal(err)
			}
			limit, err := ratelimit.Middleware(l, ratelimit.ClientAddress())
			if err != nil {
				t.Fatal(err)
			}
			a := mustStart(t, config{options: []auth.Option{auth.FailureHandler(handler), auth.RateLimit(limit)}})
			b := a.browser()
			if c.Condition == "RATE_LIMITED" {
				if b.get("/auth/login").status != 302 {
					t.Fatal("the request that uses the budget")
				}
			} else {
				a.provider.Down(true)
			}
			var headers []string
			for k, v := range c.Request.Headers {
				headers = append(headers, k, v)
			}
			r := b.send(c.Request.Method, c.Request.Path, "", headers...)
			if r.status != c.Expect.Status {
				t.Fatalf("status %d, want %d (%s)", r.status, c.Expect.Status, r.body)
			}
			if c.Expect.Location != "" || r.status == 502 {
				if r.header.Get("Cache-Control") != "no-store" {
					t.Errorf("Cache-Control %q, want no-store", r.header.Get("Cache-Control"))
				}
			}
			for _, cookie := range r.cookies() {
				if strings.Contains(cookie, "fw_login_") {
					t.Errorf("no binding cookie on a refusal: %s", cookie)
				}
			}
			if c.Expect.Location != "" {
				if r.header.Get("Location") != c.Expect.Location || r.header.Get("Retry-After") != "" {
					t.Errorf("Location %q Retry-After %q", r.header.Get("Location"), r.header.Get("Retry-After"))
				}
			} else if r.header.Get("Location") != "" || r.str("error") != c.Expect.Error {
				t.Errorf("Location %q error %q, want the envelope %q", r.header.Get("Location"), r.str("error"), c.Expect.Error)
			}
		})
	}
}

func TestWithTheDefaultHandlerANavigationStillGetsTheEnvelope(t *testing.T) {
	a := mustStart(t, config{})
	a.provider.Down(true)
	r := a.browser().get("/auth/login", "Sec-Fetch-Mode", "navigate", "Accept", "text/html")
	if r.status != 502 || r.str("error") != "PLATFORM_UNAVAILABLE" {
		t.Fatalf("%d %s", r.status, r.body)
	}
}
