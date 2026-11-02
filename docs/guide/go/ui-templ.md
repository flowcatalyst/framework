<!-- types: Assets, Option, SSE, SignalsError, Mode, Namespace, PatchOption, SignalsOption, EventOption, Component, ButtonProps, AlertProps, DialogProps, FormDialogProps, PickListProps, AppShellProps, PermissionDeniedDialogProps -->
# Go: server-rendered pages with templ and Datastar (optional)

> **Optional, and secondary to the Nuxt UI package.** The framework's primary UI is the shared
> `@flowcatalyst/ui` package ([Go: HTTP and the SPA](http.md)). Module `go/ui` is for an application that
> wants server-rendered pages without a JavaScript build; the Java counterpart is
> [Server-rendered pages (JTE)](../ui-jte.md). Nothing else in the framework depends on it. The
> specification is [`contracts/server-rendered-ui.md`](../../contracts/server-rendered-ui.md); the build plan
> is [`go/ui-templ-plan.md`](../../go/ui-templ-plan.md). **Phase GU1 is built** (the module, the kit, `ds`,
> `assets`, `md`, `compress`, `htmlnorm`); the pages, the answer helpers, form routes and the framework
> wiring (`views`, `fwui`) are GU2 and do not exist yet.

## The module

`github.com/flowcatalyst/framework/go/ui` is its own Go module (its own `go.mod`, Go 1.27) so that templ,
goldmark, bluemonday and brotli stay out of the core module. It is built from the FlowCatalyst platform's
templ port, with the platform's defaults taken out. Every package except the kit is plain `net/http` and
imports neither the framework's lifecycle nor its router, so an application that is not built on the framework
can use them.

| Package | What it is |
|---|---|
| `kit` | One templ component per spec in `ui-kit/components`, `Props` structs that mirror the specs' tables, and the hardening below |
| `ds` | The Datastar 1.0 adapter on `net/http`, written to [http contract section 10](../../contracts/http.md) |
| `assets` | The static files (kit stylesheet, Datastar, icon sprite, the islands) as an instance with hashed URLs, ETags and 304 |
| `md` | Markdown to sanitised HTML (goldmark with GFM, bluemonday) |
| `compress` | Opt-in Brotli/gzip for pages and Datastar streams that flushes each event |
| `htmlnorm` | The HTML normaliser the fixture comparison uses |

## The kit

Each component is a templ function taking its props: `kit.Button(kit.ButtonProps{...})`. A field is the spec's
prop name with a capital first letter, and its `json` tag is the prop name, so the language-neutral fixtures
decode straight into it. The default slot is the templ children, a named slot is a `templ.Component` field.
Class strings are the spec's; a `Class` prop is appended after them.

The module's own templates show the call shape (an excerpt of `FormDialog` in `kit/form.templ`; the dialog's body
is the children of the `@Dialog(...)` call):

```templ
@Dialog(DialogProps{ID: p.ID, Title: p.Title, Description: p.Description, Width: p.Width, Persistent: true, Attrs: p.dialogAttrs(), Footer: formDialogFooter(p)}) {
	<form
		id={ FormDialogFormID(p.ID) }
		method="post"
		action={ SafeURL(p.Action) }
		...
	>
```

### What the kit refuses

Contract section 3, enforced where the kit renders. A refusal is a panic whose message names the rule, so a
request that reaches a bad value fails with 500 before anything is written.

- **Extra attributes** (`Attrs`, `LinkAttrs`, `FormAttrs`, ... on every component that takes them) go through
  `kit.Attrs`: a name outside `[A-Za-z_][A-Za-z0-9_.:-]*` or starting with `on` (any case) panics, `nil` and
  `false` are skipped, `true` is a bare attribute, numbers are written as decimal text, and the values of
  `href`, `action`, `formaction`, `src` and `xlink:href` go through the URL check.
- **URLs.** `kit.URL` keeps a URL with no scheme or with `http`, `https`, `mailto` or `tel` (any case), ignoring
  characters up to `0x20` and `0x7f` when it finds the scheme, and turns anything else (`javascript:`, `data:`,
  `vbscript:`, a scheme hidden by whitespace) into `about:invalid#blocked`. Every `href`, `action` and `src` a
  component renders from a prop uses `kit.SafeURL`, which is the same check as a `templ.SafeURL`, so templ's own
  check (which also admits `ftp`) does not run on top.
- **Ids, signals and paths** that enter a Datastar expression are code: `kit.ID`, `kit.Signal`, `kit.Path` and
  `kit.SignalAttr` validate them and panic otherwise. User data goes into plain attributes (`data-id`).
- **Unknown variants and sizes** panic (`kit: unknown button variant "huge"`), they are never replaced by a
  default.
- **Raw markup** has one door, `kit.Raw` (fixture slots, sanitised Markdown, a slot the kit rendered itself).
- **No application paths.** The kit names none. `PermissionDeniedDialogProps` needs `HomeURL` (denied case) or
  `LoginURL` (session case) and panics without it; the app shell takes every link from its props.

Test coverage: `kit/escaping_test.go` (the cases of Java's `EscapingTest`, one test per rule) and
`kit/xss_test.go` (the sweep: every string prop of every fixture replaced by a payload, one at a time).

## Conformance with the fixtures

`kit/conformance_test.go` renders every case of `ui-kit/fixtures` and compares it with the golden HTML after
the normalisation of `ui-kit/fixtures/README.md`. The fixtures are read in place (`go/ui` is two directories
below the repository root; `UI_KIT_FIXTURES=/path` overrides). It checks both directions (a fixture
directory without a renderer fails, a renderer without a fixture directory fails), decodes props strictly (an
unknown property fails the case) and fails when a case fills a slot the component does not have. At GU1 all 66
fixture directories (189 cases) pass.

## Datastar: package `ds`

A Datastar action sends `Datastar-Request: true` and the page answers `text/event-stream`. The adapter has no
dependency on the Datastar SDK; the bytes are those of http contract section 10, and `ds_test.go` holds the
expected strings of the Java adapter's tests.

```go
if ds.IsDatastar(r) {
	sse := ds.NewSSE(w, r)
	err := errors.Join(
		sse.Patch(PoolRows(view)), // morphs the element with the same id
		sse.Patch(PoolCount(view), ds.Inner("#pools-count")),
	)
	...
	return
}
```

- `NewSSE(w, r)` sets `Content-Type: text/event-stream` and `Cache-Control: no-cache` (and `Connection:
  keep-alive` for HTTP/1.1 only), writes the status line and flushes. Every event is flushed before the call
  returns; a writer that cannot flush, a failed write and a gone client are errors that stick.
- `Patch(component, opts...)` renders into a buffer first, so a failing render sends nothing. `PatchHTML` takes
  rendered markup. Options: `WithSelector`, `WithMode` (`outer`, `inner`, `replace`, `prepend`, `append`,
  `before`, `after`, `remove`), `WithViewTransition`, `WithNamespace`, `WithEventID`, `WithRetry` (written only
  when it is not 1000 ms), and the shortcuts `Inner`, `Append`, `Prepend`, `Replace`, `Before`, `After`.
  `Remove(selector)` is a patch with mode `remove`.
- `PatchSignals(v)` JSON-encodes a value, `PatchSignalsJSON(raw)` validates and sends text; `OnlyIfMissing()`.
- `Focus(id)`, `CloseDialog(id)`, `Redirect(path)` and `ReplaceURL(path)` append a self-removing
  `<span hidden data-init="...">` to the body (never a `<script>`, which the page's CSP forbids). An id is
  validated (`ErrInvalidID`); a path must be local (`IsLocalPath`: a single leading `/`, not `//` or `/\`, no
  control character or backslash), otherwise `ErrUnsafeRedirect`.
- `ReadSignals(r, &v)` reads the `datastar` query parameter for `GET`, `HEAD` and `DELETE` and the JSON body
  for every other method, capped at 1 MiB. Absent, empty, `null` or invalid signals are a `*SignalsError`
  with `Status` 400, a larger body one with 413; unknown members are ignored.

A handled Datastar request always answers `200` with at least one patch (Datastar applies no other status);
failures are patched where they belong. Mutating actions are sent with `{retryMaxCount: 0}`.

## Static files: package `assets`

`assets.Load(opts...)` reads every file once and fails when one is missing. The files are the Java module's
(`framework-ui/src/main/resources/fw-ui`), copied into the module by `make -C go go-sync-shared`
(`go/internal/conformance/shared-files.txt`; the core module's conformance test fails when a copy differs).

```go
a, err := assets.Load()                                     // served under /fw-ui/
a, err := assets.Load(assets.WithPrefix("/ui/assets/"),     // an application mounted elsewhere
	assets.WithFiles(os.DirFS("static"), "fc-webauthn.js")) // its own files, served the same way

a.URL("kit.css")   // /fw-ui/kit.css?v=<first 12 hex of the sha256>
a.SpriteURL()      // /fw-ui/lucide-sprite.svg, for <use href>; give it to kit.WithSpriteURL
mux.Handle(a.Prefix(), a.Handler())
mux.Handle(assets.SpriteAliasPath, a.Handler()) // /icons/lucide-sprite.svg, the path the specs use
```

`ETag` is the quoted first 32 hex digits of the sha256; `If-None-Match` matching `*`, the tag or `W/` + the tag
(in a list) gets 304. `Cache-Control` is `public, max-age=31536000, immutable` with `?v` and
`public, max-age=3600, must-revalidate` without. Datastar 1.0.4 is pinned by sha256 (`assets.DatastarSHA256`);
the islands (`fc-code-editor.js`, `fc-diagram.js`, `mermaid.min.js`) are pinned too, and every third-party file
is served with its licence notice. `HasIcon(name)` and a test check that every icon the kit names is in the
sprite. The islands' sources and how to rebuild them are in
`ui-kit/islands/README.md`.
The platform's `fc-webauthn.js` is not in the module: the platform adds it with `WithFiles`.

## Building and checking

The templ files are compiled by `templ` (v0.3.1020, a `tool` of `go/ui/go.mod`) and the generated `*_templ.go`
files are committed. `make -C go ui-generate` regenerates them; `make -C go ui-verify` regenerates and fails if
any generated file changed or appeared, and is part of `make -C go go-check` and the CI workflow. `go-check`
also runs gofumpt, golangci-lint (`go/.golangci.yml`), `fwvet`, NilAway and `go test -race` on the module.

## Known gaps at GU1

- **`kit.css` is Java's build**, which scans only the Java templates, so it lacks about 20 utility classes that
  the Go kit's templates use, mostly in the components Java does not render yet (`aria-selected:*` of the tab
  list, `size-16` and `bg-red-500/15` of the permission-denied dialog, `[&_svg]:*` of the diagram, ...). The hand-written `.fc-*` rules are identical in both builds. A
  rebuild with the Go templates as an extra `@source` is open (see [Status](../STATUS.md)).
- Static files are not compressed (contract section 12 also describes per-encoding ETags for assets; the
  `compress` package has `Encode` and `Negotiate` for it, the `assets` handler does not use them yet).
- No pages, no `Patches` helpers, no flash cookie, no CSP builder, no form routes: GU2. The Java renderers for
  the seven components that only Go has, the thirteen spec-only components and the Go demo are GU3 to GU5.
