package flowcatalyst_test

import (
	"slices"
	"strings"
	"testing"

	"github.com/flowcatalyst/framework/go/flowcatalyst"
	"github.com/flowcatalyst/framework/go/internal/conformance"
)

// TestJobTargetVectors runs conformance/flowcatalyst/job-targets.json (Java: JobTargetsConformanceTest):
// the targetUrl the definition sync sends for each scheduled job, or the start-up problem.
func TestJobTargetVectors(t *testing.T) {
	var v struct {
		Cases []struct {
			Name  string `json:"name"`
			Setup struct {
				Settings map[string]string `json:"settings"`
				Handlers []string          `json:"handlers"`
				Jobs     []struct {
					Code      string  `json:"code"`
					TargetURL *string `json:"targetUrl"`
				} `json:"jobs"`
			} `json:"setup"`
			Expect struct {
				Targets []*string `json:"targets"`
				Problem string    `json:"problem"`
			} `json:"expect"`
		} `json:"cases"`
	}
	conformance.ReadJSON(t, "flowcatalyst/job-targets.json", &v)
	if len(v.Cases) == 0 {
		t.Fatal("no cases")
	}
	for _, c := range v.Cases {
		t.Run(c.Name, func(t *testing.T) {
			env := map[string]string{}
			for k, val := range c.Setup.Settings {
				env[strings.NewReplacer(".", "_", "-", "_").Replace(strings.ToUpper(k))] = val
			}
			o := rigOptions{env: env, define: func(d *flowcatalyst.Declared) {
				defs := d.Definitions("m")
				for _, j := range c.Setup.Jobs {
					job := flowcatalyst.NewScheduledJob(j.Code, j.Code, "0 0 2 * * *")
					if j.TargetURL != nil {
						job.TargetURL = *j.TargetURL
					}
					defs.ScheduledJob(job)
				}
			}}
			if len(c.Setup.Handlers) > 0 {
				o.locks = true
				o.register = func(fc *flowcatalyst.Platform) error {
					for _, code := range c.Setup.Handlers {
						if err := fc.Receivers().OnScheduledJob(code, flowcatalyst.JobOptions{}, okJob); err != nil {
							return err
						}
					}
					return nil
				}
			}
			if c.Expect.Problem != "" {
				_, err := tryRig(t, o)
				if err == nil {
					t.Fatalf("start-up succeeded, want a problem containing %q", c.Expect.Problem)
				}
				if !strings.Contains(err.Error(), c.Expect.Problem) {
					t.Errorf("problem %q lacks %q", err, c.Expect.Problem)
				}
				return
			}
			r := startRig(t, o)
			if _, err := r.fc.Sync(t.Context(), flowcatalyst.SyncApply); err != nil {
				t.Fatal(err)
			}
			req, _ := r.platform.syncOf("scheduled-jobs")
			var got []string
			for _, j := range req.json(t)["jobs"].([]any) {
				target, _ := j.(map[string]any)["targetUrl"].(string)
				got = append(got, target)
			}
			var want []string
			for _, w := range c.Expect.Targets {
				if w == nil {
					want = append(want, "")
				} else {
					want = append(want, *w)
				}
			}
			if !slices.Equal(got, want) {
				t.Errorf("targets %q, want %q", got, want)
			}
		})
	}
}
