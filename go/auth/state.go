package auth

import (
	"context"
	"net/http"
	"sync"
)

// requestState is what this module remembers for one request: cookies to set, and the session once
// read. The middleware installs it; code reached without it gets a throwaway state.
type requestState struct {
	mu            sync.Mutex
	cookies       []string
	headers       [][2]string
	sessionKnown  bool
	session       *Authenticated
	authenticated *Authenticated
}

type stateKey struct{}

func stateOf(r *http.Request) *requestState {
	if st, ok := r.Context().Value(stateKey{}).(*requestState); ok {
		return st
	}
	return &requestState{}
}

func withState(r *http.Request) (*http.Request, *requestState) {
	st := &requestState{}
	return r.WithContext(context.WithValue(r.Context(), stateKey{}, st)), st
}

func (s *requestState) addCookie(v string) {
	s.mu.Lock()
	s.cookies = append(s.cookies, v)
	s.mu.Unlock()
}

func (s *requestState) takeCookies() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.cookies
	s.cookies = nil
	return out
}

// setHeader asks for a response header (replacing any of the name) to be written with the cookies.
func (s *requestState) setHeader(name, value string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, h := range s.headers {
		if h[0] == name {
			s.headers[i][1] = value
			return
		}
	}
	s.headers = append(s.headers, [2]string{name, value})
}

func (s *requestState) takeHeaders() [][2]string {
	s.mu.Lock()
	defer s.mu.Unlock()
	out := s.headers
	s.headers = nil
	return out
}

func (s *requestState) known() (*Authenticated, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.session, s.sessionKnown
}

func (s *requestState) setSession(a *Authenticated) {
	s.mu.Lock()
	s.session, s.sessionKnown = a, true
	s.mu.Unlock()
}

func (s *requestState) setAuthenticated(a *Authenticated) {
	s.mu.Lock()
	s.authenticated = a
	s.mu.Unlock()
}

// Current returns who the request is authenticated as, once the context resolver ran (on a route
// that requires a context). It is false before resolution and on other routes (use
// [Sessions.Current] there).
func Current(r *http.Request) (Authenticated, bool) {
	st := stateOf(r)
	st.mu.Lock()
	defer st.mu.Unlock()
	if st.authenticated == nil {
		return Authenticated{}, false
	}
	return *st.authenticated, true
}
