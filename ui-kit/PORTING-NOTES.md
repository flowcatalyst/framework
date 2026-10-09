# Porting notes

Port of the Topcoat UI components (fc-web's 16 themed copies + the 15 upstream components not installed there) and the hand-built admin components to HTML + Tailwind + Datastar 1.0.4. Sources read: `flowcatalyst-rust/docs/topcoat-{components,trial,handover}.md`, `crates/fc-web/src/{components/*.rs,ui.rs,ui/*.rs,app/{ui.js,shell.rs,users.rs,users_drawer.rs},styles.css}`, and `topcoat-ui-registry-0.9.0` from the local cargo registry (present, so all 31 components were ported).

Status key: **P** ported (markup and behaviour as the source), **PD** ported with differences (listed), **NP** not ported (reason). Mechanism: (a) browser, (b) Datastar client-side, (c) Datastar backend action.

## Per-component table

| Component | Source | Status | Mech. | Differences from the original |
|---|---|---|---|---|
| alert | fc-web | P | a | none |
| alert_dialog | fc-web | PD | a/b | opened with a Datastar click handler instead of `commandfor`; `autofocus` on the safe action added; mask click does not dismiss (as before) |
| badge | fc-web | P | a | none (variants incl. fc-web's success/info/warn) |
| button | fc-web | P | a | none (variants incl. fc-web's text/destructive-outline/success-outline) |
| checkbox | fc-web | P | a | check icon from the SVG sprite instead of Topcoat's `icon()` |
| dialog | fc-web | PD | a/b (c opt.) | open/close by Datastar handlers, not `commandfor`/`command`; mask-click dismiss is one Datastar attribute on the dialog (was `ui.js` `data-light-dismiss`); `MODAL` dark-mask is an option; `.tc-theme` scope folded into `@theme` |
| dropdown_menu | fc-web | PD | a + b | **added** outside-click and Escape dismissal (+ focus back to summary) and close-on-item-click: upstream has none. Still no `role=menu`/arrow keys (as upstream) |
| field (+ set/legend/group/content/label/title/description/separator/error) | fc-web | P | a | none |
| input | fc-web | P | a | none |
| label | fc-web | P | a | none |
| pagination | fc-web | P | a | none (links only, as upstream) |
| select | fc-web | P | a | none; checkmark mask URL written as `&quot;`-escaped data URI |
| sheet | fc-web | PD | a/b | as dialog (Datastar open/close/mask dismiss) |
| switch | fc-web | P | a | none |
| table (+ parts) | fc-web | PD | a | `bg-[#f8fafc]`/`text-[#475569]` per-use overrides became `bg-subtle`/`text-subtle-foreground` tokens (same light values) so dark mode works |
| tooltip | fc-web | PD | a | `aria-describedby` on the trigger + `id` on the bubble **added** (the original never linked them) |
| accordion | upstream | P | a | none |
| avatar | upstream | P | a | none |
| breadcrumb | upstream | P | a | none |
| card | upstream | P | a | none |
| hover_card | upstream | PD | a | `aria-describedby`/`id` added |
| kbd | upstream | P | a | none |
| progress | upstream | P | a | none |
| radio_group | upstream | PD | a | border `border-input` (was `border-border`) to match fc-web's themed checkbox |
| separator | upstream | P | a | none |
| sidebar (+ ~20 parts) | upstream | PD | b | Expr<bool> props -> two signals; trigger/rail/mobile close/Escape/backdrop are Datastar attributes; **`--sidebar-*` token values are new** (navy); the rail sits inside the panel (as upstream's usage) because the sheet's `z-50` otherwise covers it; desktop/mobile triggers wrapped in `hidden md:contents` / `md:hidden` spans (avoids `display` utility conflicts) |
| skeleton | upstream | P | a | none |
| spinner | upstream | P | a | icon from the sprite |
| tabs (+ list/trigger/content) | upstream | PD | b (a/c) | client tabs by one signal; trigger `href="#panel"` fallback; no arrow-key roving (as upstream) |
| textarea | upstream | PD | a | `border-input bg-card` instead of `border-border bg-transparent`, to match the themed input |
| toggle / toggle_group | upstream | P | a | none |
| page_header | hand-built | P | a | none |
| tag | hand-built | P | a | none |
| code_chips | hand-built | P | a | dark-mode segment colours added |
| json_block | hand-built | P | a | none |
| empty_state | hand-built | P | a | none |
| flash_banner | hand-built | P | a (c) | markup as is; the flash-cookie plumbing is server-side (see NP); dynamic append documented |
| search_input | hand-built | P | a | none |
| cursor_pager | hand-built | P | a | none |
| detail_field / detail_value | hand-built | P | a | none |
| form_section | hand-built (plain markup in `form.rs`) | P | a | none |
| legacy `.fc-btn` / `.fc-icon-btn` | hand-built | P | a | none (kept because the hand-built components use it) |
| filter_select | hand-built | PD | a (c) | inline `onchange` -> `data-on:change` |
| paginator | hand-built | PD | a (c) | inline `onchange` -> `data-on:change` |
| local_time | hand-built + `ui.js` | PD | b | MutationObserver in `ui.js` -> `data-init` on the `<time>` (also runs for server-patched rows) |
| table_toolbar | hand-built | PD | a + b (c) | `ui.js` popover-anchoring handler -> one `data-on:toggle__capture` expression on the popover; inline `onchange` -> `data-on:change` |
| drawer (frame + header) | hand-built | PD | b (c) | Topcoat `Signal<String>` + `ui.js` Escape handler -> one signal + `data-show` + `data-on:keydown__window` |
| form_field | hand-built | PD | a (c) | error/help linked to the control with `aria-describedby` (added) |
| confirm_dialog | hand-built | PD | a/b | `commandfor`/`command` and `ui.js` -> Datastar handlers; `autofocus` on Cancel |
| pick_list | hand-built dialog content + `ui.js` | PD | b | `ui.js` mirror/filter/dirty -> array `data-bind` + `data-show` (positional-array semantics, see below) |
| dirty_form | `ui.js` | PD | b | MutationObserver snapshot -> `data-init` + `data-on:input` + `data-on:reset` |
| app_shell (fc-sidebar + user menu) | hand-built | PD | b + a | inline `onclick` class toggle -> signal on `<html>` (`data-class:fc-collapsed`); collapse button gets `aria-expanded`; user trigger `aria-label` |
| data_table / list page (composition) | composition | PD | b (c) | quick filter and row-to-drawer as the originals; **client-side sort is new** (the originals sorted on the server) |
| login page styles (`.fc-login*`) | `styles.css` | NP | - | a page, not a component; deliberately not carried over |
| flash cookie plumbing (`set_flash`/`take_flash`) | `ui/flash.rs` | NP | - | server-side concern (cookie set on redirect, read once); the markup is ported. The rule is now in `_conventions.md`: the cookie holds a fixed key only, mapped to text on the server (the Go platform implements it) |
| row_actions | new (Go platform port, 2026-10-09) | new | a + b (c) | not in fc-web or Topcoat: inline `.fc-icon-btn` actions + a native `popover` overflow menu (`.fc-row-menu`, `.fc-menu-item`), destructive marking, disabled-with-reason; `row-actions.md` |
| flash_stack / flash_message | SPA `NotificationBannerStack.vue` | new | c + b | the SPA's banner stack (four kinds, lifetimes 3s/5s/persistent, dismiss), as `.fc-flash*`; plus a **new** fallback for failed Datastar requests (`flash-stack.md`); messages rendered with the page (slot) for a flash after a redirect, carried by a fixed key |
| form_dialog | SPA create drawers / inline edit + `useDirtyForm` + discard confirmation | new | b + c (a) | composite of dialog, dirty form, field and the confirm-dialog look: server validation answered as field patches, form-level error region, busy/disabled submit, discard question (`form-dialog.md`) |
| autocomplete | SPA `AutoComplete` (`ClientSelect`) | new | a + c | `<input list>` + `<datalist>` refreshed by a debounced server search; not a custom listbox (`autocomplete.md`) |

**Counts:** 31 P, 22 PD, 2 NP (the two NP rows are server/page concerns, not components), 4 new. 57 component specs.

Global differences (apply to every component):

- **Dark theme is new.** fc-web has light values only; `tokens.css` adds a `.dark` set (slate/emerald) following Topcoat's neutral theme structure. Colours that fc-web wrote as raw hex (`#f8fafc`, `#475569`, `#fff`, ...) in the `.fc-*` CSS were replaced by `--fc-*`/token variables with the same light values (list in `tokens.css`), plus a small `.dark` override block.
- `.tc-theme` (a scope in fc-web) is folded into `@theme`, as fc-web's own doc says it should be once the older sections are gone.
- Topcoat's `icon()` (Iconify at build time) -> inline Lucide SVG via an SVG sprite.
- Class strings are the Rust source's, concatenated (`\` continuations joined). The gallery and specs are generated from one set of constants.
- `.fc-menu-item`'s text colour is `var(--fc-text)` (same light value, #475569) instead of the raw hex, so the user menu's items are readable in dark mode (2026-10-09).
- `scrollbar-gutter: stable` on `<html>` (carried over) leaves a 15px strip beside full-viewport overlays on desktops with classic scrollbars.

## How Topcoat's reactive model mapped to Datastar

**`Expr<bool>` props (`open`, `active`, `mobile_open`).** A Topcoat prop is either a constant or a signal expression the runtime re-evaluates. In Datastar the signal is explicit and the binding is an attribute on the element:

| Topcoat | Datastar |
|---|---|
| `tabs_trigger(active: tab == "a")` -> `:aria-current` | `data-attr:aria-current="$tab === 'a' && 'page'"` |
| tab panel `:hidden` | `data-show="$tab === 'a'"` |
| `sidebar(open: …)` -> `:data-state`, `:data-collapsible` | `data-attr:data-state="$sidebarOpen ? 'expanded' : 'collapsed'"` etc. |
| `sidebar(mobile_open: …)` -> `<dialog :open>` | `data-attr:open="$sidebarMobileOpen"` (non-modal, as upstream) |
| `drawer_frame` `:hidden=$(selected.get().is_empty())` | `data-show="$sel !== ''"` |
| `@click=$(\|_e\| sig.set(..))` | `data-on:click="$sig = ..."` |
| `dialog(open: …)` (non-modal in Topcoat; fc-web instead opened via invoker commands) | `showModal()` from `data-on:click`; `open` is **not** bound to a signal because a modal's state lives in the browser's top layer |

**Shards / partial updates.** A Topcoat `#[shard("/path")]` is a server fragment whose parameters are `$(signal.get())` expressions; the runtime re-fetches and morphs it when the signals change. Datastar equivalent: give the region a stable `id`; trigger `@get('/path')` from `data-on:*` (or `data-effect="$sel && @get('/users/' + $sel + '/drawer')"` for "refetch when the signal changes"); the signals are sent automatically (`?datastar=<json>`); the server replies with `datastar-patch-elements` and the same morph-by-id happens. The drawer body, table rows, paginator, toolbar badge and flash stack are all patched this way (specs give the request and the SSE). Verified end to end against a mock SSE server (inner/append/remove modes, `@get` signal transport, `@post` form + header, `data-init` on patched elements, `datastar-patch-signals`, text/html response with selector headers).

**Forms.** fc-web forms are ordinary `<form method=post>` with redirect-and-flash handlers; that stays exactly as is (mechanism a), including `data-dirty-form` behaviour. Where a page should not reload, `data-on:submit="@post('/x', {contentType: 'form'})"` (or on the button) sends the closest form (no signals) and the server patches the result. CSRF: hidden token for native posts; `headers: {'X-CSRF-Token': ...}` option for actions (fc-web relies on `Origin`/`Sec-Fetch-Site`, which Datastar's fetch also sends).

## What Datastar could not express, and what was done instead

1. **Modal state as a signal.** `<dialog>` modality is imperative (`showModal()`); `data-attr:open` would make it non-modal (no trap, no Escape). Used `data-on:click` with `showModal()`/`close()`. (The HTML invoker commands would be declarative, but see browser support below.)
2. **`commandfor`/`command` support.** MDN BCD (2026-10-06): Chrome 135, Firefox 144, **Safari 26.2**. Not universal, so Datastar handlers are the default; the native form is documented in `dialog.md`. `closedby` (native light dismiss) is Safari-preview only, so mask-click dismissal is `evt.target === el && el.close()`.
3. **Component-scoped signals.** There is none: signals are one global tree. Every stateful instance needs a unique signal name; wrappers must generate them. (Nested names like `tabs.team` are possible but still global.)
4. **Array binding is positional.** `data-bind` on several checkboxes gives one array slot per checkbox (`['', '', 'ops', '']`), not a set of values. The pick list works with `filter(Boolean)`, `includes`, and an initial array of the same shape; documented in `pick-list.md`.
5. **Client-side sorting.** No declarative sort. The kit's client sort is an inline JavaScript expression in the heading's attribute (re-appends rows). Acceptable for one table; otherwise sort on the server (c). The originals sorted on the server.
6. **Popover anchoring.** CSS anchor positioning is not yet universal; `ui.js` computed positions. Replaced by one `data-on:toggle__capture` expression (JavaScript in an attribute) that needs the popover to follow its trigger button in the DOM.
7. **Class conflicts with Datastar.** A `data-class:dark` on an element re-asserts the class when something else toggles it (found when driving the shell iframes' theme from the gallery). Fix: send the value to the owner of the signal (`postMessage` + `data-on:message__window`), never toggle the class externally.
8. **`contentType: 'form'`** only works for elements inside a `<form>` (or with `selector`); an input outside a form gets a runtime error, not a fallback.
9. **Pro features not used** (paid): `data-persist` (sidebar state -> a cookie written by the handler instead), `@intl` (local time -> `toLocaleString()`), `data-match-media` (dark default -> `window.matchMedia` in the initial signal), `data-query-string`, `data-view-transition`.
10. **`aria-expanded`/`aria-*` booleans.** `data-attr` with `true` writes an empty attribute, not `"true"`; use `$x ? 'true' : 'false'`.
11. **Attribute-name case.** HTML lower-cases attribute names, so signal keys are kebab-case in attributes and camelCase in expressions.
12. **Focus management after server patches** (drawer heading focus, announcing inserted errors) is not provided; specs state it as a template responsibility (`role=alert`, `tabindex=-1` + `.focus()` in a `data-init`).
13. **Arrow-key roving** (menus, ARIA tabs) is not provided, as in Topcoat. It could be written as `data-on:keydown` expressions but would be a growing pile of inline JS; left out to stay faithful.

## JavaScript files

None. Behaviour is attributes only. The expressions that are genuinely JavaScript logic are: the table sort handler, the popover positioning, the dirty-form `FormData` comparison, the dropdown outside-click test, and the `postMessage` theme bridge in the gallery. If any grows, move it to a registered Datastar plugin or a `<script type="module">` rather than longer attributes.

## Server-side wrappers: what must be supplied

- **Ids:** unique `id` for every dialog/sheet/menu/popover/tooltip/hover card; `{id}-title`/`{id}-desc` for dialog aria; `for`/`id` pairs for every control; stable patch-target ids (`users-rows`, `flash-stack`, `user-drawer-body`, field message ids).
- **Signal names and initial values:** one unique name per stateful instance (tabs, sidebar, drawer, dirty form, pick list, filter). Initial values rendered server-side as JSON-escaped literals (`data-signals="{...}"`): selected tab, sidebar collapsed (from the cookie), picked array in DOM order, dark theme.
- **CSRF:** hidden input on native forms; `headers: {'X-CSRF-Token': ...}` (or Origin/Sec-Fetch-Site checking) for `@post/@put/@delete`.
- **Escaping:** HTML-escape all text; never place user text in a `data-*` expression (it is code); use `data-*` plain attributes and `el.dataset` instead.
- **Endpoints for (c):** return `text/event-stream` with `datastar-patch-elements`/`-signals`; read signals from `?datastar=` (GET) or the JSON body, forms from the form body.
- **Tailwind:** add the template directories as `@source` in the build so wrapper classes are emitted.
- **Sprite:** serve `icons/lucide-sprite.svg` same-origin.
- **Cookies:** `fc_sidebar=collapsed` (sidebar) if first-paint state is wanted.

## Verification performed

- Built `dist/kit.css` with Tailwind 4.3.2; opened the gallery in system Chrome (headless, Playwright) in light and dark; screenshots in `gallery/screenshots/`.
- Scripted checks (see the report) on dialogs (modal, Tab trap, Escape, focus return, mask dismiss, alert dialog behaviour), sheets (4 sides), dropdown (outside click, Escape, submenu, item close), tooltip and hover card, native popover, tabs, accordion, switch/checkbox/radio, dirty form, list page (filter, empty state, sort, drawer, Escape), toolbar popover positioning, pick list, local time, theme bridge, both shells (desktop collapse, rail, user menu, `<details>` nav, mobile sheet open/close by button, Escape, backdrop). Browser console: no errors or warnings.
- Datastar server round trips verified against a throwaway mock SSE server (not part of the kit).
- Not verified: Firefox and Safari rendering (only Chrome was available), a real server, screen-reader output, touch devices, the `base-select` picker in non-Chromium browsers.

## Go templ port (2026-10-09)

The Go platform (`flowcatalyst-go/internal/ui/kit`) implements the pilot components in templ; it renders all 112 fixtures in `fixtures/` identically (`TestConformance`). Its architecture and conventions are in `flowcatalyst-go/docs/ui/README.md`.

Defects found and fixed here (spec and gallery now agree): four gallery inputs and `input.md` carried two `type` attributes (`type="text"` first, so the email and file inputs rendered as text); `<th>` without `scope="col"` in the gallery and `data-table.md`; gallery skeletons without `aria-hidden`; the gallery's hand-built form field error not linked by `id`/`aria-describedby`; the app shell's page header without the actions wrapper `page-header.md` requires; `select.md` eliding the checkmark data URI; `table-toolbar.md` rendering `value=""`; `_conventions.md` saying DELETE sends signals in the body (Datastar 1.0.4 sends them in the query string, like GET; GET with `contentType: 'form'` appends the form's fields to the query).

Recorded, not changed:
- `filter-select`: the gallery uses `el.form && el.form.requestSubmit()` (its select is outside a form); the spec says `el.form.requestSubmit()`. Implementations follow the spec.
- The gallery's confirm dialog form has `data-on:submit="el.closest('dialog').close()"`: Datastar prevents the default of a submit it handles, so that form never posts. Gallery-only; the spec's native POST is right.
- Gallery screenshots predate the fixes.

Gaps (needed by the platform pilot; none invented silently). Status after Phase 1 step 1 (2026-10-09, "kit decisions"):
1. **Done.** Icon sprite: 86 icons (was 38), from `icons/sprite-icons.txt` by `icons/build-sprite.sh` (pinned lucide-static 1.52.0, sha1-checked; the script reproduces the original 38 symbols byte for byte). Added: the navigation icons (house, server, code, id-card, mail, shield, lock, app-window, globe, layout-grid, building-2, link, history, book-open, log-in, palette, tag, wrench, database, zap, bell, box, send, clock, network) and the CRUD icons (plus, trash-2, ellipsis-vertical, eye, eye-off, ban, pause, play, archive, archive-restore, rotate-ccw, external-link, arrow-left, arrow-right, circle-x, circle-plus, download, upload, save, user-plus, user-minus, star, file). Phase 1 step 4 added menu, sun, moon and monitor (90 icons).
2. **Done.** Row actions: `row-actions.md` (inline icons, popover overflow menu, destructive last after a separator, disabled with a reason), fixtures `row-actions/{inline,inline-buttons,menu,disabled}`; the proposed fixture is gone.
3. **Done.** Table toolbar live updates: the hidden fields, Clear All and the Filters button are one always-present part, `<div id="{formId}-state" class="fc-toolbar-state">` (fixtures `table-toolbar-state/*`); a live answer patches it by id and never the search box or the popover. Live Clear All empties the search and popover fields client-side (by class) and submits, so the server sees an ordinary request. It also fixes a bug the old layout had: the hidden sort fields were never patched, so a search after a live sort click lost the sort.
4. **Done.** `app-shell` narrow screens (< 768px): a sticky top bar with a menu button, the sidebar off canvas with a backdrop, close button and Escape (`$sidebarMobileOpen`), collapse disabled there; no horizontal page scroll at phone width.
5. Missing controls. **Partly done** (Phase 1, dispatch pools CRUD): the create/edit pattern is `form-dialog.md` (server validation as field patches, dirty and busy state, discard question); AutoComplete and the client select are `autocomplete.md` (a datalist the server refreshes); a number input is `input.md` with `type="number"` (section Numbers); `form-section.md` and `detail-field.md` have Props and fixtures; `confirm-dialog.md` has a shared dialog for many records; `drawer.md` has a server-loaded record (`id`, the `drawer-content` part). Phase 1 step 2 (connections, 2026-10-09) read the SPA's connections pages: they need the client select (`autocomplete.md`) and a service-account select, which is `select.md` (section "Choosing among records": the SPA's filterable `Select` becomes a native select whose label carries name and code); no number input, no multi-select. The SPA's `SecretRefInput` is used only by the identity-provider pages, not by connections, so it was **not** built here (it belongs with that area). Still open: multi-select with search, password strength, secret-reference input, permission-denied modal.
6. **Done, with one limit.** Theme cookies (`fc_theme` = the choice, `fc_scheme` = the system scheme the page last saw) rendered as `<html class="dark">` by the server, a theme picker in the user menu, `system` still follows the OS (`app-shell.md`, Theme). Without an inline script, system mode's first page in a browser (and the first after the OS scheme changes) still renders light until Datastar runs.
7. **Done.** Non-2xx Datastar answers: `_conventions.md`, "Answering a (c) request" (handled failures are 200 + patches because Datastar 1.0.4 drops non-200 bodies; mutations use `retryMaxCount: 0`) and the flash stack's fallback (refused / failed / offline; 401 left to the application).
8. **Done.** Flash after a native post/redirect/get: a fixed key in a short-lived cookie, mapped to text on the server, rendered in the flash stack's slot (`_conventions.md`, `flash-stack.md`); the Go platform implements it (`shell.SetFlash`).

The gallery has the new pieces (row actions table, toolbar state, flash messages, the stack with two buttons that trigger the fallback); its screenshots were not retaken.
