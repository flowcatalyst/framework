package kit

import (
	"strings"
	"testing"
)

// The permission-denied dialog names no application path: homeUrl is required for the denied case
// and loginUrl for the session case, and the refusal names the rule (permission-denied-dialog.md).
func TestPermissionDeniedDialogRequiresTheURLOfItsCase(t *testing.T) {
	denied := PermissionDeniedDialogProps{ID: "d", Kind: PermissionDenied, Message: "no"}
	session := PermissionDeniedDialogProps{ID: "s", Kind: SessionExpired, Message: "expired"}

	if msg := refused(func() { render(t, PermissionDeniedDialog(denied), nil) }); !strings.Contains(msg, "HomeURL (homeUrl) is required when kind is denied") {
		t.Errorf("denied without homeUrl: %q", msg)
	}
	if msg := refused(func() { render(t, PermissionDeniedDialog(session), nil) }); !strings.Contains(msg, "LoginURL (loginUrl) is required when kind is session") {
		t.Errorf("session without loginUrl: %q", msg)
	}
	// the default kind is denied
	if msg := refused(func() { render(t, PermissionDeniedDialog(PermissionDeniedDialogProps{ID: "d", Message: "no"}), nil) }); !strings.Contains(msg, "HomeURL") {
		t.Errorf("default kind without homeUrl: %q", msg)
	}
	// each case needs only its own URL
	denied.HomeURL = "/home"
	session.LoginURL = "/login"
	if out := render(t, PermissionDeniedDialog(denied), nil); !strings.Contains(out, `href="/home"`) {
		t.Errorf("denied: %s", out)
	}
	if out := render(t, PermissionDeniedDialog(session), nil); !strings.Contains(out, `href="/login"`) {
		t.Errorf("session: %s", out)
	}
}

// The toggle group's kind and size are checked before anything renders, so a bad one is refused for
// a group with no options as well (contract section 3 rule 5).
func TestToggleGroupValidatesKindAndSizeUpFront(t *testing.T) {
	for name, p := range map[string]ToggleGroupProps{
		"kind, no options": {Kind: "both", Name: "n"},
		"size, no options": {Size: "huge", Name: "n"},
		"kind":             {Kind: "both", Name: "n", Options: []Option{{Value: "a", Label: "A"}}},
		"size":             {Size: "huge", Name: "n", Options: []Option{{Value: "a", Label: "A"}}},
	} {
		if msg := refused(func() { render(t, ToggleGroup(p), nil) }); !strings.Contains(msg, "contract section 3 rule 5") {
			t.Errorf("%s: %q", name, msg)
		}
	}
	if out := render(t, ToggleGroup(ToggleGroupProps{Name: "n"}), nil); !strings.Contains(out, "inline-flex") {
		t.Errorf("an empty valid group renders: %s", out)
	}
}
