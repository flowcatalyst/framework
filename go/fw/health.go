package fw

import (
	"context"
	"errors"
	"slices"
	"sync"
	"time"
)

// Criticality says whether a health check decides readiness (core contract section 1).
type Criticality int

const (
	// Critical checks make the application not ready when they are not up.
	Critical Criticality = iota + 1
	// Informational checks are run and reported but never decide readiness.
	Informational
)

func (c Criticality) String() string {
	switch c {
	case Critical:
		return "CRITICAL"
	case Informational:
		return "INFORMATIONAL"
	}
	return "UNKNOWN"
}

// HealthCheck checks one dependency: nil when it is up, an error saying why it is down. It must
// return when ctx is done; a check still running after app.health.timeout reports TimedOut. The
// error's text is shown on the health endpoint, which is not authenticated: keep secrets and
// internal details out of it.
type HealthCheck func(ctx context.Context) error

// HealthStatus is the outcome of one check.
type HealthStatus int

const (
	// Up means the check returned nil.
	Up HealthStatus = iota + 1
	// Down means the check returned an error (its text is the reason).
	Down
	// TimedOut means the check did not return within app.health.timeout.
	TimedOut
)

func (s HealthStatus) String() string {
	switch s {
	case Up:
		return "UP"
	case Down:
		return "DOWN"
	case TimedOut:
		return "TIMED_OUT"
	}
	return "UNKNOWN"
}

// HealthResult is one check's result.
type HealthResult struct {
	Name        string
	Status      HealthStatus
	Reason      string
	Criticality Criticality
}

// HealthReport is the result of every check, in registration order.
type HealthReport struct {
	Results []HealthResult
}

// IsUp reports whether every check is up.
func (r HealthReport) IsUp() bool {
	for _, x := range r.Results {
		if x.Status != Up {
			return false
		}
	}
	return true
}

// IsReady reports whether every critical check is up.
func (r HealthReport) IsReady() bool {
	for _, x := range r.Results {
		if x.Criticality == Critical && x.Status != Up {
			return false
		}
	}
	return true
}

// Result returns the result of the named check.
func (r HealthReport) Result(name string) (HealthResult, bool) {
	for _, x := range r.Results {
		if x.Name == name {
			return x, true
		}
	}
	return HealthResult{}, false
}

type healthEntry struct {
	name        string
	criticality Criticality
	check       HealthCheck
}

// health holds an application's checks.
type health struct {
	mu      sync.Mutex
	entries []healthEntry
	timeout time.Duration
	// overrides from app.health.critical / app.health.informational
	critical, informational []string
}

func (h *health) add(name string, c Criticality, check HealthCheck) error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if name == "" {
		return errors.New("a health check needs a name")
	}
	if check == nil {
		return errors.New("health check " + quote(name) + " has no function")
	}
	if c != Critical && c != Informational {
		return errors.New("health check " + quote(name) + " has no criticality")
	}
	for _, e := range h.entries {
		if e.name == name {
			return errors.New("health check " + quote(name) + " added twice")
		}
	}
	h.entries = append(h.entries, healthEntry{name: name, criticality: c, check: check})
	return nil
}

func (h *health) effective(e healthEntry) Criticality {
	switch {
	case slices.Contains(h.critical, e.name):
		return Critical
	case slices.Contains(h.informational, e.name):
		return Informational
	}
	return e.criticality
}

// unmatched returns the override names that match no check.
func (h *health) unmatched() []string {
	h.mu.Lock()
	defer h.mu.Unlock()
	var out []string
	for _, n := range append(slices.Clone(h.critical), h.informational...) {
		if !slices.ContainsFunc(h.entries, func(e healthEntry) bool { return e.name == n }) {
			out = append(out, n)
		}
	}
	return out
}

// run runs every check, each with the timeout, concurrently.
func (h *health) run(ctx context.Context) HealthReport {
	h.mu.Lock()
	entries := slices.Clone(h.entries)
	timeout := h.timeout
	h.mu.Unlock()
	results := make([]HealthResult, len(entries))
	var wg sync.WaitGroup
	for i, e := range entries {
		wg.Go(func() {
			results[i] = HealthResult{Name: e.name, Criticality: h.effective(e)}
			status, reason := runCheck(ctx, e.check, timeout)
			results[i].Status, results[i].Reason = status, reason
		})
	}
	wg.Wait()
	return HealthReport{Results: results}
}

func runCheck(ctx context.Context, check HealthCheck, timeout time.Duration) (HealthStatus, string) {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	done := make(chan error, 1)
	go func() {
		defer func() {
			if p := recover(); p != nil {
				done <- errors.New("the check panicked")
			}
		}()
		done <- check(ctx)
	}()
	select {
	case err := <-done:
		if err != nil {
			return Down, err.Error()
		}
		return Up, ""
	case <-ctx.Done():
		return TimedOut, "no answer within " + timeout.String()
	}
}
