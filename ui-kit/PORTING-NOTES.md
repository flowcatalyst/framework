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
| flash cookie plumbing (`set_flash`/`take_flash`) | `ui/flash.rs` | NP | - | server-side concern (cookie set on redirect, read once); the markup is ported |

**Counts:** 31 P, 22 PD, 2 NP (the two NP rows are server/page concerns, not components). 53 component specs.

Global differences (apply to every component):

- **Dark theme is new.** fc-web has light values only; `tokens.css` adds a `.dark` set (slate/emerald) following Topcoat's neutral theme structure. Colours that fc-web wrote as raw hex (`#f8fafc`, `#475569`, `#fff`, ...) in the `.fc-*` CSS were replaced by `--fc-*`/token variables with the same light values (list in `tokens.css`), plus a small `.dark` override block.
- `.tc-theme` (a scope in fc-web) is folded into `@theme`, as fc-web's own doc says it should be once the older sections are gone.
- Topcoat's `icon()` (Iconify at build time) -> inline Lucide SVG via an SVG sprite.
- Class strings are the Rust source's, concatenated (`\` continuations joined). The gallery and specs are generated from one set of constants.
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
