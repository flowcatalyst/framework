package fw

import "time"

// Clock is the application's wall-clock source. Framework code never calls time.Now; it asks the
// Clock the application was given ([WithClock]), so tests can pin or move time.
//
// Durations are measured with the monotonic reading Go keeps inside every time.Time
// (t.Sub, time.Since on a value from Now), so a Clock only needs to give the wall time.
type Clock interface {
	// Now returns the current time.
	Now() time.Time
}

// SystemClock returns the clock of the operating system.
func SystemClock() Clock { return systemClock{} }

type systemClock struct{}

func (systemClock) Now() time.Time { return time.Now() } //nolint:forbidigo // the one place that reads the system clock
