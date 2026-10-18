package auth

import (
	"context"
	"encoding/json/v2"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf16"

	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/internal/javafmt"
)

// loginLifetime is how long a login record lives (the platform's code lifetime).
const loginLifetime = 10 * time.Minute

// sessionData is a browser session as stored (contract section 6.1).
type sessionData struct {
	principal                                      Principal
	authTime, createdAt, lastSeenAt, absoluteUntil time.Time
}

// live is a session after a read.
type live struct {
	data          sessionData
	handle        string
	handleChanged bool
	idleExpiresAt time.Time
}

// manager keeps sessions and login records in a [Store] (contract section 6): the JSON shapes,
// the idle and absolute timeouts, and when lastSeenAt is written back.
type manager struct {
	store      Store
	clock      fw.Clock
	idle       time.Duration
	absolute   time.Duration
	touchAfter time.Duration
}

func newManager(store Store, clock fw.Clock, idle, absolute time.Duration) (*manager, error) {
	if idle <= 0 || absolute <= 0 {
		return nil, errors.New("session timeouts must be positive")
	}
	return &manager{
		store: store, clock: clock, idle: idle, absolute: absolute,
		touchAfter: min(idle/2, time.Minute),
	}, nil
}

// createSession stores a session and returns its handle. The store's expiry is the earlier of the
// absolute and the idle expiry.
func (m *manager) createSession(ctx context.Context, p Principal, authTime time.Time) (string, error) {
	now := m.clock.Now()
	d := sessionData{principal: withoutScopes(p), authTime: authTime, createdAt: now, lastSeenAt: now, absoluteUntil: now.Add(m.absolute)}
	return m.store.Create(ctx, KindSession, encodeSession(d), m.idleExpiry(d))
}

// session reads a session, deleting it when a timeout has passed and writing lastSeenAt back when
// due (older than the smaller of one minute and half the idle timeout).
func (m *manager) session(ctx context.Context, handle string) (live, bool, error) {
	raw, found, err := m.store.Read(ctx, KindSession, handle)
	if err != nil || !found {
		return live{}, false, err
	}
	d, ok := decodeSession(raw)
	now := m.clock.Now()
	if !ok || !now.Before(d.absoluteUntil) || !now.Before(d.lastSeenAt.Add(m.idle)) {
		return live{}, false, m.store.Delete(ctx, KindSession, handle)
	}
	if now.Sub(d.lastSeenAt) >= m.touchAfter {
		d.lastSeenAt = now
		next, err := m.store.Update(ctx, KindSession, handle, encodeSession(d), m.idleExpiry(d))
		if err != nil {
			return live{}, false, err
		}
		return live{data: d, handle: next, handleChanged: next != handle, idleExpiresAt: m.idleExpiry(d)}, true, nil
	}
	return live{data: d, handle: handle, idleExpiresAt: m.idleExpiry(d)}, true, nil
}

func (m *manager) deleteSession(ctx context.Context, handle string) error {
	return m.store.Delete(ctx, KindSession, handle)
}

func (m *manager) createLogin(ctx context.Context, l loginData) (string, error) {
	return m.store.Create(ctx, KindLogin, encodeLogin(l), l.createdAt.Add(loginLifetime))
}

// takeLogin reads and deletes a login record: it can complete once.
func (m *manager) takeLogin(ctx context.Context, handle string) (loginData, bool, error) {
	raw, found, err := m.store.Take(ctx, KindLogin, handle)
	if err != nil || !found {
		return loginData{}, false, err
	}
	l, ok := decodeLogin(raw)
	return l, ok, nil
}

func (m *manager) idleExpiry(d sessionData) time.Time {
	idleEnd := d.lastSeenAt.Add(m.idle)
	if idleEnd.Before(d.absoluteUntil) {
		return idleEnd
	}
	return d.absoluteUntil
}

func withoutScopes(p Principal) Principal {
	p.Scopes = nil
	return p
}

// ---- JSON (contract sections 6.1 and 6.2) --------------------------------------------------

type principalJSON struct {
	ID    string `json:"id"`
	Kind  string `json:"kind"`
	Name  string `json:"name,omitzero"`
	Email string `json:"email,omitzero"`
}

type sessionJSON struct {
	V            int           `json:"v"`
	Principal    principalJSON `json:"principal"`
	Tier         string        `json:"tier,omitzero"`
	HomeClient   string        `json:"homeClient,omitzero"`
	Clients      []string      `json:"clients"`
	Applications []string      `json:"applications"`
	Roles        []string      `json:"roles"`
	AuthTime     string        `json:"authTime"`
	CreatedAt    string        `json:"createdAt"`
	LastSeenAt   string        `json:"lastSeenAt"`
	AbsoluteAt   string        `json:"absoluteExpiresAt"`
}

func encodeSession(d sessionData) []byte {
	p := d.principal
	b, err := json.Marshal(sessionJSON{
		V:         1,
		Principal: principalJSON{ID: p.ID, Kind: p.Kind.String(), Name: p.Name, Email: p.Email},
		Tier:      p.Tier, HomeClient: p.HomeClient,
		Clients: nonNil(p.Clients), Applications: nonNil(p.Applications), Roles: nonNil(p.Roles),
		AuthTime: javafmt.Instant(d.authTime), CreatedAt: javafmt.Instant(d.createdAt),
		LastSeenAt: javafmt.Instant(d.lastSeenAt), AbsoluteAt: javafmt.Instant(d.absoluteUntil),
	})
	if err != nil {
		panic(fmt.Sprintf("auth: cannot encode a session: %v", err)) // plain strings and times
	}
	return b
}

func nonNil(s []string) []string {
	if s == nil {
		return []string{}
	}
	return s
}

// sessionIn is the lenient reading of a stored session: unknown members are ignored and element
// types other than text are dropped, as the Java reader does.
type sessionIn struct {
	V         int `json:"v"`
	Principal struct {
		ID   string `json:"id"`
		Kind string `json:"kind"`
		Name string `json:"name"`
		Mail string `json:"email"`
	} `json:"principal"`
	Tier         string `json:"tier"`
	HomeClient   string `json:"homeClient"`
	Clients      any    `json:"clients"`
	Applications any    `json:"applications"`
	Roles        any    `json:"roles"`
	AuthTime     string `json:"authTime"`
	CreatedAt    string `json:"createdAt"`
	LastSeenAt   string `json:"lastSeenAt"`
	AbsoluteAt   string `json:"absoluteExpiresAt"`
}

func decodeSession(raw []byte) (sessionData, bool) {
	var in sessionIn
	if err := json.Unmarshal(raw, &in); err != nil || in.V != 1 || strings.TrimSpace(in.Principal.ID) == "" {
		return sessionData{}, false
	}
	kind := in.Principal.Kind
	if kind == "" {
		kind = "USER"
	}
	var times [4]time.Time
	for i, s := range []string{in.AuthTime, in.CreatedAt, in.LastSeenAt, in.AbsoluteAt} {
		t, err := time.Parse(time.RFC3339Nano, s)
		if err != nil {
			return sessionData{}, false
		}
		times[i] = t.UTC()
	}
	return sessionData{
		principal: Principal{
			ID: in.Principal.ID, Kind: kindOf(kind), Name: in.Principal.Name, Email: in.Principal.Mail,
			Tier: in.Tier, HomeClient: in.HomeClient,
			Clients: texts(in.Clients), Applications: texts(in.Applications), Roles: texts(in.Roles),
		},
		authTime: times[0], createdAt: times[1], lastSeenAt: times[2], absoluteUntil: times[3],
	}, true
}

type loginJSON struct {
	V            int    `json:"v"`
	State        string `json:"state"`
	Nonce        string `json:"nonce"`
	CodeVerifier string `json:"codeVerifier"`
	ReturnTo     string `json:"returnTo"`
	CreatedAt    string `json:"createdAt"`
}

func encodeLogin(l loginData) []byte {
	b, err := json.Marshal(loginJSON{V: 1, State: l.state, Nonce: l.nonce, CodeVerifier: l.codeVerifier, ReturnTo: l.returnTo, CreatedAt: javafmt.Instant(l.createdAt)})
	if err != nil {
		panic(fmt.Sprintf("auth: cannot encode a login: %v", err))
	}
	return b
}

func decodeLogin(raw []byte) (loginData, bool) {
	var in loginJSON
	if err := json.Unmarshal(raw, &in); err != nil || in.V != 1 {
		return loginData{}, false
	}
	created, err := time.Parse(time.RFC3339Nano, in.CreatedAt)
	if err != nil || in.State == "" || in.Nonce == "" || in.CodeVerifier == "" || !isSameOriginPath(in.ReturnTo) {
		return loginData{}, false
	}
	return loginData{state: in.State, nonce: in.Nonce, codeVerifier: in.CodeVerifier, returnTo: in.ReturnTo, createdAt: created.UTC()}, true
}

// isSameOriginPath reports whether a value is a same-origin path (contract section 4.1): it starts
// with "/" but not "//", has no backslash, no character below U+0020 and no U+007F, and is at
// most 2048 characters.
func isSameOriginPath(v string) bool {
	if v == "" || len(utf16.Encode([]rune(v))) > 2048 || v[0] != '/' || strings.HasPrefix(v, "//") {
		return false
	}
	for i := range len(v) {
		if c := v[i]; c == '\\' || c < 0x20 || c == 0x7f {
			return false
		}
	}
	return true
}
