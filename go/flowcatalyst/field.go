package flowcatalyst

import (
	"context"
	"net/http"
	"sync"
	"time"

	"github.com/flowcatalyst/framework/go/auth"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/httpfw"
	"github.com/flowcatalyst/framework/go/push"
)

// Field-worker support of the FlowCatalyst module (sessions-and-login contract sections 15.6 and
// 15.7): the online check before a high-value action, and the revocation events.

const maxLiveCache = 4096

// liveCache remembers, per login family (sid), until when the platform's "active" answer holds.
type liveCache struct {
	mu    sync.Mutex
	until map[string]time.Time
}

func (c *liveCache) fresh(sid string, now time.Time) bool {
	c.mu.Lock()
	defer c.mu.Unlock()
	until, ok := c.until[sid]
	return ok && now.Before(until)
}

func (c *liveCache) remember(sid string, now, until time.Time) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if len(c.until) >= maxLiveCache {
		for k, u := range c.until {
			if !now.Before(u) {
				delete(c.until, k)
			}
		}
		if len(c.until) >= maxLiveCache { // all still live: drop one rather than grow
			for k := range c.until {
				delete(c.until, k)
				break
			}
		}
	}
	c.until[sid] = until
}

// RequireLiveField is route middleware for an action that cannot wait for a field access token to
// expire (opening a gate, releasing a load): it asks the platform, with the application's service
// account (permission platform:iam:field-token:introspect), whether the token is still active,
// that is, its login family is not revoked and the worker and the device are both active. An
// active answer is remembered per sid for maxAge (0: ask every time). Only active answers are
// remembered, so a revocation is seen at the next check once the cached answer is older than
// maxAge.
//
//   - not active: 401 UNAUTHENTICATED with WWW-Authenticate: DPoP error="invalid_token";
//   - the platform cannot be asked (down, refused, the service account lacks the permission, the
//     module disabled): 503 UNAVAILABLE. It fails closed: a high-value action is never let through
//     unchecked;
//   - a request that was not authenticated as a field worker: 403 FORBIDDEN.
//
// Use it on a route that requires a context, after [auth.RequireSource] if the route should say
// so itself:
//
//	rt.HandleContext("POST /api/gate/open", open, httpfw.With(fc.RequireLiveField(30*time.Second)))
func (p *Platform) RequireLiveField(maxAge time.Duration) httpfw.Middleware {
	cache := &liveCache{until: map[string]time.Time{}}
	return func(next httpfw.HandlerFunc) httpfw.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) error {
			a, ok := auth.Current(r)
			if !ok || a.Source != auth.SourceField || a.Field == nil {
				return httpfw.Forbidden("FORBIDDEN", "this route needs a field worker")
			}
			now := p.b.Clock().Now()
			if maxAge > 0 && cache.fresh(a.Field.SessionID, now) {
				return next(w, r)
			}
			active, err := p.introspect(r.Context(), r, a.Field)
			if err != nil {
				p.log.Warn("the field token could not be checked online", "error", err.Error())
				return httpfw.NewError(http.StatusServiceUnavailable, "UNAVAILABLE", "the sign-in cannot be checked right now; try again")
			}
			if !active {
				w.Header().Set("WWW-Authenticate", `DPoP error="invalid_token"`)
				return httpfw.Unauthenticated("authentication is required")
			}
			if maxAge > 0 {
				cache.remember(a.Field.SessionID, now, now.Add(maxAge))
			}
			return next(w, r)
		}
	}
}

// introspect asks the platform whether the request's field token is active for this worker.
func (p *Platform) introspect(ctx context.Context, r *http.Request, f *auth.FieldPrincipal) (bool, error) {
	if !p.client.Enabled() {
		return false, ErrDisabled
	}
	token := r.Header.Get("Authorization")
	if len(token) > len("DPoP ") {
		token = token[len("DPoP "):]
	}
	res, err := p.client.sdk.FieldTokens().Introspect(ctx, token)
	if err != nil {
		return false, callError("field token introspection", err)
	}
	return res.Active && (res.Sub == "" || res.Sub == f.WorkerID), nil
}

// FieldRevocation is a revocation the platform announced: [WorkerSuspended], [DeviceRevoked] or
// [SessionRevoked]. The events are for application state (cached work assignments, open gate
// sessions), not for token validity: access tokens expire on their own within 10 minutes (use
// [Platform.RequireLiveField] where that is too long).
//
//sumtype:decl
type FieldRevocation interface{ fieldRevocation() }

// WorkerSuspended is platform:iam:field-worker:suspended: the worker was suspended and every
// session of the worker revoked.
type WorkerSuspended struct{ WorkerID, ClientID string }

// DeviceRevoked is platform:iam:field-device:revoked: the device was revoked for good.
type DeviceRevoked struct{ DeviceID, ClientID string }

// SessionRevoked is platform:iam:field-session:revoked. SessionID is the login family (the token's
// sid) and is set only when one family was revoked (a refresh-token reuse); an administrator
// revoking all sessions of a worker or a device gives WorkerID or DeviceID and no SessionID. Fields
// the platform did not send are empty. Reason is the platform's (REUSE, ADMIN, WORKER_SUSPENDED,
// DEVICE_REVOKED, ...).
type SessionRevoked struct{ SessionID, WorkerID, DeviceID, ClientID, Reason string }

func (WorkerSuspended) fieldRevocation() {}
func (DeviceRevoked) fieldRevocation()   {}
func (SessionRevoked) fieldRevocation()  {}

// PushHolder names the push streams a revocation ends (push contract section 7): the worker's for
// a suspension, the device's for a revoked device, and for a revoked session the login family's
// (or, when the platform revoked every session of a worker or a device, theirs). Call
// push.Pushes.EndStreams with it from the handler of [OnFieldRevocation]:
//
//	flowcatalyst.OnFieldRevocation(receivers, func(ctx context.Context, cx fw.Context, ev flowcatalyst.FieldRevocation) error {
//		return pushes.EndStreams(ctx, flowcatalyst.PushHolder(ev))
//	})
func PushHolder(event FieldRevocation) push.Holder {
	switch e := event.(type) {
	case WorkerSuspended:
		return push.Holder{Principal: e.WorkerID}
	case DeviceRevoked:
		return push.Holder{Device: e.DeviceID}
	case SessionRevoked:
		return push.Holder{Session: e.SessionID, Principal: e.WorkerID, Device: e.DeviceID}
	}
	return push.Holder{}
}

// Event types of the revocations, as the platform writes them.
const (
	FieldWorkerSuspendedEvent = "platform:iam:field-worker:suspended"
	FieldDeviceRevokedEvent   = "platform:iam:field-device:revoked"
	FieldSessionRevokedEvent  = "platform:iam:field-session:revoked"
)

// fieldEventData is the union of the data members of the three events (the platform's
// fieldidentity/events.go and operations.go); a member an event does not carry stays empty.
type fieldEventData struct {
	WorkerID string `json:"workerId"`
	DeviceID string `json:"deviceId"`
	ClientID string `json:"clientId"`
	FamilyID string `json:"familyId"`
	Reason   string `json:"reason"`
}

// OnFieldRevocation registers a receiver for the platform's three field revocation events
// (contract section 15.7): the handler gets a [FieldRevocation] built from the event data. A nil
// error acknowledges; an error is a transient failure and the platform delivers again. An event
// whose data lacks the identifier it is about is rejected (it can never succeed). Register it in
// the build function, before [Platform.Routes]. The application must also declare a subscription
// to the three event types (Definitions.Subscription), which the sync creates.
func OnFieldRevocation(r *Receivers, handler func(ctx context.Context, cx fw.Context, event FieldRevocation) error) error {
	register := func(eventType string, build func(fieldEventData) (FieldRevocation, bool)) error {
		return OnDispatch(r, eventType, func(ctx context.Context, cx fw.Context, d Delivery[fieldEventData]) (DispatchResult, error) {
			event, ok := build(d.Data)
			if !d.HasData || !ok {
				return Reject{Reason: "the data of " + eventType + " does not say which worker, device or session it is about"}, nil
			}
			if err := handler(ctx, cx, event); err != nil {
				return nil, err
			}
			return Ack{}, nil
		})
	}
	if err := register(FieldWorkerSuspendedEvent, func(d fieldEventData) (FieldRevocation, bool) {
		return WorkerSuspended{WorkerID: d.WorkerID, ClientID: d.ClientID}, d.WorkerID != ""
	}); err != nil {
		return err
	}
	if err := register(FieldDeviceRevokedEvent, func(d fieldEventData) (FieldRevocation, bool) {
		return DeviceRevoked{DeviceID: d.DeviceID, ClientID: d.ClientID}, d.DeviceID != ""
	}); err != nil {
		return err
	}
	return register(FieldSessionRevokedEvent, func(d fieldEventData) (FieldRevocation, bool) {
		return SessionRevoked{SessionID: d.FamilyID, WorkerID: d.WorkerID, DeviceID: d.DeviceID, ClientID: d.ClientID, Reason: d.Reason},
			d.FamilyID != "" || d.WorkerID != "" || d.DeviceID != ""
	})
}
