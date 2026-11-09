package kit

import (
	"encoding/base64"
	"strings"
	"testing"

	"golang.org/x/net/html"
)

// The shell's brand (app-shell.md, Brand). The fixtures under
// ui-kit/fixtures/app-sidebar/brand-* and app-shell/brand hold the markup;
// these tests hold the rules a fixture cannot show.

// Without a brand the logo link is exactly the one the shell always rendered.
func TestBrandAbsentKeepsTheBoltMarkup(t *testing.T) {
	out := render(t, AppSidebar(AppShellProps{LogoHref: "/"}), nil)
	want := `<a href="/" class="fc-sidebar-logo" style="height: 32px"><span style="width: 32px; height: 32px"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="100%" height="100%" aria-label="FlowCatalyst">`
	if !strings.Contains(out, want) {
		t.Errorf("sidebar logo changed without a brand:\n%s", out)
	}
	shell := render(t, AppShell(AppShellProps{LogoHref: "/"}), nil)
	if !strings.Contains(shell, `<a href="/" class="fc-topbar-logo"><svg viewBox="0 0 24 24" fill="none" stroke="currentColor" width="100%" height="100%" aria-label="FlowCatalyst">`) {
		t.Errorf("top bar logo changed without a brand:\n%s", shell)
	}
}

// An SVG logo is an <img> with a data URL of exactly that markup: whatever it
// contains is never parsed into the page.
func TestBrandSVGIsAnImageNeverInlineMarkup(t *testing.T) {
	svg := `<svg xmlns="http://www.w3.org/2000/svg" onload="alert(1)"><script>alert(2)</script><foreignObject><img src=x onerror=alert(3)></foreignObject></svg>`
	out := render(t, AppShell(AppShellProps{LogoHref: "/", Brand: Brand{Name: xss, LogoSVG: svg}}), nil)
	if n := dangerOf(out, t, false); n != 0 {
		t.Errorf("%d live script/on* in:\n%s", n, out)
	}
	if strings.Contains(out, "onload") || strings.Contains(out, "foreignObject") || strings.Contains(out, "alert(3)") {
		t.Errorf("the SVG's content reached the page:\n%s", out)
	}
	imgs := find(parse(t, out), "img")
	if len(imgs) != 2 {
		t.Fatalf("want the logo image in the sidebar and the top bar, got %d", len(imgs))
	}
	for _, img := range imgs {
		src, ok := strings.CutPrefix(attr(img, "src"), "data:image/svg+xml;base64,")
		if !ok {
			t.Fatalf("src = %q, want a base64 SVG data URL", attr(img, "src"))
		}
		raw, err := base64.StdEncoding.DecodeString(src)
		if err != nil || string(raw) != svg {
			t.Errorf("data URL does not hold the SVG: %q, %v", raw, err)
		}
		if attr(img, "alt") != xss {
			t.Errorf("alt = %q, want the name as text", attr(img, "alt"))
		}
	}
}

// A URL logo is an image in the sidebar (capped at the logo height) and in the
// top bar, named by the brand.
func TestBrandImageFromURL(t *testing.T) {
	props := AppShellProps{
		LogoHref: "/ui/",
		Brand:    Brand{Name: "Acme", LogoURL: "https://cdn.example.com/acme.png", LogoHeight: 40},
	}
	imgs := find(parse(t, render(t, AppShell(props), nil)), "img")
	if len(imgs) != 2 {
		t.Fatalf("want the logo in the sidebar and the top bar, got %d images", len(imgs))
	}
	for _, img := range imgs {
		if attr(img, "src") != "https://cdn.example.com/acme.png" || attr(img, "alt") != "Acme" {
			t.Errorf("img src=%q alt=%q", attr(img, "src"), attr(img, "alt"))
		}
	}
	if got := attr(imgs[0], "style"); got != "height: auto; max-height: 40px" {
		t.Errorf("sidebar image style = %q", got)
	}
}

// The logo height sets the header link and the bolt; zero or less is 32.
func TestBrandLogoHeight(t *testing.T) {
	for _, c := range []struct {
		height int
		want   string
	}{{0, "32px"}, {-5, "32px"}, {56, "56px"}} {
		doc := parse(t, render(t, AppSidebar(AppShellProps{Brand: Brand{LogoHeight: c.height}}), nil))
		link := find(doc, "a")[0]
		if got := attr(link, "style"); got != "height: "+c.want {
			t.Errorf("height %d: link style = %q", c.height, got)
		}
		span := firstChildElement(t, link)
		if got := attr(span, "style"); got != "width: "+c.want+"; height: "+c.want {
			t.Errorf("height %d: bolt style = %q", c.height, got)
		}
	}
	// An SVG image fills the link (kit.css); a URL image is capped, not scaled up.
	svgImg := find(parse(t, render(t, AppSidebar(AppShellProps{Brand: Brand{LogoSVG: "<svg/>", LogoHeight: 50}}), nil)), "img")[0]
	if _, has := attrOK(svgImg, "style"); has {
		t.Errorf("SVG image has a style: %q", attr(svgImg, "style"))
	}
	urlImg := find(parse(t, render(t, AppSidebar(AppShellProps{Brand: Brand{LogoURL: "/logo.png", LogoHeight: 50}}), nil)), "img")[0]
	if got := attr(urlImg, "style"); got != "height: auto; max-height: 50px" {
		t.Errorf("URL image style = %q", got)
	}
}

// A logo URL goes through the URL check; a data: URL is refused there (only
// the kit builds data URLs, from LogoSVG).
func TestBrandLogoURLIsChecked(t *testing.T) {
	for _, u := range []string{"javascript:alert(1)", "data:image/svg+xml,<svg onload=alert(1)>", " java\tscript:1"} {
		for _, img := range find(parse(t, render(t, AppShell(AppShellProps{Brand: Brand{LogoURL: u}}), nil)), "img") {
			if got := attr(img, "src"); got != BlockedURL {
				t.Errorf("%q: src = %q", u, got)
			}
		}
	}
}

func firstChildElement(t *testing.T, n *html.Node) *html.Node {
	t.Helper()
	for c := n.FirstChild; c != nil; c = c.NextSibling {
		if c.Type == html.ElementNode {
			return c
		}
	}
	t.Fatalf("<%s> has no child element", n.Data)
	return n
}

func attrOK(n *html.Node, key string) (string, bool) {
	for _, a := range n.Attr {
		if a.Key == key {
			return a.Val, true
		}
	}
	return "", false
}
