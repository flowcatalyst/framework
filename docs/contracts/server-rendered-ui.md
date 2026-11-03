# Contract: server-rendered UI (Java `framework-ui`, Go `go/ui`)

Written 2026-11-02 from the Java module as built (M-UI, 2026-10-16) and the platform's templ port, with the
owner's rulings of 2026-11-02 (section 14). Both frameworks' **primary** UI stays the shared Nuxt UI package
(`docs/go/ui.md`); this module is the optional one, for an application that wants server-rendered pages without
a JavaScript build. Java renders with JTE (`framework-ui`), Go with templ (module `go/ui`). Both render the same
markup from the same specs, and are held to it by the same fixtures.

## 1. Shape

| Part | Java | Go |
|---|---|---|
| Components | `kit.Kit` (JTE templates in `src/main/jte/kit`), `kit.Props` | package `kit` (templ) |
| Escaping and expression values | `kit.Markup` | package `kit` (`ID`, `Signal`, `Path`, `URL`, `Attrs`, ...) |
| Static files | `UiAssets`, `UiModule` | package `assets` |
| Pages and answers | `Views`, `Patches` | package `views` |
| Page policy | `ViewCsp` | `views.CSP` |
| Datastar wire | `framework-http` `http.datastar` (http contract section 10) | package `ds` (same section) |
| Flash after a redirect | `Views.page(..., flashes)` + section 9 | package `views` (section 9) |
| Compression | not built | package `compress` and `assets.WithCompression`, opt-in (section 12) |

The Go module's packages that are plain `net/http` (`kit`, `ds`, `assets`, `views`, `compress`, `htmlnorm`,
`md`) do not import the framework's lifecycle or router, so an application that is not built on the framework
(the FlowCatalyst platform, ruling R1) can use them. One package (`go/ui/fwui`) wires them into `fw` and `httpfw`.

## 2. Components

1. The reference is `ui-kit/components/<name>.md` (purpose, props, variants and states, markup, behaviour,
   accessibility) and `ui-kit/theme/tokens.css`.
2. **Every spec with a fixture directory has a renderer in both implementations.** A renderer passes when its
   output for every case in `ui-kit/fixtures/<name>/` equals the golden HTML after the normalisation of
   `ui-kit/fixtures/README.md`. Props are decoded strictly (an unknown property fails the case) and every slot a
   case fills must appear in the output.
3. Coverage is checked both ways: a fixture directory without a renderer fails, and so does a renderer without a
   fixture directory. A temporary `NOT_YET` list may name fixture directories without a renderer; it must be empty
   when a phase that adds components is closed.
4. A new component gets its spec and its fixtures **before** any renderer. The golden HTML is written by hand from
   the spec, never generated from a renderer.
5. Components that need a script beyond Datastar (an *island*: the code editor, the diagram) name the asset in
   their spec. The page that uses them lists it (section 6, rule 5); the component itself emits no `<script>`.

## 3. Escaping, and values that become code

1. Every text and attribute value is HTML-escaped (`& < > " '`).
2. A map of extra attributes escapes its values, skips a null or `false` value, writes `true` as a bare
   attribute, and **refuses** a name that is not `[A-Za-z_][A-Za-z0-9_.:-]*` or that starts with `on` (any case).
3. The values of `href`, `action`, `formaction`, `src` and `xlink:href` pass through the URL check: characters
   up to `0x20` and `0x7f` are ignored when finding the scheme (the part before the first `:` that comes before
   any `/`, `?` or `#`); no scheme, or `http`, `https`, `mailto`, `tel` (any case), keeps the URL; anything else
   (`javascript:`, `data:`, `vbscript:`, ...) becomes `about:invalid#blocked`.
4. Datastar attribute values are code. An id, a signal name or a path that enters an expression is validated and
   refused: id `[A-Za-z][A-Za-z0-9_-]*`, signal `[A-Za-z][A-Za-z0-9]*` (its attribute form is kebab-case, a `-`
   before each capital that follows a lower-case letter or digit, then lower-cased), path `/[A-Za-z0-9/_-]*` not starting with `//` (*amended 2026-11-02:* `//host` would fetch from another host; Java's
   `Markup.path` accepted it).
   User data goes into plain attributes (`data-id`) and is read with `el.dataset`, never written into an
   expression.
5. An unknown variant or size is refused, never replaced by a default.
6. Refused means: Java `IllegalArgumentException`, Go a panic with a message naming the rule, both while
   rendering, so the request fails with 500 and nothing half-written is sent. (Go: a templ component's panic is
   recovered by the framework's handler; `views` renders into a buffer before writing.)
7. The only unescaped paths are the explicit raw API (Java `Markup.raw`, Go `templ.Raw` wrapped by
   `kit.Raw`) and fixture slots. Markdown (section 11) is sanitised before it is raw.

## 4. Static files

1. Served under a prefix, default `/fw-ui/`; the Go `assets` handler takes the prefix as an option, so an
   application mounted elsewhere (the platform: `/ui/assets/`) keeps its paths.
2. Files: `kit.css` (the Tailwind build of `ui-kit/theme`, committed), `datastar.js` (1.0.4, vendored, its sha256
   pinned in a test: `727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91`), `lucide-sprite.svg`
   (the `ui-kit/icons` sprite), and the islands: `fc-code-editor.js` (CodeMirror 6), `fc-diagram.js` and
   `mermaid.min.js` (12.1.0). Each third-party file travels with its licence notice, and each has a pinned sha256.
3. `url(name)` = `<prefix><name>?v=<first 12 hex of the sha256>`; an unknown name is refused.
   The sprite's URL for `<use href>` is `<prefix>lucide-sprite.svg` (no `?v`, the fragment follows).
4. `ETag` = `"` + the first 32 hex of the sha256 + `"`. `If-None-Match` matches on `*`, the tag, or `W/` + the
   tag (comma-separated list): `304` with the `ETag` and `Cache-Control`, no body.
5. `Cache-Control`: with `?v` present `public, max-age=31536000, immutable`; without
   `public, max-age=3600, must-revalidate`.
6. Content types: `.css` `text/css; charset=utf-8`, `.js` `text/javascript; charset=utf-8`, `.svg`
   `image/svg+xml`. An unknown file is `404`.
7. The sprite also answers at `/icons/lucide-sprite.svg` (the path the specs and fixtures use).
8. Each file is read once at start-up; a missing file fails start-up.
9. Every icon name a component or a page uses must exist in the sprite (a test).

## 5. Pages: one template, a full document or a patch

1. The page content is wrapped in `<div id="fw-page">`; the flash stack has the id `flash-stack`.
2. A request with `Datastar-Request: true` (case-insensitive value) gets a `text/event-stream` answer whose one
   `datastar-patch-elements` event carries the wrapped content in the default mode (morph by id). **A Datastar
   answer is always `200`** (Datastar applies no other status), and carries no CSP header (it is not a document).
3. Any other request gets the full document with the given status (`200`, or `422` for a re-rendered form):
   - `<html>` with the theme attributes (section 10) and, with a shell, the shell attributes;
   - a head with the title (page title + the configured suffix), the stylesheet `url("kit.css")`, Datastar
     `url("datastar.js")` as `<script type="module">`, and the page's island scripts (section 6 rule 5);
   - with a shell: the app shell (section 7) around the flash stack and the content; without one:
     `<main class="mx-auto max-w-xl p-[24px]">` around the flash stack and the content;
   - the session-expired listener (section 8) when a login is configured;
   - no inline `<script>`, no `<style>` element, no `on*` attribute.
4. Document headers: `Content-Security-Policy` (section 6), `Cross-Origin-Opener-Policy: same-origin`,
   `Permissions-Policy: camera=(), microphone=(), geolocation=()`, `X-Content-Type-Options: nosniff`,
   `Cache-Control: private, no-cache`.
5. The same template yields the same fragment both ways (a test renders one page as a document and as a patch and
   compares the `#fw-page` element).
6. Flash messages given to a document render into its flash stack (section 9); a Datastar answer ignores them and
   patches a message instead (section 6, `flash`).

## 6. The page policy and the answer helpers

1. CSP, exactly (hashes optional, each `sha256-`, `sha384-` or `sha512-` + base64, given without quotes, a
   malformed one refused at build):
   `default-src 'self'; script-src 'self' 'unsafe-eval'[ '<hash>']...; style-src 'self'; style-src-attr
   'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri
   'none'; form-action 'self'; object-src 'none'`
   (`'unsafe-eval'`: Datastar compiles expressions with `Function`; `style-src-attr`: a few kit `style`
   attributes. Nothing else is relaxed.)
2. The answer helpers (Java `Patches`, Go `views.Patches`), each one `datastar-patch-elements` event unless said:
   `outer(content)` (morph by root ids), `page(content)` (the wrapped page), `replace(selector, content)` (mode
   `replace`: remove and insert, for a modal dialog the answer closes), `inner`, `append`, `prepend`,
   `remove(selector)`, `focus(id)` and `closeDialog(id)` (a self-removing `<span hidden data-init="...">` appended
   to `body`, never a `<script>`), `flash(kind, summary, detail)` (appends a flash message to `#flash-stack`),
   `signals(map)` (`datastar-patch-signals`).
3. Added 2026-11-02 (from the platform's port), both implementations: `redirect(path)` and `replaceURL(path)`, by
   the same self-removing element (`window.location.assign(...)`, `history.replaceState(...)`); the path must be
   local (section 8 rule 3), otherwise the helper refuses it.
4. A handled Datastar request answers `200` with at least one patch. An expected failure (validation, a use-case
   refusal) patches the error where it belongs or flashes it (`error`). Mutating actions are sent with
   `{retryMaxCount: 0}` so a failure is not resubmitted.
5. A page names its island scripts by asset name (Go: `views.WithScripts("fc-code-editor.js")`, Java: the
   `page` overload taking scripts); each becomes `<script type="module" src="url(name)">` in the head. Only module
   assets can be named.

## 7. The shell

The application supplies a function from the request to the shell props (`AppShell`: logo link, navigation groups
and items with the current item, the user (name, email, initials), version, profile and reset-password links,
the sign-out action, the theme). The module fills in the theme from the cookies. Nothing in the module names an
application path; every link in the shell comes from those props.

## 8. Sessions, CSRF and form posts

1. **Session expiry.** When a login is configured (`sessionExpiredLogin(path)`), the document carries a listener
   that, on a Datastar fetch answered `401`, navigates to `<path>?returnTo=<current path and query,
   percent-encoded>`. *Fixed 2026-11-02:* the parameter is `returnTo` (sessions contract 4.1); both kits sent
   `redirect`. An application with its own sign-in (the platform) may name the parameter (Go:
   `views.WithLogin(path, param)`).
2. **Form posts (ruling R2; amends sessions contract 7.2 rule 5).** A route the application declares as a form
   route (Java: a route option; Go: `httpfw.AcceptForms()`) also accepts `application/x-www-form-urlencoded` and
   `multipart/form-data` bodies on cookie-authenticated requests. The provenance rules 1 to 4 of 7.2 are unchanged
   and still apply. Bearer requests are unaffected. Routes not declared keep the JSON-only rule.
3. A local path (for `returnTo`, `redirect` and `replaceURL`, which carry user-visible paths with queries) starts
   with a single `/`, does not start with `//` or `/\`, and has no control characters or `\`.
4. Native form posts: a re-rendered form answers `422` with the page; success answers `303` to a local path with
   the flash cookie (section 9). Go: `Views.SeeOther(w, r, path, flashKey)`; a Datastar request cannot follow a
   `303`, so it gets the flash cookie and a `redirect` patch (`200`) instead. Java: the same helper (GU3).
5. Login pages are not part of the module.

## 9. Flash after a redirect

1. The cookie `fw_flash` holds a **key**, never message text: the application maps keys to messages
   (kind, summary, detail) in code. Setting a key that is not registered is refused (an error, no cookie); an
   unknown key read from a cookie renders nothing.
2. Set on the `303`: `Path=/`, `HttpOnly`, `SameSite=Lax`, `Max-Age=60`, `Secure` unless the request is plain
   HTTP in `local` or `test`. The value is the key, `[a-z0-9-]{1,64}`, otherwise refused.
3. Read and cleared (`Max-Age=0`, same attributes) by the next document answer; the message renders in the flash
   stack.

## 10. Theme

Cookies `fc_theme` (`light`, `dark`, `system`; anything else is `system`), `fc_scheme` (`dark` when the browser
reported dark), `fc_sidebar` (`collapsed`). The document's `<html>` attributes follow Java `Theme.htmlAttrs` and
`Theme.shellHtmlAttrs`; the theme menu sets the cookies itself.

## 11. Markdown

`markdown` renders CommonMark + GFM (tables, strikethrough, task lists, autolinks) with heading ids, then
sanitises with a user-generated-content policy that also keeps heading ids, `language-*` classes on `code`,
`text-align` on table cells, task-list checkboxes, and links with `http`, `https` or `mailto` only. Raw HTML in the
source is removed by the sanitiser. Go: goldmark + bluemonday (the platform's `md`). Java: an equivalent; the
`markdown` fixtures decide.

## 12. Compression (ruling R3; Go only for now)

Off by default; switched on in code (Go: `compress.Handler(next, ...)`). When on: brotli or gzip chosen from
`Accept-Encoding` with q-values (brotli preferred on a tie, `identity;q=0` honoured), `Vary: Accept-Encoding` on
every answer it could have compressed, only for `text/html`, `text/event-stream`, `text/css`, `text/javascript`
and `image/svg+xml`, never when the answer already has a `Content-Encoding`, the encoder flushed on every flush
(so each SSE event reaches the browser), and an asset's `ETag` suffixed per encoding (`-br`, `-gz`). Static-assets
contract ruling 6 is amended: the SPA handler still does not compress.

The static files are compressed by the assets handler itself, also opt-in (Go: `assets.WithCompression()`, added
2026-11-03): each `text/css`, `text/javascript` and `image/svg+xml` file is compressed once per coding (Brotli 9,
gzip 9), by the first request that negotiates it, and kept for the life of the instance; a compressed body that is not
smaller than the file is not used. The answer has the coding, the variant's `ETag` (the suffix above) and its
`Content-Length`; `If-None-Match` matches the variant's tag or the plain tag (the `304` carries the tag that matched)
and never the other coding's; `Vary: Accept-Encoding` is on every answer for those files. The middleware passes such
an answer through, so the handler may still be wrapped by it, with the same answers.

## 13. Tests both implementations carry

Fixture conformance (section 2), an escaping suite (section 3, each rule), an **XSS sweep** (every string prop of
every fixture case replaced, one at a time, by a payload set; the render must refuse it or produce no `script` or
`img` element and no `on*` attribute; `markdown` may keep a sanitised `<img>`, as its spec allows, but never a
`script` or an `on*` attribute), the pages suite (section 5: document vs patch, headers, no inline script,
same fragment, escaping both ways, theme cookies, flash), the helpers (section 6: one event each, bytes per http
contract 10), assets (section 4: caching, 304, 404, alias, pins, every icon exists), the CSP builder, the local-path
check, the flash cookie and, for Go, compression.

## 14. Rulings (owner, 2026-11-02)

- **R1.** The FlowCatalyst platform stops keeping its own copy of the kit and imports the Go module, as the last
  phase.
- **R2.** UI routes accept form posts (section 8 rule 2); the same change in Java.
- **R3.** Compression is an opt-in part of the Go module (section 12).
- **R4.** In scope: the platform's extra components (code editor, diagram, markdown, password input, secret
  reference input, permission-denied dialog, tab list, search select), the same components in Java's kit, the
  thirteen spec-only components (accordion, alert-dialog, avatar, card, dirty-form, dropdown-menu, hover-card,
  json-block, kbd, progress, radio-group, separator, tooltip) in both, and a Go server-rendered orders demo.
- **Taken here:** the Go module is its own Go module (`go/ui`), so templ, goldmark, bluemonday and brotli stay out
  of the core module; its Datastar adapter is the framework's own, to http contract section 10, not
  `datastar-go`.

## 15. Amendments to other contracts (2026-11-02)

- http section 10: signals are read from the `datastar` query parameter for `GET`, `HEAD` **and `DELETE`**
  (Datastar 1.0.4 sends a body only for other methods: `!["GET","DELETE"].includes(method)` in its bundle);
  a body above 1 MiB is refused `413`.
- sessions-and-login 7.2 rule 5: form routes, section 8 rule 2.
- static-assets-and-spa 12 ruling 6: section 12.
