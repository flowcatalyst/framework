# FlowCatalyst UI kit (HTML + Tailwind + Datastar)

> **Status (owner ruling 2026-10-14).** This is the kit of the **optional** server-rendered module
> (JTE + Datastar, `framework-ui`, milestone M-UI: optional, deferred) and of the FlowCatalyst
> platform's templ port under `/ui`. The Java and Go frameworks' primary UI is the shared Nuxt UI
> package (`docs/go/ui.md`, milestone MU), which does not render this markup: for it, the specs'
> purpose, variants and states, behaviour and accessibility sections and `theme/tokens.css` stay the
> reference; the golden HTML in `fixtures/` does not apply.

A language-neutral port of the FlowCatalyst server-rendered UI components (the Topcoat UI components themed for the product, plus the hand-built admin components) to plain **HTML + Tailwind CSS + Datastar**. No Topcoat, no Rust, no Node. Server templates in any language (JTE, Rust, TypeScript, ...) are written from the specs in `components/`; the gallery proves the markup and behaviour in a real browser.

- **Datastar:** 1.0.4 (stable, released 2026-09-21). Pinned in `gallery/*.html` as `https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.4/bundles/datastar.js`. All attribute names/separators in the specs are that release's (`data-on:click`, `data-attr:aria-current`, `data-signals:my-signal`, SSE events `datastar-patch-elements` / `datastar-patch-signals`).
- **Tailwind CSS:** 4.3.2 standalone CLI (the same version fc-web builds with); downloaded to `.bin/` by `build.sh`.
- **Components:** 55 specs (31 Topcoat UI components + 24 hand-built/composite/new), see `components/README.md`.

## Layout

| Path | What |
|---|---|
| `components/` | One spec per component (+ `_conventions.md`, read it first) |
| `theme/tokens.css` | CSS custom properties (light + dark), Tailwind `@theme`, radius/type scale |
| `theme/fc-components.css` | The hand-built `.fc-*` classes (carried over from fc-web) |
| `theme/kit.css` | Tailwind entry file (imports the two above, scans `gallery/` and `components/`) |
| `build.sh` | Builds `dist/kit.css` with the Tailwind standalone CLI |
| `dist/kit.css` | Build output (gitignored) |
| `icons/` | Lucide sprite (90 icons), its licence, the icon list and `build-sprite.sh` (rebuilds the sprite from the pinned lucide-static release) |
| `gallery/index.html` | Every component, every variant and state, light and dark, interactive |
| `gallery/sidebar.html`, `gallery/app-shell.html` | The two shells, embedded in the gallery as iframes |
| `gallery/CHECKS.md` | Result of the scripted interaction checks (69 pass) |
| `gallery/screenshots/` | Desktop screenshots, light and dark, plus open overlays |
| `fixtures/` | Conformance fixtures (props JSON + golden HTML) every template implementation must render identically; format and normalisation in `fixtures/README.md` |
| `PORTING-NOTES.md` | Per-component status and differences, how Topcoat concepts map to Datastar, what did not fit |
| `THIRD-PARTY-NOTICES.md` | Topcoat UI (MIT) notice and the other licences |

## Build and open the gallery

```sh
cd ui-kit
./build.sh                      # downloads .bin/tailwindcss v4.3.2 on first run, writes dist/kit.css
python3 -m http.server 8099     # any static server rooted at ui-kit/
open http://localhost:8099/gallery/index.html
```

It must be served over http(s): the icon sprite is referenced with `<use href>` (blocked on `file://`) and the shell iframes talk to the page with `postMessage`. `./build.sh --watch` rebuilds on change. The only network dependencies are the Tailwind download (once) and the Datastar CDN script (the gallery needs it to be interactive; the markup still renders without it).

## Conventions in one page

1. **Prefer the browser.** `<details>`, `<dialog>`, `popover`, native inputs, links and forms, CSS `:hover`/`:has()`. Topcoat does the same.
2. **Datastar only where state is needed**: selected tab, sidebar collapse, drawer record, client filter/sort, dirty form, pick list, dialog open/close handlers. Server round trips (mechanism **c**) are `@get/@post/@delete` actions whose replies are SSE `datastar-patch-elements` events.
3. **Dialogs open with Datastar, not `commandfor`.** `commandfor`/`command` is Chrome 135+, Firefox 144+, Safari 26.2+ (not yet universal), so the default is `data-on:click="document.getElementById('id').showModal()"` and `el.closest('dialog').close()`. The native alternative is noted in `components/dialog.md`.
4. **Markup and classes are the themed fc-web copies, unchanged**, except where listed in `PORTING-NOTES.md` (dark mode tokens, `aria-describedby` on tooltips, light dismiss, ...).
5. **No JavaScript files, no Node, no npm.** All behaviour is attributes. (See PORTING-NOTES for the few inline expressions that are real JavaScript.)
6. **Accessibility did not regress**: roles, labels, focus order and keyboard operation are kept; additions are listed.
7. Signals are page-global: give every stateful component instance its own signal name.

## Licence

The component markup and Tailwind class strings derive from Topcoat UI (MIT). The required notice is in `THIRD-PARTY-NOTICES.md`; it must travel with any copy of these files.
