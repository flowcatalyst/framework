package kit

import (
	"context"
	"strings"
	"testing"
)

func renderSidebar(t *testing.T, nav []NavGroup) string {
	t.Helper()
	var sb strings.Builder
	if err := AppSidebar(AppShellProps{Nav: nav}).Render(context.Background(), &sb); err != nil {
		t.Fatal(err)
	}
	return sb.String()
}

func TestCollapsibleNavGroups(t *testing.T) {
	nav := []NavGroup{
		{Label: "Identity & Access", Key: "identity-access", Items: []NavItem{{Label: "Roles", Href: "/r"}}},
		{Label: "Messaging", Key: "messaging", Items: []NavItem{{Label: "Pools", Href: "/p", Current: true}}},
		{Label: "Static", Items: []NavItem{{Label: "X", Href: "/x"}}},
	}
	out := renderSidebar(t, nav)

	for _, want := range []string{
		`aria-controls="fc-nav-sec-identity-access"`,
		`id="fc-nav-sec-identity-access"`,
		`navClosedIdentityAccess`,
		`fc:nav-closed`,
		`<span class="fc-nav-group-label">Static</span>`, // un-keyed group stays a plain label
	} {
		if !strings.Contains(out, want) {
			t.Errorf("sidebar lacks %s", want)
		}
	}
	// The group holding the current page: header disabled, never restored closed.
	if !strings.Contains(out, `aria-controls="fc-nav-sec-messaging"`) || !strings.Contains(out, `aria-disabled="true"`) {
		t.Error("current group header should be aria-disabled")
	}
	if strings.Contains(NavInitExpr(nav), "'messaging'") || strings.Contains(NavInitExpr(nav), `"messaging"`) {
		t.Error("init must not restore the current group's closed state")
	}
	if !strings.Contains(NavInitExpr(nav), `"identity-access"`) {
		t.Error("init should restore other groups")
	}
}

func TestNoKeysRendersNoCollapseMachinery(t *testing.T) {
	out := renderSidebar(t, []NavGroup{{Label: "G", Items: []NavItem{{Label: "X", Href: "/x"}}}})
	if strings.Contains(out, "navClosed") || strings.Contains(out, "<button type=\"button\" class=\"fc-nav-group-label") {
		t.Error("un-keyed nav must render the plain static markup")
	}
}

func TestNavSignal(t *testing.T) {
	if got := NavSignal("identity-access"); got != "navClosedIdentityAccess" {
		t.Errorf("got %s", got)
	}
}
