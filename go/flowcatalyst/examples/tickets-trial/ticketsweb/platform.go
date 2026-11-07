package ticketsweb

import (
	"encoding/json/v2"
	"fmt"
	"net/http"
	"regexp"
	"time"

	"github.com/flowcatalyst/framework/go/flowcatalyst/fakeplatform"
)

// The stand-in for the platform that the tests and the browser launcher point the application at (the
// Java FakePlatform with the calls this service makes): the token endpoint, the application sync
// endpoints and the scheduled-job instance callbacks. Nothing here reaches a real platform.

var (
	syncPath     = regexp.MustCompile(`^/api/applications/([^/]+)/([a-z-]+)/sync$`)
	instancePath = regexp.MustCompile(`^/api/scheduled-jobs/instances/([^/]+)/(complete|log)$`)
)

// Platform is the fake platform with the sync and instance routes of this service.
type Platform struct {
	*fakeplatform.Platform
}

// StartPlatform starts the fake.
func StartPlatform() *Platform {
	p := &Platform{Platform: fakeplatform.Start()}
	p.RouteMatching(http.MethodPost, `/api/applications/[^/]+/[a-z-]+/sync`, func(r fakeplatform.Recorded) fakeplatform.Reply {
		m := syncPath.FindStringSubmatch(r.Path)
		codes := []string{}
		for _, list := range r.JSON() {
			items, isList := list.([]any)
			if !isList {
				continue
			}
			for _, it := range items {
				obj, isObj := it.(map[string]any)
				if !isObj {
					continue
				}
				for _, key := range []string{"code", "name", "slug"} {
					if s, isString := obj[key].(string); isString {
						codes = append(codes, s)
						break
					}
				}
			}
		}
		var body any = map[string]any{"applicationCode": m[1], "created": len(codes), "updated": 0, "deleted": 0, "syncedCodes": codes}
		if m[2] == "scheduled-jobs" {
			body = map[string]any{"applicationCode": m[1], "created": codes, "updated": []string{}, "archived": []string{}}
		}
		b, _ := json.Marshal(body)
		return fakeplatform.Reply{Status: http.StatusOK, Body: string(b)}
	})
	p.RouteMatching(http.MethodPost, `/api/scheduled-jobs/instances/[^/]+/(complete|log)`, func(fakeplatform.Recorded) fakeplatform.Reply {
		return fakeplatform.Reply{Status: http.StatusNoContent}
	})
	return p
}

// SyncRequests are the sync calls received, oldest first.
func (p *Platform) SyncRequests() []fakeplatform.Recorded {
	var out []fakeplatform.Recorded
	for _, r := range p.Requests() {
		if syncPath.MatchString(r.Path) {
			out = append(out, r)
		}
	}
	return out
}

// SyncOf is the sync call of a resource (roles, event-types, ...).
func (p *Platform) SyncOf(resource string) (fakeplatform.Recorded, bool) {
	for _, r := range p.SyncRequests() {
		if syncPath.FindStringSubmatch(r.Path)[2] == resource {
			return r, true
		}
	}
	return fakeplatform.Recorded{}, false
}

// SyncedResources are the resources synced, in the order they were sent.
func (p *Platform) SyncedResources() []string {
	var out []string
	for _, r := range p.SyncRequests() {
		out = append(out, syncPath.FindStringSubmatch(r.Path)[2])
	}
	return out
}

// InstanceCalls are the scheduled-job instance callbacks of a kind ("complete" or "log").
func (p *Platform) InstanceCalls(kind string) []fakeplatform.Recorded {
	var out []fakeplatform.Recorded
	for _, r := range p.Requests() {
		if m := instancePath.FindStringSubmatch(r.Path); m != nil && m[2] == kind {
			out = append(out, r)
		}
	}
	return out
}

// AwaitInstanceCalls waits until at least n callbacks of a kind were received.
func (p *Platform) AwaitInstanceCalls(kind string, n int, timeout time.Duration) ([]fakeplatform.Recorded, error) {
	expired := time.After(timeout)
	tick := time.NewTicker(10 * time.Millisecond)
	defer tick.Stop()
	for {
		if calls := p.InstanceCalls(kind); len(calls) >= n {
			return calls, nil
		}
		select {
		case <-expired:
			return nil, fmt.Errorf("fewer than %d %q calls within %v", n, kind, timeout)
		case <-tick.C:
		}
	}
}
