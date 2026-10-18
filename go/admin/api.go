package admin

import (
	"slices"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// SectionView is a section in the navigation (admin contract section 4.1).
type SectionView struct {
	ID          string  `json:"id"`
	Title       string  `json:"title"`
	Path        string  `json:"path"`
	Permission  string  `json:"permission"`
	Icon        *string `json:"icon,omitzero"`
	Description *string `json:"description,omitzero"`
}

// Navigation is the sections the caller may use, in registration order.
type Navigation struct {
	Sections []SectionView `json:"sections"`
}

// CheckStatus is the state of one health check.
type CheckStatus string

// The check states.
const (
	// CheckUp means the check passed.
	CheckUp CheckStatus = "UP"
	// CheckDown means the check failed.
	CheckDown CheckStatus = "DOWN"
	// CheckTimedOut means the check did not answer in time.
	CheckTimedOut CheckStatus = "TIMED_OUT"
)

// EnumValues implements openapi.EnumValuer.
func (CheckStatus) EnumValues() []string { return []string{"UP", "DOWN", "TIMED_OUT"} }

// Check is one health check; the failure message is deliberately not on the wire (it can name
// connection strings and hosts; the logs have it).
type Check struct {
	Name     string      `json:"name"`
	Status   CheckStatus `json:"status"`
	Critical bool        `json:"critical"`
}

// About is what the application is and whether it is healthy (admin contract section 4.2).
type About struct {
	Application string    `json:"application"`
	Framework   *string   `json:"framework,omitzero"`
	Environment string    `json:"environment"`
	StartedAt   time.Time `json:"startedAt"`
	Ready       bool      `json:"ready"`
	Checks      []Check   `json:"checks"`
}

// Page is one page of a list, by cursor: NextCursor goes in as "after" for the next page and is
// absent on the last one.
type Page[T any] struct {
	Items      []T     `json:"items"`
	NextCursor *string `json:"nextCursor,omitzero"`
}

// MaxLimit is the largest page an admin list serves; a limit below 1 is 1 and above is MaxLimit.
const MaxLimit = 200

// PageOf cuts one page out of a list sorted ascending by key (keys unique): the items after the
// cursor, at most limit. The cursor is the key of the last item of the previous page, opaque to
// callers. A limit below 1 is 1 and above [MaxLimit] is [MaxLimit].
func PageOf[T any](sorted []T, key func(T) string, after string, hasAfter bool, limit int) Page[T] {
	size := max(1, min(MaxLimit, limit))
	start := 0
	if hasAfter {
		for start < len(sorted) && key(sorted[start]) <= after {
			start++
		}
	}
	end := min(len(sorted), start+size)
	page := Page[T]{Items: slices.Clone(sorted[start:end])}
	if page.Items == nil {
		page.Items = []T{}
	}
	if end < len(sorted) {
		last := key(sorted[end-1])
		page.NextCursor = &last
	}
	return page
}

// Checks turns a health report into the wire form, without failure messages.
func Checks(report fw.HealthReport) []Check {
	out := make([]Check, 0, len(report.Results))
	for _, r := range report.Results {
		status := CheckDown
		switch r.Status {
		case fw.Up:
			status = CheckUp
		case fw.TimedOut:
			status = CheckTimedOut
		case fw.Down:
		}
		out = append(out, Check{Name: r.Name, Status: status, Critical: r.Criticality != fw.Informational})
	}
	return out
}

// operations are the skeleton's operations for one application: built per application because the
// permission carries the application code.
type operations struct {
	navigation httpfw.Operation[httpfw.NoBody, Navigation]
	about      httpfw.Operation[httpfw.NoBody, About]
}

func newOperations(application fw.ApplicationCode) operations {
	view := permissionOf(ConsoleView(application))
	return operations{
		navigation: httpfw.Get[Navigation]("/api/admin/navigation").
			ID("getAdminNavigation").Summary("The admin sections the caller may use").Tag("admin").
			Permission(view).Build(),
		about: httpfw.Get[About]("/api/admin/about").
			ID("getAdminAbout").Summary("What the application is and whether it is healthy").Tag("admin").
			Permission(view).Build(),
	}
}
