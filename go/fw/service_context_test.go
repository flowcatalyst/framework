package fw_test

import (
	"testing"

	"github.com/flowcatalyst/framework/go/fw"
)

// The context of work that no platform principal asked for (a provider's webhook): a service
// principal for a client (fulfil-go port, item 18).
func TestServiceContextForIsAServicePrincipalOfTheClient(t *testing.T) {
	t.Parallel()
	cx := fw.ServiceContextFor(fw.MustClientID("acme"), fw.MustPrincipalID("svc_uber"))
	if cx.Client() != fw.MustClientID("acme") || cx.Correlation().IsZero() {
		t.Errorf("client %v, correlation %v", cx.Client(), cx.Correlation())
	}
	if p, ok := cx.Principal(); !ok || p.String() != "svc_uber" {
		t.Errorf("principal %v %v", p, ok)
	}
	if k, ok := cx.PrincipalKind(); !ok || k != fw.PrincipalService {
		t.Errorf("kind %v %v, want SERVICE", k, ok)
	}
	if len(cx.Permissions()) != 0 {
		t.Errorf("permissions %v", cx.Permissions())
	}
	if other := fw.ServiceContextFor(fw.MustClientID("acme"), fw.MustPrincipalID("svc_uber")); other.Correlation() == cx.Correlation() {
		t.Error("two contexts share a correlation id")
	}
}

func TestServiceContextForPanicsOnZeroValues(t *testing.T) {
	t.Parallel()
	for name, f := range map[string]func(){
		"zero client":    func() { fw.ServiceContextFor(fw.ClientID{}, fw.MustPrincipalID("svc")) },
		"zero principal": func() { fw.ServiceContextFor(fw.MustClientID("acme"), fw.PrincipalID{}) },
	} {
		func() {
			defer func() {
				if recover() == nil {
					t.Errorf("%s: no panic", name)
				}
			}()
			f()
		}()
	}
}

// The subject's display name (fulfil-go port, item 20): set by the auth layer, kept by ForClient,
// dropped with the principal it describes.
func TestTheSubjectNameDescribesThePrincipal(t *testing.T) {
	t.Parallel()
	base := fw.ContextFor(fw.MustClientID("acme")).WithPrincipalOfKind(fw.MustPrincipalID("fwu_7"), fw.PrincipalFieldWorker)
	if _, ok := base.SubjectName(); ok {
		t.Error("a new Context has a subject name")
	}
	named := base.WithSubjectName("Dee Driver").WithActor(fw.Actor{ID: "prn_sup", Type: "principal", Name: "Sue Pervisor"})
	if n, ok := named.SubjectName(); !ok || n != "Dee Driver" {
		t.Errorf("subject name %q %v", n, ok)
	}
	if a, _ := named.Actor(); a.Name != "Sue Pervisor" {
		t.Errorf("the actor is still the operator: %+v", a)
	}
	if n, ok := named.ForClient(fw.MustClientID("globex")).SubjectName(); !ok || n != "Dee Driver" {
		t.Errorf("ForClient dropped the subject name: %q %v", n, ok)
	}
	if _, ok := named.WithPrincipal(fw.MustPrincipalID("prn_other")).SubjectName(); ok {
		t.Error("a new principal kept the old principal's name")
	}
	if _, ok := named.WithPrincipalOfKind(fw.MustPrincipalID("prn_other"), fw.PrincipalUser).SubjectName(); ok {
		t.Error("a new principal of a kind kept the old principal's name")
	}
	func() {
		defer func() {
			if recover() == nil {
				t.Error("a blank subject name was accepted")
			}
		}()
		base.WithSubjectName("  ")
	}()
}
