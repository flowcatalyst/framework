package auth

import (
	"net/http"
	"time"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
)

// Sessions is the browser session of a request (contract section 7.1, step 3), provided by the
// module. Routes that require a context get it through the resolver; other handlers call
// [Sessions.Current]. On an unsafe method the CSRF rules run first.
type Sessions struct {
	manager     *manager
	cookies     cookieNames
	csrf        *httpfw.CSRFGuard
	authorities *authorities
	clock       fw.Clock
}

// Current returns the session of the request, read once per request: timeouts checked, lastSeenAt
// written back when due. It returns nil when there is no valid session (a stale cookie is cleared
// on the response). Errors: 403 CROSS_ORIGIN_REQUEST or 415 on an unsafe request that breaks the
// CSRF rules, 403 CLIENT_NOT_PERMITTED when the selected client is not the principal's, the
// application's selector error, or a store failure (500).
func (s *Sessions) Current(r *http.Request) (*Authenticated, error) {
	st := stateOf(r)
	if known, ok := st.known(); ok {
		return known, nil
	}
	handle := readCookie(r, s.cookies.sessionName())
	if handle == "" {
		st.setSession(nil)
		return nil, nil
	}
	if err := s.csrf.Check(r); err != nil {
		return nil, err
	}
	l, ok, err := s.manager.session(r.Context(), handle)
	if err != nil {
		return nil, err
	}
	if !ok {
		st.addCookie(s.cookies.clear(s.cookies.sessionName()))
		st.setSession(nil)
		return nil, nil
	}
	if l.handleChanged {
		st.addCookie(s.cookies.set(s.cookies.sessionName(), l.handle, int64(l.data.absoluteUntil.Sub(s.clock.Now())/time.Second)))
	}
	a, err := s.authorities.authenticate(l.data.principal, r, SourceSession, l.data.absoluteUntil, l.idleExpiresAt, l.data.authTime)
	if err != nil {
		return nil, err
	}
	st.setSession(&a)
	return &a, nil
}
