package ticketsweb

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json/v2"
	"fmt"
	"net/http"
	"regexp"
	"strings"
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

// ---- what the platform sends to the receivers ------------------------------------------------------

// Sign is the signature of a delivery: hex(HMAC-SHA256(secret, timestamp + body)) (flowcatalyst
// contract section 4). The Java side publishes this as ReceiverTesting; the Go module has no public
// helper, so a service re-implements these eight lines (friction G76-1).
func Sign(secret, timestamp, body string) string {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte(timestamp))
	m.Write([]byte(body))
	return hex.EncodeToString(m.Sum(nil))
}

// FiringBody is the body of a scheduled-job firing, as the platform posts it.
func FiringBody(now time.Time, jobCode, instanceID string) string {
	ts := now.UTC().Format(time.RFC3339Nano)
	return `{"jobId":"sj_1","jobCode":"` + jobCode + `","instanceId":"` + instanceID + `","scheduledFor":"` + ts +
		`","firedAt":"` + ts + `","triggerKind":"CRON","correlationId":"corr-` + instanceID + `","tracksCompletion":true,"concurrent":false}`
}

// Delivery is a signed POST to a receiver of the application.
type Delivery struct {
	Path    string
	Body    string
	Headers map[string]string
}

// Request builds the signed request for a delivery at a time with the signing secret.
func (d Delivery) Request(ctx context.Context, baseURL, secret string, now time.Time) (*http.Request, error) {
	ts := fmt.Sprint(now.Unix())
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, strings.TrimSuffix(baseURL, "/")+d.Path, strings.NewReader(d.Body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-FlowCatalyst-Signature", Sign(secret, ts, d.Body))
	req.Header.Set("X-FlowCatalyst-Timestamp", ts)
	for k, v := range d.Headers {
		req.Header.Set(k, v)
	}
	return req, nil
}
