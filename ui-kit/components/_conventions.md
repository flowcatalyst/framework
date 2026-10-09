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
- `@post(..., {contentType: 'form'})` sends the closest `<form>` (so the element must be inside one, or pass `selector`), validates it, and sends **no signals**. Without `contentType: 'form'` all non-`_` signals are sent: as JSON for POST/PUT/PATCH, as the `datastar` query parameter (`?datastar={"q":"abc"}`) for GET **and DELETE** (Datastar 1.0.4 sends a body only for methods other than GET and DELETE; checked in the bundle and in the Go SDK's `ReadSignals`). With `contentType: 'form'` a GET appends the form's fields to the query string, so a filter form can drive `@get` with plain query parameters.
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

## Answering a (c) request: success, expected failure, unexpected failure

Datastar 1.0.4 applies the body of a response **only when its status is 200** (checked in the bundle: any other status ends the request after the `datastar-fetch` `error` event; the body is never read, whatever its content type). So the status tells the client only "your patches are here" or "something outside the handler went wrong":

| Outcome | Status | Body | What the user sees |
|---|---|---|---|
| Success | 200 | SSE patches: the changed region(s) by id, and usually a flash message appended to `#flash-stack` (`flash-stack.md`) | the result + "Saved." |
| Expected failure the handler detects: validation, a business rule, not found, a permission check, a conflict | **200** | SSE patches that show the error where it belongs: the form re-rendered with field errors (`form-field.md`: the message `<small id="{for}-msg">`, `aria-invalid`, `aria-describedby`) or the dialog's error region; otherwise an error flash message | the message, next to the field when there is one |
| Unexpected failure: a guard refused the request (CSRF 403, no session 401, no access 403), the handler crashed (500), a proxy or timeout (502/503/504), an unknown URL (404) | non-200 | anything (ignored) | the flash stack's generic fallback (refused / failed), or the application's session-expired handling for 401 |
| Network failure | none | - | the flash stack's fallback (offline) |

Rules:

- **Never an empty body for a handled request.** A Datastar request that reaches the handler always gets at least one patch: if nothing on the page changes, append a flash message saying what happened.
- Log the real outcome (e.g. "403 permission denied") server-side; the 200 is a transport detail.
- An error for an action started in a **dialog** goes into the dialog (it covers the page); close the dialog only on success.
- **Mutations disable network retries**: `@post('/x', {retryMaxCount: 0})`. Datastar retries a request whose connection failed up to 10 times (1s, doubling, up to 30s), which for a POST that reached the server may apply it twice; with `retryMaxCount: 0` a network failure ends at once in `retries-failed` (the offline message, which says the change may not have been saved). GETs keep the default retries.
- A native form post (mechanism a) follows post/redirect/get: 303 to the result page with a flash (cookie, read once) on success; the form page re-rendered with errors (status 422) on validation failure.

## Props

Every spec has a **Props** table: the component's parameters with language-neutral names, so every template implementation (templ in the Go platform, JTE in the framework) exposes the same signature and the fixtures in `fixtures/` can drive all of them.

- Names are camelCase. Each implementation maps them to its own idiom (Go: an exported field `Variant` with the JSON tag `variant`).
- Types: `string`, `boolean`, `number`, `enum(a|b)` (first value or the listed default applies when absent), `list<T>`, `{...}` (an object), `attrs` (extra attributes passed through to the part the row names), `slot` (caller markup; `default` is the unnamed slot).
- Absent = the default. Booleans default to false, so a prop that is on by default is named for its opposite (`persistent` turns light dismiss *off*).
- Values that end up in a Datastar expression (ids, signal names) must be validated by the implementation (`[A-Za-z][A-Za-z0-9_-]*` for ids, camelCase for signals); they never carry user data.

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

Lucide, inline SVG, 24x24 viewBox, `stroke="currentColor"`. The kit ships `icons/lucide-sprite.svg` (86 symbols, listed in `icons/sprite-icons.txt`; add a name there and run `icons/build-sprite.sh`, which takes the paths from the pinned lucide-static 1.52.0 package). Reference with `<svg width="1em" height="1em" class="size-4" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg>` (the sprite must be same-origin and served over http(s)), or inline the `<path>`s. Decorative icons are `aria-hidden="true"`; a lone icon gets `role="img"` + `aria-label`.

## Vocabulary

- **Parameter**: a value the template accepts (variant, size, id, text, state).
- **Slot**: a region of caller-provided markup.
- `{id}`, `{name}`: placeholders the template fills. Ids must be unique per page.
- "Default" in an option table = what applies when the caller gives nothing.
