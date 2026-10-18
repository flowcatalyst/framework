package ratelimit_test

import (
	"context"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/fwtest"
	"github.com/flowcatalyst/framework/go/internal/conformance"
	"github.com/flowcatalyst/framework/go/ratelimit"
)

type vectorPolicy struct {
	Limit       int   `json:"limit"`
	PeriodNanos int64 `json:"periodNanos"`
	Burst       int   `json:"burst"`
}

func (p vectorPolicy) build() (ratelimit.Policy, error) {
	return ratelimit.NewPolicy(p.Limit, time.Duration(p.PeriodNanos), p.Burst)
}

// TestGCRAVectors runs conformance/ratelimit/gcra.json (Java: RateLimitConformanceTest): decisions of
// the memory driver on a pinned clock, per-request policies, policy range checks, limiter names.
func TestGCRAVectors(t *testing.T) {
	var v struct {
		Scenarios []struct {
			Name    string       `json:"name"`
			Policy  vectorPolicy `json:"policy"`
			MaxKeys int          `json:"maxKeys"`
			Steps   []struct {
				At     int64         `json:"at"`
				Key    string        `json:"key"`
				Cost   int           `json:"cost"`
				Policy *vectorPolicy `json:"policy"`
				Expect struct {
					Result     string `json:"result"`
					Remaining  int64  `json:"remaining"`
					ResetAfter int64  `json:"resetAfterNanos"`
					RetryAfter int64  `json:"retryAfterNanos"`
				} `json:"expect"`
			} `json:"steps"`
		} `json:"scenarios"`
		PolicyChecks []struct {
			Policy   vectorPolicy `json:"policy"`
			Accepted bool         `json:"accepted"`
		} `json:"policyChecks"`
		Names []struct {
			Name     string `json:"name"`
			Accepted bool   `json:"accepted"`
		} `json:"names"`
		Defaults struct {
			BurstOfWithoutBurst int `json:"burstOfWithoutBurst"`
		} `json:"defaults"`
	}
	conformance.ReadJSON(t, "ratelimit/gcra.json", &v)
	if len(v.Scenarios) == 0 || len(v.PolicyChecks) == 0 || len(v.Names) == 0 {
		t.Fatal("the vector is empty")
	}
	base := time.Unix(0, 0)
	for _, s := range v.Scenarios {
		t.Run(s.Name, func(t *testing.T) {
			p, err := s.Policy.build()
			if err != nil {
				t.Fatal(err)
			}
			clk := fwtest.NewClock(base)
			m, err := ratelimit.NewMemory("vector", p, ratelimit.MaxKeys(s.MaxKeys), ratelimit.MemoryClock(clk))
			if err != nil {
				t.Fatal(err)
			}
			for i, st := range s.Steps {
				clk.Set(base.Add(time.Duration(st.At)))
				var d ratelimit.Decision
				var err error
				if st.Policy != nil {
					sp, perr := st.Policy.build()
					if perr != nil {
						t.Fatalf("step %d: %v", i, perr)
					}
					d, err = m.AcquireWith(context.Background(), st.Key, st.Cost, sp)
				} else {
					d, err = m.Acquire(context.Background(), st.Key, st.Cost)
				}
				if st.Expect.Result == "error" {
					if err == nil {
						t.Errorf("step %d: want an error, got %#v", i, d)
					}
					continue
				}
				if err != nil {
					t.Fatalf("step %d: %v", i, err)
				}
				switch got := d.(type) {
				case ratelimit.Allowed:
					if st.Expect.Result != "allowed" || int64(got.Remaining) != st.Expect.Remaining || int64(got.ResetAfter) != st.Expect.ResetAfter {
						t.Errorf("step %d: %#v, want %+v", i, got, st.Expect)
					}
				case ratelimit.Limited:
					if st.Expect.Result != "limited" || int64(got.RetryAfter) != st.Expect.RetryAfter || int64(got.ResetAfter) != st.Expect.ResetAfter {
						t.Errorf("step %d: %#v, want %+v", i, got, st.Expect)
					}
				}
			}
		})
	}
	for _, c := range v.PolicyChecks {
		_, err := c.Policy.build()
		if (err == nil) != c.Accepted {
			t.Errorf("policy %+v: accepted = %v, want %v (%v)", c.Policy, err == nil, c.Accepted, err)
		}
	}
	for _, n := range v.Names {
		if err := ratelimit.CheckName(n.Name); (err == nil) != n.Accepted {
			t.Errorf("name %q: accepted = %v, want %v", n.Name, err == nil, n.Accepted)
		}
	}
	// PolicyOf(limit, period) has burst = limit
	if p, err := ratelimit.PolicyOf(v.Defaults.BurstOfWithoutBurst, time.Second); err != nil || p.Burst() != v.Defaults.BurstOfWithoutBurst {
		t.Errorf("PolicyOf burst %v (%v), want %d", p.Burst(), err, v.Defaults.BurstOfWithoutBurst)
	}
}

func TestOnlyUnlimitedIsUnlimited(t *testing.T) {
	same, err := ratelimit.NewPolicy(ratelimit.MaxLimit, time.Second, ratelimit.MaxLimit)
	if err != nil {
		t.Fatal(err)
	}
	if same.IsUnlimited() || !ratelimit.Unlimited.IsUnlimited() || (ratelimit.Policy{}).IsUnlimited() {
		t.Fatal("only the Unlimited value means no limit")
	}
}
