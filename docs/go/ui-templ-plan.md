# Plan: the Go server-rendered UI module (`go/ui`, templ + Datastar)

Owner request 2026-11-02: the Go framework gets the server-rendered UI that the Java framework has (`framework-ui`,
M-UI), built from the FlowCatalyst platform's templ + Datastar port (`flowcatalyst-go/internal/ui`), improved and
extended, and the platform then uses it. Contract: [`../contracts/server-rendered-ui.md`](../contracts/server-rendered-ui.md)
(written first; rulings R1 to R4 in its section 14). This supersedes decision 32's "later" ([`design.md`](design.md)).

## Starting point (2026-11-02)

| | Java `framework-ui` | Platform `internal/ui` (Go) |
|---|---|---|
| Fixture dirs rendered | 59 of 66 (`NOT_YET`: code-editor, diagram, markdown, password-input, permission-denied-dialog, secret-ref-input, tab-list) | 66 of 66 (189 cases), plus `SearchSelect` with no spec |
| Page or patch from one template | `Views.page` | none (pages call the shell; handlers build patches) |
| Answer helpers | `Patches` | `ds.SSE` (adds `Redirect`, `ReplaceURL`; no `page`, `replace`, `prepend`, `flash`) |
| Page policy | `ViewCsp` (`style-src-attr`, `base-uri 'none'`, hashes) | looser (`style-src 'unsafe-inline'`, `base-uri 'self'`) |
| Escaping tests, XSS sweep | yes | no (only markdown sanitising) |
| `attrs` refuses `on*`, URL scheme check, unknown variant refused | yes | no (templ passes attributes through) |
| Assets | instance, `?v=`, ETag, 304, no compression | package globals, hashed file names, brotli/gzip |
| Flash after redirect | not built | `fc_ui_flash` cookie, fixed keys |
| Session-expired login | configurable, sends `?redirect=` (wrong: login takes `returnTo`) | hard-coded `/auth/login?redirect=` |
| Datastar wire | own adapter (http contract 10) | `datastar-go` v1.2.2 wrapper |

## Phases

Each phase ends with its tests green, the guide page and `docs/guide/STATUS.md` updated, `docs/guide/verify.sh`
OK, and one commit per step (made in the main session after verification).

### GU1: the module, and the kit moved over (Go)

1. **Module** `go/ui`, path `github.com/flowcatalyst/framework/go/ui`, go 1.27, requiring the core module by
   `replace => ../` as `go/flowcatalyst` does. `tool github.com/a-h/templ/cmd/templ` at the platform's version
   (v0.3.1020). Generated `*_templ.go` committed. Makefile: `ui-generate`, `ui-verify` (generate, then fail on a
   diff); `go-check` covers the new module (gofumpt, golangci-lint, `fwvet`, NilAway, `test -race`);
   `.github/workflows/go.yml` too.
2. **Copied from the platform** (the platform keeps its copy until GU6): `kit` (all templ components, `navgroup`),
   `htmlnorm`, `md`, `compress`. Remove every platform default from the kit (permission-denied's `/ui/login` and
   `/ui` become props). No testify (stdlib tests, as the rest of the framework).
3. **`ds`**: the framework's own adapter to http contract section 10, amended (signals from the query for `GET`,
   `HEAD`, `DELETE`; 1 MiB cap, `413`), with the platform's `Redirect`, `ReplaceURL`, `Focus`, `CloseDialog`
   (self-removing `data-init` elements, local-path check). No `datastar-go`. Byte tests from the contract text and
   Java's `framework-http` datastar tests' expected output.
4. **`assets`**: an instance (`assets.Load(opts...)`: prefix, default `/fw-ui/`; extra application files from an
   `fs.FS`, for the platform's `fc-webauthn.js`), contract section 4: `URL`, `SpriteURL`, `Names`, `HasIcon`,
   `Handler()`, `?v=` scheme, ETag, 304, cache rules, the `/icons/lucide-sprite.svg` alias, start-up failure on a
   missing file, sha256 pins. **One source for the files:** `kit.css`, `datastar.js` and the sprite are Java's
   `framework-ui/src/main/resources/fw-ui/` files; the islands (`fc-code-editor.js`, `fc-diagram.js`,
   `mermaid.min.js`) and their licences move there too, with their build script and sources into
   `ui-kit/islands/`. Go copies them with `go/internal/conformance/sync-shared.sh` + `shared-files.txt`
   (embedded files must be inside the module). Find out how the platform's `kit.css` differs from Java's (the
   platform syncs from `ui-kit` at `fdaa6f34`; Java rebuilt at `98e00cb`) and record it.
5. **Hardening (contract section 3)**: `kit.Attrs` (validated names, values escaped, URL attributes checked) for
   every component that takes extra attributes; `kit.URL` on every `href`/`action`/`src` a component renders;
   unknown variants panic; `kit.Raw` the one raw path.
6. **Tests**: fixture conformance against `../ui-kit/fixtures` read in place (override `UI_KIT_FIXTURES`), both
   directions; the escaping suite and the XSS sweep ported from Java's `EscapingTest` and `XssSweepTest`; assets;
   `ds`; `md`; `compress`.
7. **Docs**: guide page `docs/guide/go/ui-templ.md` (types header), nav entry, STATUS, milestones row `GU`.

### GU2: pages, answers, form routes, framework wiring (Go)

1. **`views`**: the builder (title suffix, shell function, `WithLogin(path, param)` default param `returnTo`,
   script hashes), `Page(w, r, title, content, opts...)` (status, flashes, island scripts), `Stream(w, r, func(*Patches)
   error)`, `Patches` (contract section 6, with `Redirect` and `ReplaceURL`), `CSP` (section 6 rule 1), the document
   (section 5; the platform's `shell/layout.templ` generalised), the flash cookie (section 9), the theme (section 10).
   Rendered into a buffer before anything is written.
2. **Form routes (R2)**: `httpfw.AcceptForms()` route option, honoured by `httpfw.SessionsGuard` through
   `auth.Sessions`; provenance rules unchanged. Tests: a form body on a form route passes with provenance, is
   refused `403` without, is `415` on any other route; bearer requests unaffected. Update the sessions contract's
   test list (section 11). Security-sensitive: reviewed in the main session line by line.
3. **`fwui`**: `Declare`/`Build` in the framework's style; registers `GET <prefix>{file}` and the sprite alias;
   a shell-user helper from `auth.Current`; a use-case error mapper (the framework's `usecase` errors to a Datastar
   `200` + error flash, or a `422` re-render for a native form).
4. **Tests**: the pages suite of contract section 13 (port Java's `ViewsTest`), the flash cookie, the local-path
   check, the CSP builder.

### GU3: Java catches up (ruling R4, and the contract fixes)

1. Renderers for the seven `NOT_YET` fixture directories; `NOT_YET` empty. Markdown: commonmark-java with the GFM
   extensions and a sanitiser (OWASP java-html-sanitizer) to section 11; the `markdown` fixtures decide.
2. **Search select**: write the spec `ui-kit/components/search-select.md` and its fixtures from the platform's
   component first (contract section 2 rule 4), then the Java renderer; the Go renderer is checked against the new
   fixtures.
3. Contract fixes in Java: `returnTo`, signals for `DELETE`, the 1 MiB `413`, the form-route option in the sessions
   guard, `Patches.redirect`/`replaceURL`, island assets in `UiAssets`, the page-scripts overload, the flash cookie.
4. Docs: `docs/guide/ui-jte.md`, STATUS.

### GU4: the thirteen spec-only components (both)

accordion, alert-dialog, avatar, card, dirty-form, dropdown-menu, hover-card, json-block, kbd, progress,
radio-group, separator, tooltip. First the fixtures, hand-written from each spec (and checked against the gallery's
markup), then the Go and Java renderers in parallel. `NOT_YET` empty in both at the end.

### GU5: a Go server-rendered orders demo

`go/ui/examples/orders-demo-templ`, the counterpart of Java's `examples/orders-demo-jte` (its pages, its same-origin
check now covered by form routes, its browser check), on the `ordersweb` library, sessions from `auth`. `make -C go
e2e-orders-templ`.

### GU6: the platform uses the module (ruling R1)

1. **Needs an owner decision first:** how the platform fetches the module (decision 33: nothing is published; the
   platform's Docker and CI builds need a tag such as `go/ui/v0.1.0` on `github.com/flowcatalyst/framework`, or a
   pinned pseudo-version, and access to that repository).
2. The platform replaces `internal/ui/{kit,ds,assets,compress,htmlnorm,md}` with the module's packages; `guard`,
   `shell`, `pages` stay platform code (the shell adopts `views` where it fits). Its assets keep `/ui/assets/` as the
   prefix (URLs change from hashed names to `?v=`); `fc-webauthn.js` stays a platform file served through
   `assets`' extra files. Its Tailwind build must still cover the classes its own pages use.
3. The platform's browser checks and `ui-verify` green; `internal/ui` loses the moved packages.

## Order and parallelism

GU1 → GU2 → GU3 and the GU4 fixtures in parallel → GU4 renderers (Go and Java in parallel) → GU5 → GU6. Two agents
never edit the same files; Java and Go renderers touch different trees, the fixtures are written before either.

## Rules for the agents

- Implement from this plan and the contract; a question of behaviour comes back to the main session, it is not
  decided in code. A contract that turns out wrong is reported, not silently changed.
- No git commands that change state (no commit, checkout, restore, stash, reset, clean, switch, push); the main
  session commits.
- No process killed by pattern; only a PID the agent started. Never touch a running `fcdev` (`:8080`) or its
  database (`:15432`).
- Count tests from the tool output, report the exact commands and results, and say what was not verified.
