package views

import (
	"context"
	"io"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/ds"
	"github.com/flowcatalyst/framework/go/ui/look"
)

// PageID is the id of the element a page's content is wrapped in; a Datastar page answer morphs it.
const PageID = "fw-page"

// FlashStackID is the id of the document's flash stack, which [Patches.Flash] appends to.
const FlashStackID = "flash-stack"

// Patches are the patches of one Datastar answer (contract section 6 rule 2): each method sends one
// datastar-patch-elements event (Signals: datastar-patch-signals), rendering the component to a
// buffer first so a failing render sends nothing. A handled Datastar request answers 200 with at
// least one patch; an expected failure patches the error where it belongs or flashes it.
type Patches struct {
	sse  *ds.SSE
	look look.Look
}

// wrapPage wraps content in <div id="fw-page">.
func wrapPage(content templ.Component) templ.Component {
	return templ.ComponentFunc(func(ctx context.Context, w io.Writer) error {
		if _, err := io.WriteString(w, `<div id="`+PageID+`">`); err != nil {
			return err
		}
		if err := content.Render(ctx, w); err != nil {
			return err
		}
		_, err := io.WriteString(w, `</div>`)
		return err
	})
}

// Outer replaces the element(s) with the same id as the root(s) of the content (morph).
func (p *Patches) Outer(content templ.Component) error { return p.sse.Patch(content) }

// Page replaces the page content (the #fw-page wrapper): what [Views.Page] sends for a Datastar
// request, for a handler that streams more than the page.
func (p *Patches) Page(content templ.Component) error { return p.sse.Patch(wrapPage(content)) }

// Replace replaces the element matching the selector with the content (the element is removed and
// inserted, not morphed). Use it for a modal dialog the answer must close: removing an open modal
// takes it out of the top layer, whereas a morph that drops its open attribute leaves it modal.
func (p *Patches) Replace(selector string, content templ.Component) error {
	return p.sse.Patch(content, ds.Replace(selector))
}

// Inner replaces the inside of the element matching the selector.
func (p *Patches) Inner(selector string, content templ.Component) error {
	return p.sse.Patch(content, ds.Inner(selector))
}

// Append adds the content as the last children of the element matching the selector.
func (p *Patches) Append(selector string, content templ.Component) error {
	return p.sse.Patch(content, ds.Append(selector))
}

// Prepend adds the content as the first children of the element matching the selector.
func (p *Patches) Prepend(selector string, content templ.Component) error {
	return p.sse.Patch(content, ds.Prepend(selector))
}

// Remove removes the element matching the selector.
func (p *Patches) Remove(selector string) error { return p.sse.Remove(selector) }

// Focus moves focus to the element with this id once the patches before it have been applied (a
// closed dialog hands focus back to its trigger). The id is validated (ds.ErrInvalidID).
func (p *Patches) Focus(id string) error { return p.sse.Focus(id) }

// CloseDialog closes the modal dialog with this id. Datastar processes it asynchronously: when the
// answer also re-renders the dialog, use [Patches.Replace] for that instead.
func (p *Patches) CloseDialog(id string) error { return p.sse.CloseDialog(id) }

// Redirect navigates the browser to a local path (ds.ErrUnsafeRedirect otherwise).
func (p *Patches) Redirect(path string) error { return p.sse.Redirect(path) }

// ReplaceURL sets the address bar to a local path without navigating (ds.ErrUnsafeRedirect
// otherwise).
func (p *Patches) ReplaceURL(path string) error { return p.sse.ReplaceURL(path) }

// Flash appends a flash message to the page's flash stack (flash-stack.md). Success and info
// messages remove themselves after 3 and 5 seconds; warn and error stay until dismissed. detail may
// be empty. An unknown kind panics (contract section 3 rule 5).
func (p *Patches) Flash(kind look.FlashKind, summary, detail string) error {
	return p.sse.Patch(p.look.FlashMessage(look.Flash{Kind: kind, Summary: summary, Detail: detail}),
		ds.Append("#"+FlashStackID))
}

// Signals merges the values into the page's signals (a JSON merge patch: a nil member removes that
// signal).
func (p *Patches) Signals(signals map[string]any) error { return p.sse.PatchSignals(signals) }

// SSE is the underlying stream, for what [Patches] does not cover (patch options, signals options).
func (p *Patches) SSE() *ds.SSE { return p.sse }
