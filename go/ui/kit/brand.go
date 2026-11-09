package kit

import (
	"encoding/base64"
	"strconv"

	"github.com/a-h/templ"
)

// Brand is the app shell's logo (app-shell.md, Brand): an image in place of
// the FlowCatalyst bolt, and the logo's height in the sidebar header. The zero
// value is the bolt at 32px, exactly as the shell renders without a brand.
type Brand struct {
	// Name is the logo's accessible name: the image's alt text, or the bolt's
	// aria-label. Empty: "Logo" for an image, "FlowCatalyst" for the bolt.
	Name string `json:"name"`
	// LogoURL is an image shown with <img src>. It passes the URL check
	// (contract section 3 rule 3): http(s) or a path; data: is refused.
	LogoURL string `json:"logoUrl"`
	// LogoSVG is SVG markup shown as an image, used when LogoURL is empty. The
	// kit writes it as a data:image/svg+xml;base64 URL of an <img>, never as
	// inline markup: an SVG in an <img> runs no script and loads nothing.
	LogoSVG string `json:"logoSvg"`
	// LogoHeight is the logo's height in the sidebar header in CSS pixels; 0
	// (or less) is 32. A URL image is at most that high (it is not scaled up);
	// an SVG image and the bolt are exactly that high.
	LogoHeight int `json:"logoHeight"`
}

const defaultLogoHeight = 32

// height is the logo height in px.
func (b Brand) height() string {
	if b.LogoHeight > 0 {
		return strconv.Itoa(b.LogoHeight)
	}
	return strconv.Itoa(defaultLogoHeight)
}

// hasImage reports whether the brand replaces the bolt with an image.
func (b Brand) hasImage() bool { return b.LogoURL != "" || b.LogoSVG != "" }

// imageSrc is the <img src>: the checked URL, else the SVG as a base64 data
// URL. The data URL is built by the kit from the markup (so the URL check,
// which refuses data:, does not apply to it), with the image/svg+xml type.
func (b Brand) imageSrc() string {
	if b.LogoURL != "" {
		return URL(b.LogoURL)
	}
	return "data:image/svg+xml;base64," + base64.StdEncoding.EncodeToString([]byte(b.LogoSVG))
}

// imageAlt is the image's alt text.
func (b Brand) imageAlt() string { return orDefault(b.Name, "Logo") }

// boltLabel is the bolt's aria-label.
func (b Brand) boltLabel() string { return orDefault(b.Name, "FlowCatalyst") }

// sidebarImageStyle caps a URL image at the logo height without scaling it up;
// an SVG image fills the header link's height (kit.css), so it has no style.
func (b Brand) sidebarImageStyle() string {
	if b.LogoURL != "" {
		return "height: auto; max-height: " + b.height() + "px"
	}
	return ""
}

// style is a style attribute written as given (templ's style={...} would
// append a ';'), none when css is empty. Its values are the kit's own: CSS
// lengths built from an int.
func (Brand) style(css string) templ.Attributes {
	if css == "" {
		return nil
	}
	return templ.Attributes{"style": css}
}

// topbarClass is the top bar logo link's class: an image is its own width.
func (b Brand) topbarClass() string {
	if b.hasImage() {
		return "fc-topbar-logo fc-topbar-logo-image"
	}
	return "fc-topbar-logo"
}
