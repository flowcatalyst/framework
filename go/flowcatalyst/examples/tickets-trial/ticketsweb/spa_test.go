package ticketsweb

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The jar serves its build under the strict policy; an unknown API path stays a JSON 404 (the Java
// TicketsSpaTest, on the embedded placeholder build: the browser suite serves the real one).

func TestTheBuildIsServedWithTheContractsHeaders(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	index, err := os.ReadFile(filepath.Join("..", "web", "dist", "index.html"))
	if err != nil {
		t.Fatal(err)
	}
	home := s.call("GET", "/", "")
	if home.status != 200 || home.body != string(index) || home.header.Get("Cache-Control") != "no-cache" {
		t.Errorf("/: %d cache=%q", home.status, home.header.Get("Cache-Control"))
	}
	if csp := home.header.Get("Content-Security-Policy"); !strings.HasPrefix(csp, "default-src 'self'; script-src 'self'") {
		t.Errorf("the strict policy is the default: %q", csp)
	}
	if home.header.Get("X-Content-Type-Options") != "nosniff" {
		t.Errorf("headers: %v", home.header)
	}
	if a := s.call("GET", "/assets/gone-abc.js", ""); a.status != 404 || !strings.Contains(a.body, "NOT_FOUND") {
		t.Errorf("a missing asset: %d %s", a.status, a.body)
	}
}

func TestAClientRouteOfTheAppIsTheShellAndTheAPIKeepsItsJSON404(t *testing.T) {
	t.Parallel()
	s := startWeb(t, siteOptions{})
	index, err := os.ReadFile(filepath.Join("..", "web", "dist", "index.html"))
	if err != nil {
		t.Fatal(err)
	}
	if a := s.call("GET", "/tickets/anything-the-spa-routes", ""); a.status != 200 || a.body != string(index) {
		t.Errorf("a client route: %d", a.status)
	}
	if a := s.call("GET", "/api/nothing-here", ""); a.status != 404 || a.header.Get("Content-Type") != "application/json" || a.header.Get("Cache-Control") != "no-store" {
		t.Errorf("an unknown API path: %d %v", a.status, a.header)
	}
}
