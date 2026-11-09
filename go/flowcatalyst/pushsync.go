package flowcatalyst

import (
	"context"
	"encoding/json/v2"
	"fmt"
)

// pushSyncResponse is the gateway's answer to the push sync (the counts and what it stored).
type pushSyncResponse struct {
	Rules          pushSyncCounts `json:"rules"`
	Keys           pushSyncCounts `json:"keys"`
	SyncedPatterns []string       `json:"syncedPatterns"`
}

type pushSyncCounts struct {
	Created int `json:"created"`
	Updated int `json:"updated"`
	Deleted int `json:"deleted"`
}

// syncPush sends the push module's rules and the JWKS of its ticket keys to the platform's gateway
// (POST /api/applications/{app}/push/sync, a declarative full replace). A dry run reports the
// patterns it would send and makes no request.
func (p *Platform) syncPush(ctx context.Context, mode SyncMode) CategoryResult {
	body := p.pushes.GatewaySync()
	patterns := make([]string, len(body.Rules))
	for i, r := range body.Rules {
		patterns[i] = r.Pattern
	}
	res := CategoryResult{Category: CategoryPush, Sent: len(patterns), Codes: patterns}
	if mode == SyncDryRun {
		res.Outcome = OutcomeWouldSync
		return res
	}
	answer, err := p.client.http.call(ctx, "POST", "/api/applications/"+pathSegment(p.client.app.String())+"/push/sync", nil, body)
	if err != nil {
		res.Outcome, res.Message = OutcomeFailed, err.Error()
		return res
	}
	var out pushSyncResponse
	if err := json.Unmarshal(answer, &out); err != nil {
		res.Outcome, res.Message = OutcomeFailed, fmt.Sprintf("the gateway's answer to the push sync cannot be read: %v", err)
		return res
	}
	res.Outcome = OutcomeSynced
	res.Created, res.Updated, res.Deleted = out.Rules.Created, out.Rules.Updated, out.Rules.Deleted
	if len(out.SyncedPatterns) > 0 {
		res.Codes = out.SyncedPatterns
	}
	return res
}
