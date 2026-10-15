package ratelimit

import "time"

// step is the GCRA decision on one stored theoretical arrival time (TAT), shared by the memory
// driver and checked against the Redis script's arithmetic in tests. All values are in one time
// unit.
type step struct {
	allowed bool
	// store says whether newTat is to be stored: always when allowed; when limited, only when the
	// stored TAT was beyond the policy's window and was capped (contract section 3a).
	store      bool
	newTat     int64
	remaining  int64
	retryAfter int64
	resetAfter int64
}

// gcra makes one decision. The TAT is capped at now + burst*interval (an empty bucket under this
// policy); with one fixed policy the cap never applies.
func gcra(storedTat, now, interval, burst, cost int64) step {
	window := burst * interval // bounded by Policy, no overflow
	tat := min(max(storedTat, now), now+window)
	next := tat + interval*cost
	allowAt := next - window
	if now < allowAt {
		return step{allowed: false, store: tat < storedTat, newTat: tat, retryAfter: allowAt - now, resetAfter: tat - now}
	}
	return step{allowed: true, store: true, newTat: next, remaining: (now - allowAt) / interval, resetAfter: next - now}
}

func (s step) decision(nanosPerUnit int64) Decision {
	if s.allowed {
		return Allowed{Remaining: s.remaining, ResetAfter: time.Duration(s.resetAfter * nanosPerUnit)}
	}
	return Limited{
		RetryAfter: time.Duration(s.retryAfter * nanosPerUnit),
		ResetAfter: time.Duration(s.resetAfter * nanosPerUnit),
	}
}
