package usecase_test

import (
	"encoding/json/v2"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

// The payloads' clientCode (write-path contract section 7, amended 2026-11-18): an event and an
// audit row of a client's context name that client, as a dispatch job and a push do; of the
// platform's own context (fw.PlatformClient) they name none, because "_platform" is no platform
// client's identifier and the platform could not resolve it (it drops an audit row it cannot
// resolve and refuses an event's batch from a credential that is not an anchor).

func payloadMembers(t *testing.T, payload string) map[string]any {
	t.Helper()
	var m map[string]any
	if err := json.Unmarshal([]byte(payload), &m); err != nil {
		t.Fatal(err)
	}
	return m
}

func eventPayloadFor(t *testing.T, client fw.ClientID) map[string]any {
	t.Helper()
	cx := fw.ContextFor(client).WithPrincipal(fw.MustPrincipalID("svc-1"))
	m := event.New(cx, event.Fields{EventID: "e1", Type: event.MustType("shop:billing:invoice:cancelled"), SubjectID: "inv_1", Time: time.Unix(0, 0)})
	s, err := usecase.EventPayload(cx, opEvent{meta: m})
	if err != nil {
		t.Fatal(err)
	}
	return payloadMembers(t, s)
}

func auditPayloadFor(t *testing.T, client fw.ClientID) map[string]any {
	t.Helper()
	s, err := usecase.AuditPayload(usecase.AuditEntry{
		EntityType: "Invoice", EntityID: "inv_1", Operation: "sweep-invoices", PrincipalID: "svc-1",
		Source: "shop:billing", CorrelationID: "corr-1", Client: client, PerformedAt: time.Unix(0, 0),
	})
	if err != nil {
		t.Fatal(err)
	}
	return payloadMembers(t, s)
}

func TestAnEventAndAuditRowOfAClientsContextNameThatClient(t *testing.T) {
	t.Parallel()
	acme := fw.MustClientID("acme")
	if p := eventPayloadFor(t, acme); p["clientCode"] != "acme" {
		t.Errorf("event clientCode %v, want acme: %v", p["clientCode"], p)
	}
	if p := auditPayloadFor(t, acme); p["clientCode"] != "acme" {
		t.Errorf("audit clientCode %v, want acme: %v", p["clientCode"], p)
	}
}

func TestAnEventAndAuditRowOfThePlatformsOwnContextNameNoClient(t *testing.T) {
	t.Parallel()
	if p := eventPayloadFor(t, fw.PlatformClient); p["clientCode"] != nil {
		t.Errorf("a platform-scoped event named a client: %v", p)
	}
	if p := auditPayloadFor(t, fw.PlatformClient); p["clientCode"] != nil {
		t.Errorf("a platform-scoped audit row named a client: %v", p)
	}
}
