package ratelimit_test

import (
	"context"
	"encoding/json/jsontext"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

// spyLimiter records the key the middleware asks about and can play a store that is down.
type spyLimiter struct {
	*ratelimit.Memory
	down bool
	mu   sync.Mutex
	keys []string
}

func (s *spyLimiter) note(key string) {
	s.mu.Lock()
	s.keys = append(s.keys, key)
	s.mu.Unlock()
}

func (s *spyLimiter) Acquire(ctx context.Context, key string, cost int) (ratelimit.Decision, error) {
	return s.AcquireWith(ctx, key, cost, s.Policy())
}

func (s *spyLimiter) AcquireWith(ctx context.Context, key string, cost int, p ratelimit.Policy) (ratelimit.Decision, error) {
	s.note(key)
	if s.down {
		return nil, fmt.Errorf("%w: down", ratelimit.ErrStore)
	}
	return s.Memory.AcquireWith(ctx, key, cost, p)
}

// goDifferences are the vector cases in which Go's key differs from Java's, with the reason. Each
// is owner-visible (docs/contracts/rate-limiting.md section 6 and the Go guide). In these cases a
// differing key is logged, not failed, and the RateLimit-* counters (which follow from the shared
// bucket) are not compared; status is.
var goDifferences = map[string]string{
	"clientAddress: no address, an unparseable address":          "Go's RemoteAddr always carries a port, so host:port is parsed as the peer address (Java is handed the bare host and refuses 203.0.113.5:80)",
	"clientAddress: odd IPv4 spellings":                          "netip refuses leading zeros, short forms (1.2.3) and a bare integer; Java's InetAddress.ofLiteral reads them as decimal, inet_aton short form and integer. Go answers the shared bucket (the stricter result)",
	"clientAddress: zone ids and long values":                    "Go drops any IPv6 zone; Java refuses a zone naming an interface it does not know (fe80::1%eth0)",
	"forwardedFor(1): IPv6 entries, brackets, mapped IPv4, zone": "Go drops any IPv6 zone; Java refuses a named zone",
}

// TestRateLimitHTTPVectors runs conformance/ratelimit/http.json (Java: RateLimitConformanceTest).
func TestRateLimitHTTPVectors(t *testing.T) {
	var v struct {
		Cases []struct {
			Name           string       `json:"name"`
			Policy         vectorPolicy `json:"policy"`
			Resolver       string       `json:"resolver"`
			Trusted        int          `json:"trusted"`
			HeaderName     string       `json:"headerName"`
			Cost           int          `json:"cost"`
			Headers        bool         `json:"headers"`
			OnStoreFailure string       `json:"onStoreFailure"`
			StoreDown      bool         `json:"storeDown"`
			PolicyResolver bool         `json:"policyResolver"`
			BuildError     string       `json:"buildError"`
			Requests       []struct {
				At      int64       `json:"at"`
				Remote  *string     `json:"remote"`
				Headers [][2]string `json:"headers"`
			} `json:"requests"`
			Expect []struct {
				Status  int               `json:"status"`
				Key     *string           `json:"key"`
				Headers map[string]string `json:"headers"`
				Body    jsontext.Value    `json:"body"`
			} `json:"expect"`
		} `json:"cases"`
	}
	conformance.ReadJSON(t, "ratelimit/http.json", &v)
	if len(v.Cases) == 0 {
		t.Fatal("no cases")
	}
	base := time.Unix(0, 0)
	for _, c := range v.Cases {
		t.Run(c.Name, func(t *testing.T) {
			var keys ratelimit.KeyFunc
			var kerr error
			switch c.Resolver {
			case "literal":
				keys = func(r *http.Request) string { return r.Header.Get("X-Test-Key") }
			case "clientAddress":
				keys = ratelimit.ClientAddress()
			case "forwardedFor":
				keys, kerr = ratelimit.ForwardedFor(c.Trusted)
			case "header":
				keys = ratelimit.Header(c.HeaderName)
			default:
				t.Fatalf("unknown resolver %q", c.Resolver)
			}
			if c.BuildError != "" {
				if kerr == nil {
					t.Fatal("want the resolver refused")
				}
				return
			}
			if kerr != nil {
				t.Fatal(kerr)
			}
			p, err := c.Policy.build()
			if err != nil {
				t.Fatal(err)
			}
			clk := fwtest.NewClock(base)
			mem, err := ratelimit.NewMemory("vector", p, ratelimit.MemoryClock(clk))
			if err != nil {
				t.Fatal(err)
			}
			spy := &spyLimiter{Memory: mem, down: c.StoreDown}
			opts := []ratelimit.Option{ratelimit.WithCost(c.Cost), ratelimit.WithHeaders(c.Headers)}
			if c.OnStoreFailure == "DENY" {
				opts = append(opts, ratelimit.WithOnStoreFailure(ratelimit.Deny))
			}
			if c.PolicyResolver {
				opts = append(opts, ratelimit.WithPolicy(func(r *http.Request) (ratelimit.Policy, bool, error) {
					switch h := r.Header.Get("X-Test-Policy"); h {
					case "", "null":
						return ratelimit.Policy{}, false, nil
					case "throw":
						return ratelimit.Policy{}, false, fmt.Errorf("the resolver failed")
					case "unlimited":
						return ratelimit.Unlimited, true, nil
					default:
						var l, ms, b int
						if _, err := fmt.Sscanf(h, "%d/%d/%d", &l, &ms, &b); err != nil {
							return ratelimit.Policy{}, false, err
						}
						pp, err := ratelimit.NewPolicy(l, time.Duration(ms)*time.Millisecond, b)
						return pp, err == nil, err
					}
				}))
			}
			mw, err := ratelimit.Middleware(spy, keys, opts...)
			if err != nil {
				t.Fatal(err)
			}
			rt := httpfw.NewRoutes("vector")
			rt.Handle("POST /x", func(w http.ResponseWriter, _ *http.Request) error {
				httpfw.Bytes(w, 200, "application/json", []byte(`{"ok":true}`))
				return nil
			}, httpfw.With(mw))
			d, err := httpfw.Compile([]*httpfw.Routes{rt})
			if err != nil {
				t.Fatal(err)
			}
			diff, differs := goDifferences[c.Name]
			for i, rq := range c.Requests {
				clk.Set(base.Add(time.Duration(rq.At)))
				req := httptest.NewRequest(http.MethodPost, "/x", nil)
				req.RemoteAddr = ""
				if rq.Remote != nil {
					req.RemoteAddr = *rq.Remote
				}
				req.Header = http.Header{}
				for _, h := range rq.Headers {
					req.Header.Add(h[0], h[1])
				}
				before := len(spy.keys)
				rec := httptest.NewRecorder()
				d.ServeHTTP(rec, req)
				body, _ := io.ReadAll(rec.Result().Body)
				e := c.Expect[i]
				where := fmt.Sprintf("request %d", i)
				if rec.Code != e.Status {
					t.Errorf("%s: status %d, want %d: %s", where, rec.Code, e.Status, body)
				}
				var gotKey *string
				if len(spy.keys) > before {
					gotKey = &spy.keys[len(spy.keys)-1]
				}
				if (gotKey == nil) != (e.Key == nil) || (gotKey != nil && *gotKey != *e.Key) {
					if differs {
						t.Logf("%s: recorded difference: key %v, Java %v (%s)", where, deref(gotKey), deref(e.Key), diff)
						continue
					}
					t.Errorf("%s (remote %v, headers %v): key %v, want %v", where, deref(rq.Remote), rq.Headers, deref(gotKey), deref(e.Key))
				}
				if differs {
					continue
				}
				for _, name := range []string{"Retry-After", "RateLimit-Limit", "RateLimit-Remaining", "RateLimit-Reset"} {
					if got, want := rec.Header().Get(name), e.Headers[name]; got != want {
						t.Errorf("%s: %s = %q, want %q", where, name, got, want)
					}
				}
				conformance.EqualJSON(t, where+" body", e.Body, body)
			}
		})
	}
}

func deref(s *string) string {
	if s == nil {
		return "<nil>"
	}
	return *s
}
