# Conventions (read first)

Every component spec in this folder is language-neutral: it gives complete HTML, the parts and class lists, the options, accessibility requirements, behaviour and notes for whoever writes the server template (JTE, Rust, TypeScript, ...). The markup and class strings are authoritative; the specs and `gallery/` were generated from the same class constants, so they cannot disagree.

Targets: **Datastar 1.0.4** (pinned; the attribute syntax below is that release's) and **Tailwind CSS 4.3.2**.

## Mechanisms

Each spec says which mechanism provides each behaviour.

| Mark | Meaning | Needs |
|---|---|---|
| (a) | The browser: `<details>`, `<dialog>`, `popover`, native inputs and forms, links, CSS `:hover`/`:focus-within`/`:has()`. | Nothing. Works without Datastar. |
| (b) | Datastar attributes only, client-side: signals, `data-show`, `data-attr`, `data-class`, `data-bind`, `data-on`. | The Datastar script. No server round trip. |
| (c) | A Datastar backend action (`@get`, `@post`, ...): the server replies with `text/event-stream` and patches the page. | A server endpoint per action. |

Prefer (a). Use (b) only for state the browser does not hold (selected tab, sidebar collapse, drawer record, client filter, dirty form). Use (c) for anything that needs data the page does not have.

## Datastar 1.0.4 syntax used here

| Need | Attribute |
|---|---|
| declare signals | `data-signals:my-signal="'x'"` (key kebab-case -> `$mySignal`) or `data-signals="{mySignal: 'x', other: 1}"` |
| two-way bind | `data-bind:my-signal` on `input`/`select`/`textarea`; several checkboxes with `value` bind to one array signal (positional, see `pick-list.md`) |
| text | `data-text="$mySignal"` |
| show/hide | `data-show="$x === 'a'"` (toggles `display`; also put `style="display: none"` on elements that start hidden, to avoid a flash before the script loads) |
| attribute | `data-attr:aria-current="$x === 'a' && 'page'"` (`false`/`null` removes the attribute; `true` sets an EMPTY attribute (`aria-expanded=""`), so for ARIA states write `$x ? 'true' : 'false'`; verified) |
| class | `data-class:dark="$dark"` |
| computed | `data-computed:total="$a + $b"` (read-only) |
| effect | `data-effect="..."` (runs now and whenever a read signal changes) |
| on load / on insert | `data-init="..."` (runs when the element is processed, **including when a server patch inserts it**; modifier `__delay.5s`) |
| event | `data-on:click="..."`, `data-on:input__debounce.300ms="..."`, `data-on:keydown__window="..."`, `data-on:click__prevent`, `data-on:toggle__capture`, `data-on:message__window`; `evt` and `el` are available |
| actions | `@get('/u')`, `@post('/u', {contentType: 'form'})`, `@delete('/u')`, options `headers`, `filterSignals`, `requestCancellation` |
| in-flight flag | `data-indicator:fetching` |

Rules that bite:

- **Signals are global to the page.** There is no component scope. Every instance of a component that keeps state needs its own signal name (`tabsTeam`, `dirtyUserEdit`, `pickedRoles`). Wrappers must take the name (or derive it from the component id).
- HTML attribute names are case-insensitive, so write signal keys in kebab-case in attributes (`data-signals:tabs-team`) and camelCase in expressions (`$tabsTeam`).
- Signals starting with `_` are not sent to the server.
- Expressions are JavaScript. Inside a double-quoted attribute use single quotes (`'page'`); never emit a raw `"` in an expression.
- `@post(..., {contentType: 'form'})` sends the closest `<form>` (so the element must be inside one, or pass `selector`), validates it, and sends **no signals**. Without `contentType: 'form'` all non-`_` signals are sent: as JSON for POST/PUT/PATCH/DELETE, as the `datastar` query parameter (`?datastar={"q":"abc"}`) for GET.
- A new request to the same URL+method cancels the one in flight (`requestCancellation: 'auto'`).
- Attribute order matters (`data-indicator:x` before the `data-init` that starts a request).

## Server contract for (c)

Every Datastar request carries `Datastar-Request: true`. The reply is `Content-Type: text/event-stream` (or `text/html` / `application/json`, see the Datastar docs). Events (verified against a mock server in this repo's tests):

```
event: datastar-patch-elements
data: elements <small id="msg" class="fc-field-error">Domain not allowed.</small>

event: datastar-patch-elements
data: selector #users-rows
data: mode inner
data: elements <tr>...</tr>
data: elements <tr>...</tr>

event: datastar-patch-elements
data: selector #row-42
data: mode remove

event: datastar-patch-signals
data: signals {q: 'patched'}
```

- Default mode is `outer`: the element in `elements` **morphs** into the page element with the same `id` (so give every patch target a stable `id`; ids also preserve focus, scroll and open `<details>` state).
- Modes: `outer` (default), `inner`, `replace`, `prepend`, `append`, `before`, `after`, `remove`. `selector` is required for all but `outer`/`replace`.
- A blank line ends each event.
- Multi-line `elements` use one `data: elements ` prefix per line.
- Authorisation, CSRF and validation are the endpoint's job, exactly as for a normal form post.

## Escaping and security

- Escape all text and attribute values from users or the database (HTML escaping).
- **Datastar attribute values are code.** Never put user-controlled text inside a `data-*` expression. Pass data in plain attributes (`data-filter-text`, `data-id`) and read them with `el.dataset`, or emit signal initial values with a JSON encoder (`data-signals='{"x": ...}'` with proper HTML-attribute escaping), never by string concatenation.
- CSRF: native form posts carry a hidden token input. Datastar actions send a header: `@post('/x', {headers: {'X-CSRF-Token': '{token}'}})`, or rely on `Origin`/`Sec-Fetch-Site: same-origin` checks (browsers send them on Datastar's `fetch` too), as fc-web's handlers do.

## Classes and theme

- Classes are Tailwind v4 utilities reading CSS custom properties from `theme/tokens.css` (`bg-primary`, `text-muted-foreground`, `border-border`, `bg-subtle`, ...). Dark mode = `class="dark"` on `<html>` (`@custom-variant dark`).
- Tailwind only emits classes it finds in scanned files. `theme/kit.css` scans `gallery/` and `components/`; a project adds its own template directories with `@source "../path/to/templates"`.
- Classes in a spec are concatenated by the template, **not merged**. To override a base class, use `[&]:...` (e.g. `[&]:max-w-[450px]`), which has higher specificity.
- Hand-built components use the `.fc-*` classes from `theme/fc-components.css` (plain CSS, no scanning needed).

## Icons

Lucide, inline SVG, 24x24 viewBox, `stroke="currentColor"`. The kit ships `icons/lucide-sprite.svg` (38 symbols). Reference with `<svg width="1em" height="1em" class="size-4" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg>` (the sprite must be same-origin and served over http(s)), or inline the `<path>`s. Decorative icons are `aria-hidden="true"`; a lone icon gets `role="img"` + `aria-label`.

## Vocabulary

- **Parameter**: a value the template accepts (variant, size, id, text, state).
- **Slot**: a region of caller-provided markup.
- `{id}`, `{name}`: placeholders the template fills. Ids must be unique per page.
- "Default" in an option table = what applies when the caller gives nothing.
