// Package look is what the page layer ([github.com/flowcatalyst/framework/go/ui/views]) and a component
// kit share: the flash message type and the [Look] interface the views render documents through. The
// module's own kit implements it (kit.Look), and an application that keeps a copy of the kit under its
// own import path implements it again, so that both kits agree on these types without the copy having
// to import the module's kit.
//
// The package imports only the standard library, templ and the assets package, so that nothing in it
// can pull a kit in.
package look

import (
	"context"
	"net/http"

	"github.com/a-h/templ"

	"github.com/flowcatalyst/framework/go/ui/assets"
)

// FlashKind is the kind of a flash message (flash-stack.md's flash message, all four).
type FlashKind string

// Flash kinds.
const (
	FlashSuccess FlashKind = "success"
	FlashInfo    FlashKind = "info"
	FlashWarn    FlashKind = "warn"
	FlashError   FlashKind = "error"
)

// Flash is one flash message; it mirrors flash-stack.md's flash-message Props table. The json tags are
// the prop names, so the language-neutral fixtures decode straight into it.
type Flash struct {
	Kind    FlashKind `json:"kind"`
	Summary string    `json:"summary"`
	Detail  string    `json:"detail"`
	// Persistent keeps a success or info message (warn and error always stay).
	Persistent bool             `json:"persistent"`
	Attrs      templ.Attributes `json:"attrs"`
}

// Look is the component kit that documents are rendered with. The views keep the document skeleton,
// the policy and its headers, the flash cookie and the patches; a Look supplies the six things below.
type Look interface {
	// Stylesheet is the asset name of the stylesheet every document links first.
	Stylesheet() string
	// Context adds what the kit's components read from the render context (the module's kit: the
	// icon sprite's URL).
	Context(ctx context.Context, a *assets.Assets) context.Context
	// HTMLAttrs are the attributes of <html> for the request: the theme's and, when the document
	// has a shell, the shell's.
	HTMLAttrs(r *http.Request, shell bool) templ.Attributes
	// Main wraps a document that has no shell; its children are the flash stack and the page.
	Main() templ.Component
	// FlashStack is the element with this id that flash messages are appended to; its children are
	// the messages that came with the page.
	FlashStack(id string) templ.Component
	// FlashMessage renders one message.
	FlashMessage(m Flash) templ.Component
}
