package usecase_test

import (
	"strings"
	"testing"
	"time"

	"github.com/flowcatalyst/framework/go/event"
	"github.com/flowcatalyst/framework/go/fw"
	"github.com/flowcatalyst/framework/go/usecase"
)

type opEvent struct {
	meta event.Metadata
	N    int `json:"n"`
}

func (e opEvent) Metadata() event.Metadata { return e.meta }

// An impersonated field request records the worker as the principal and the actor as the operator:
// in the event's contextData and in the audit row's operationData.
func TestOperatorIsRecordedBesideTheSubject(t *testing.T) {
	t.Parallel()
	typ := event.MustType("orders:gate:visit:opened")
	base := fw.ContextFor(fw.MustClientID("acme")).WithPrincipalOfKind(fw.MustPrincipalID("fwu_7"), fw.PrincipalFieldWorker)
	plain := event.New(base, event.Fields{EventID: "e1", Type: typ, SubjectID: "1", Time: time.Unix(0, 0)})
	acting := event.New(base.WithActor(fw.Actor{ID: "prn_ADMIN", Type: "principal", Name: "Admin"}),
		event.Fields{EventID: "e1", Type: typ, SubjectID: "1", Time: time.Unix(0, 0)})

	payload := func(m event.Metadata, cx fw.Context) string {
		s, err := usecase.EventPayload(cx, opEvent{meta: m})
		if err != nil {
			t.Fatal(err)
		}
		return s
	}
	if got := payload(plain, base); strings.Contains(got, "operator") {
		t.Fatalf("no actor, no operator: %s", got)
	}
	got := payload(acting, base)
	if !strings.Contains(got, `fwu_7`) || !strings.Contains(got, "prn_ADMIN") || !strings.Contains(got, "operatorType") {
		t.Fatalf("event payload %s", got)
	}

	entry := usecase.AuditEntry{EntityType: "visit", EntityID: "1", Operation: "Open", PrincipalID: "fwu_7", Client: fw.MustClientID("acme"), PerformedAt: time.Unix(0, 0)}
	if s, err := usecase.AuditPayload(entry); err != nil || strings.Contains(s, "operator") {
		t.Fatalf("no operator: %s %v", s, err)
	}
	entry.OperatorID, entry.OperatorType = "prn_ADMIN", "principal"
	s, err := usecase.AuditPayload(entry)
	if err != nil || !strings.Contains(s, `\"operatorId\":\"prn_ADMIN\"`) || !strings.Contains(s, `"principalId":"fwu_7"`) {
		t.Fatalf("audit payload %s %v", s, err)
	}
}
