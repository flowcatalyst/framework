<!-- types: Assets, Option, SSE, SignalsError, Mode, Namespace, PatchOption, SignalsOption, EventOption, Component, ButtonProps, AlertProps, DialogProps, FormDialogProps, PickListProps, AppShellProps, PermissionDeniedDialogProps, Views, Patches, PageOption, Declared, Module, Failure -->
# Go: server-rendered pages with templ and Datastar (optional)

> **Optional, and secondary to the Nuxt UI package.** The framework's primary UI is the shared
> `@flowcatalyst/ui` package ([Go: HTTP and the SPA](http.md)). Module `go/ui` is for an application that
> wants server-rendered pages without a JavaScript build; the Java counterpart is
> [Server-rendered pages (JTE)](../ui-jte.md). Nothing else in the framework depends on it. The
> specification is [`contracts/server-rendered-ui.md`](../../contracts/server-rendered-ui.md); the build plan
> is [`go/ui-templ-plan.md`](../../go/ui-templ-plan.md). **Phases GU1, GU2, GU3 and GU5 are built**: the module, the kit,
> `ds`, `assets`, `md`, `compress`, `htmlnorm` (GU1), and the pages and answers (`views`), form routes
> (`httpfw.AcceptForms`) and the framework wiring (`fwui`) (GU2); Java's catch-up is GU3; the orders demo is
> GU5 ([below](#the-orders-demo)). The thirteen spec-only components and the platform's adoption are GU4 and
> GU6 and do not exist yet.

## The module

`github.com/flowcatalyst/framework/go/ui` is its own Go module (its own `go.mod`, Go 1.27) so that templ,
goldmark, bluemonday and brotli stay out of the core module. It is built from the FlowCatalyst platform's
templ port, with the platform's defaults taken out. Every package except the kit is plain `net/http` and
imports neither the framework's lifecycle nor its router, so an application that is not built on the framework
can use them. Only `fwui` imports the core module.

| Package | What it is |
|---|---|
| `kit` | One templ component per spec in `ui-kit/components`, `Props` structs that mirror the specs' tables, and the hardening below |
| `ds` | The Datastar 1.0 adapter on `net/http`, written to [http contract section 10](../../contracts/http.md) |
| `assets` | The static files (kit stylesheet, Datastar, icon sprite, the islands) as an instance with hashed URLs, ETags and 304 |
| `md` | Markdown to sanitised HTML (goldmark with GFM, bluemonday) |
| `views` | Pages and answers: one template as a full document or a patch, `Patches`, the page policy (`CSP`), the flash cookie, the theme cookies |
| `fwui` | The framework wiring: `Declare`/`Build`, the static-file routes, the shell user from the session, the use-case error mapper |
| `compress` | Opt-in Brotli/gzip for pages, Datastar streams and the static files; flushes each event; an ETag per encoding |
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
fixture directories (189 cases) pass; GU3 added the search select (spec, 7 cases: 67 directories, 196 cases). Go's `SearchSelect` now builds `data-filter-text` as `multi-select.md` does (the label alone when it equals the value).

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

## Pages and answers: package `views`

`views.New(assets, opts...)` builds the page layer for an assets instance (contract sections 5 to 10). One page
template answers a navigation with a full document and a request with `Datastar-Request: true` with a patch of
the same content, so a link, a form post and a live update share a template. Everything is rendered into a buffer
first: an error returns and a panic propagates before anything is written, so a response is never half-sent.

```go
v, err := views.New(a,
	views.WithTitleSuffix(" - Demo"),
	views.WithLogin("/auth/login", ""), // the parameter defaults to returnTo
	views.WithFlashes(map[string]kit.FlashMessageProps{"saved": {Kind: kit.FlashSuccess, Summary: "Saved."}}),
	views.WithShell(func(r *http.Request) kit.AppShellProps { ... }))

err = v.Page(w, r, "Orders", content)                                  // 200, or a Datastar patch
err = v.Page(w, r, "Orders", form, views.WithStatus(422),              // a re-rendered form
	views.WithPageFlashes(kit.FlashMessageProps{Kind: kit.FlashError, Summary: "Check the details."}),
	views.WithScripts("fc-code-editor.js"))                            // island scripts, by asset name
err = v.Stream(w, r, func(p *views.Patches) error {                    // several patches
	return errors.Join(p.Inner("#rows", rows), p.Flash(kit.FlashSuccess, "Saved.", ""))
})
```

- **The document** is `<!DOCTYPE html>` with the theme attributes on `<html>`, a head (title plus the suffix, the
  stylesheet, `datastar.js` as a module, the page's island scripts) and the app shell around the flash stack and
  the content, or, without a shell, `<main class="mx-auto max-w-xl p-[24px]">`. It has no inline script, no
  `<style>` element and no `on*` attribute. The content is wrapped in `<div id="fw-page">`; the stack is
  `#flash-stack`. Headers: `Content-Security-Policy`, `Cross-Origin-Opener-Policy: same-origin`,
  `Permissions-Policy: camera=(), microphone=(), geolocation=()`, `X-Content-Type-Options: nosniff`,
  `Cache-Control: private, no-cache`. The shell function gets the request; the module fills in the theme from the
  cookies when its props leave it empty. Icons use the assets' sprite URL (the module puts it in the render
  context), so an application mounted under `/ui/assets/` works.
- **A Datastar answer** is always `200`, `text/event-stream` and has no CSP (it is not a document). `Page` sends
  one `datastar-patch-elements` event with the wrapped content; the document-only options (status, flashes
  from the cookie) do not apply. An invalid option (a script that is not a JavaScript asset, a status a document
  cannot have) is an error for either kind of request.
- **`Patches`** (contract section 6): `Outer`, `Page`, `Replace`, `Inner`, `Append`, `Prepend`, `Remove`, `Focus`,
  `CloseDialog`, `Redirect`, `ReplaceURL`, `Flash`, `Signals`; each is one event. `Redirect` and `ReplaceURL`
  take a local path only (`ds.ErrUnsafeRedirect` otherwise), `Focus` and `CloseDialog` a valid id. `SSE()` is the
  underlying `ds.SSE` for patch options.
- **`CSP(hashes...)`** returns the policy of contract section 6 rule 1 exactly: `script-src 'self'
  'unsafe-eval'` (Datastar compiles expressions with `Function`), `style-src 'self'` with `style-src-attr
  'unsafe-inline'`, `frame-ancestors 'none'`, `base-uri 'none'`, `form-action 'self'`, `object-src 'none'`. Script
  hashes (`WithScriptHash`) are `sha256-`, `sha384-` or `sha512-` plus base64, without quotes; a malformed one is
  an error at `New`. The policy is weaker than the single-page application's strict one because of
  `'unsafe-eval'`; the kit escapes every dynamic value, and the policy is the second line.
- **Session expiry** (`WithLogin(path, param)`): the document's `<body>` listens for a Datastar fetch answered
  `401` and navigates to `<path>?<param>=<current path and query, percent-encoded>`. The parameter is `returnTo`
  unless named; the path must be a local path of letters, digits, `/`, `_` and `-`.
- **Flash after a redirect** (contract section 9): the cookie `fw_flash` holds a key, never text.
  `v.SetFlash(w, r, key)` (key `[a-z0-9-]{1,64}`, otherwise `views.ErrInvalidFlashKey`; a key not registered with `WithFlashes`, `views.ErrUnknownFlashKey`) sets it with `Path=/`,
  `HttpOnly`, `SameSite=Lax`, `Max-Age=60` and `Secure`; the next document answer looks the key up in the
  `WithFlashes` map (an unknown key renders nothing), renders the message in the flash stack and clears the
  cookie. A Datastar answer leaves the cookie alone. `Secure` is dropped only when the application says so with
  `WithPlainHTTPCookies()` **and** the request is plain HTTP (`fwui` sets it when `APP_ENV` is `local` or `test`).
  `v.SeeOther(w, r, path, key)` answers a successful form post: `303` to a local path with the flash (an empty key
  sets none); a Datastar request cannot follow a `303`, so it gets a redirect patch with status `200` instead.
- **Theme** (contract section 10): `fc_theme` (`light`, `dark`, `system`; anything else is system), `fc_scheme`
  (`dark` when the browser reported dark) and `fc_sidebar` (`collapsed`) decide the `dark` class and the signals
  on `<html>` (`kit.ThemeHTMLAttrs`, `kit.ShellHTMLAttrs`), so a dark page does not flash light.

The tests (`views/views_test.go`) are the port of Java's `ViewsTest` plus the flash cookie, the local-path check,
the CSP builder, the options, and "an error or a panic writes nothing".

## Form posts: `httpfw.AcceptForms`

A native form posts `application/x-www-form-urlencoded` or `multipart/form-data`, which the sessions CSRF check
refuses (`415`) on every route by default. A route registered with `httpfw.AcceptForms()` takes them, after the
provenance rules, which are unchanged ([HTTP page](http.md#csrf-and-security-headers)). The page's success path
is `v.SeeOther(...)`; a use-case refusal is a `422` re-render (next section).

## The framework wiring: package `fwui`

```go
ui := fwui.Declare(app)                                  // declaring: adds the "ui" module, no settings
...
mod, err := ui.Build(b,                                  // in the build function
	fwui.WithViews(views.WithLogin("/auth/login", ""), views.WithShell(shellFor)))
srv, err := httpfw.NewServer(b, web, []*httpfw.Routes{authModule.Routes(), mod.Routes(), pages}, ...)
```

- `Build` loads the assets (`fwui.WithAssets(assets.WithPrefix("/ui/assets/"), ...)`), builds the views
  (`fwui.WithViews(...)`) and the route table: `GET <prefix>{file}` and `GET /icons/lucide-sprite.svg` (any
  start-up problem is a `*fw.StartupError` naming module `ui`). `mod.Views()` and `mod.Assets()` are for the
  handlers. `fwui.CompressAssets()` serves the files through `compress.Handler`; pages and streams are compressed
  by wrapping the server (`compress.Handler(srv.Handler())`).
- `fwui.ShellUser(r)` is the shell's user from `auth.Current`: the display name (the email, then the principal id,
  when the token had none), the email and the initials (`fwui.Initials`). It needs a route that requires a context.
- **Errors.** `fwui.Classify(err)` maps an error to a `Failure` (status, code, message, details): a
  `httpfw.ServiceError` (which a use case's `*usecase.Error` is) with a 4xx status keeps its message; anything else
  is a 500 with a generic message, because it may name internals. `mod.Fail(w, r, err)` answers a Datastar request
  itself (status `200`, one error flash patch; the real status is only logged) and returns `answered`; for a native
  request it writes nothing and the handler re-renders the form when `f.Rerender()` (validation `400`, conflict or
  business rule `409`, `422`) with `views.WithStatus(f.FormStatus())` (`422`), or returns the error for the
  framework's envelope (403, 404, 5xx). On a stream already open, `mod.FlashFailure(patches, r, err)`.

```go
f, answered := mod.Fail(w, r, err)
if answered {
	return nil
}
if !f.Rerender() {
	return err
}
return mod.Views().Page(w, r, "Orders", form, views.WithStatus(f.FormStatus()),
	views.WithPageFlashes(kit.FlashMessageProps{Kind: kit.FlashError, Summary: f.Title(), Detail: f.Message}))
```

The tests (`fwui/fwui_test.go`) run a real application: the auth module against the test provider, a signed-in
session, the static routes, a form route through the real sessions guard (`303` with the flash cookie, `403`
without provenance, `415` on a route without `AcceptForms`), and the error mapping both ways.

## The orders demo

`go/ui/examples/orders-demo-templ` is the counterpart of Java's `examples/orders-demo-jte` ([JTE guide](../ui-jte.md)):
the same in-memory orders (23 seeded), the same pages, URLs, element ids, signals and element structure, so the
Java demo's browser check runs against it unchanged. It is a main package plus `internal/orders` (the store, the
query, the form, the handlers and the templ templates). It needs no database and no configuration file.

```
go run ./examples/orders-demo-templ serve --port 4711     # from go/ui; binds 127.0.0.1, there is no default port
make -C go e2e-orders-templ                              # builds the binary, starts it on a free port, runs the browser check
```

**Wiring.** The demo uses the framework's lifecycle: `fw.App`, `httpfw` for the server and `fwui` for the assets
and the views. It has no sessions, so there is no `auth` module and no resolver, and its handlers are plain
`httpfw.HandlerFunc`s.

```go
return func(_ context.Context, b *fw.Boot) (fw.Serve, error) {
	mod, err := ui.Build(b, fwui.WithViews(views.WithTitleSuffix(" - Orders demo"), views.WithShell(orders.Shell)))
	...
	pages := httpfw.NewRoutes("orders-ui")
	orders.New(mod.Views(), orders.Seeded(b.Clock().Now()), b.Clock()).Register(pages)
	return httpfw.NewServer(b, web, []*httpfw.Routes{mod.Routes(), pages})
}
```

**Routes.** `GET /` redirects to `/orders`; `GET /orders` is the list; `GET /orders/{id}` is the drawer (a Datastar
request) or the record page (a navigation); `POST /orders` creates; `POST /orders/{id}/cancel` cancels. Each answers a
navigation with a document and a Datastar request with patches, through the same template:

```go
f := Validate(formValues(r))
if len(f.Errors) > 0 {
	// an expected failure: the form again with its errors (200 for Datastar, 422 for a navigation)
	if ds.IsDatastar(r) {
		return u.views.Stream(w, r, func(p *views.Patches) error { return p.Outer(newOrderDialog(f)) })
	}
	return u.listPage(w, r, http.StatusUnprocessableEntity, f)
}
```

A successful create through Datastar reloads the list from the defaults, replaces the dialog (so the old modal
leaves the top layer; a morph would not do that), flashes and hands focus back to the trigger:

```go
if err := p.Outer(ordersList(ordersView{Query: q, Listing: l, Form: EmptyForm()})); err != nil { return err }
if err := p.Replace("#new-order", newOrderDialog(EmptyForm())); err != nil { return err }
if err := p.Flash(kit.FlashSuccess, "Order created.", o.Customer+" ("+o.ID+")"); err != nil { return err }
return p.Focus("new-order-open")
```

A native success is `u.views.SeeOther(w, r, "/orders?flash=created", "")`. The demo keeps Java's flash scheme, a fixed
key in the query string (`?flash=created`, looked up in `orders.Flashes`; an unknown key shows nothing, and request
text is never a message), and so passes no key to `SeeOther`. An application that does not want the key in the URL
registers the messages with `views.WithFlashes` and passes the key, which travels in the `fw_flash` cookie
([above](#pages-and-answers-package-views)).

**Templates** are templ files over the kit's props (`internal/orders/pages.templ`). Slots are templ children or a
`templ.Component` field:

```templ
@kit.TR(kit.TRProps{ID: "row-" + o.ID}) {
	@kit.TD(kit.TDProps{}) {
		<a href={ kit.SafeURL("/orders/" + o.ID) } data-id={ o.ID } class="font-medium text-foreground no-underline" data-on:click__prevent={ OpenOrder }>{ o.Customer }</a>
	}
```

The row link has a plain `href` and the id in `data-id`; the click handler (`OpenOrder`) is the constant
`$selOrder = el.dataset.id; @get(el.href)`, so no user data enters a Datastar expression.

**The same-origin check.** The demo has no sessions, so `httpfw.AcceptForms` (honoured by the sessions guard) does
not apply: a form body is not refused by anything. The POST handlers call `refuseCrossSite`, a port of the Java demo's
`requireSameOrigin`: `Sec-Fetch-Site` must be `same-origin` or `none`, else `Origin` must equal the request's own
`http(s)://Host`, else `403`; a request with neither header passes.

**Tests.** `internal/orders/ui_test.go` is the port of Java's `OrdersUiTest` (13 tests, the same assertions) on
`httptest` over `httpfw.Compile`; `selector_test.go` is a small CSS-selector engine over `golang.org/x/net/html` (the
module has no jsoup or goquery). `main_test.go` checks the `serve --port` parser. `make -C go e2e-orders-templ` runs
`examples/orders-demo-jte/e2e/browser-check.mjs` unchanged against the binary (Playwright and Chromium come from the
`ui` workspace; the target stops the server by its PID).

## Compression and the assets

`compress.Handler` also compresses `text/css`, `text/javascript` and `image/svg+xml` (contract section 12), so it
can wrap the assets handler. A compressed response's `ETag` gets the coding as a suffix inside the quotes (`"abc"`
becomes `"abc-br"` or `"abc-gz"`, a weak tag keeps its `W/`), `Vary: Accept-Encoding` is on every answer it could
have compressed, and `If-None-Match` matches per variant: a request that negotiated Brotli and sent `"abc-br"`
gets `304` (with the variant's tag), `"abc-gz"` matches nothing. The middleware translates a tag with the
negotiated coding's suffix back to the handler's own tag, so the assets' own conditional logic is unchanged. A tag
without a suffix still reaches the handler as sent and matches the uncompressed tag (the `304` then carries the
plain tag): only a client that cached the uncompressed body sends that. A `HEAD` carries the headers of the `GET`.

## Building and checking

The templ files are compiled by `templ` (v0.3.1020, a `tool` of `go/ui/go.mod`) and the generated `*_templ.go`
files are committed. `make -C go ui-generate` regenerates them; `make -C go ui-verify` regenerates and fails if
any generated file changed or appeared, and is part of `make -C go go-check` and the CI workflow. `go-check`
also runs gofumpt, golangci-lint (`go/.golangci.yml`), `fwvet`, NilAway and `go test -race` on the module.

## Known gaps

- **`kit.css`** is the Tailwind build of Java's templates **and** the Go kit's templ sources (`framework-ui/assets/kit.css`
  names `go/ui/kit/*.templ`, `kit.go` and `go/ui/views/*.templ` as extra `@source`s; `assets/build.sh`, then
  `go/internal/conformance/sync-shared.sh`). Tailwind also picks up words in comments and prose as class names, so a
  few unused rules (`blur`, `italic`, ...) are in the file. An application with its own templates writes its own
  input, as the file's header says.
- The thirteen spec-only components and the platform's adoption are GU4 and GU6. Java has caught up
  (GU3, [JTE](../ui-jte.md)): the seven components, search select, form routes, `returnTo`, `Patches.redirect`, island
  scripts and the flash cookie.
- `views` has no `?theme=` query override (the platform's shell has one for screenshots).
