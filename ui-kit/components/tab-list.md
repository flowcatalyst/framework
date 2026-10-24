# Tab list

**Source:** new (Go platform port; replaces PrimeVue `Tabs` in drawers and detail pages; `tabs.md` is the plain-link variant)  
**Mechanism:** (b) Datastar signal + keyboard handler; (a) real `?tab=` links without Datastar  

## Purpose

The ARIA tab pattern: a `tablist` of `tab`s, each controlling a `tabpanel`, with arrow-key navigation and the active tab kept in the URL (`?tab=attempts`), so a reload or a shared link opens the same tab. Use it where keyboard and screen-reader users must get real tabs (drawers, detail pages); `tabs.md` stays for plain link-style switching.

## Structure

```html
<div class="flex flex-col gap-4" data-signals:dj-tab="&#39;overview&#39;"><div role="tablist" aria-label="Dispatch job sections" class="inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1" data-on:keydown="const t = [...el.querySelectorAll(&#39;[role=tab]&#39;)]; const i = t.indexOf(document.activeElement); const k = evt.key; const n = k === &#39;ArrowRight&#39; ? (i + 1) % t.length : k === &#39;ArrowLeft&#39; ? (i - 1 + t.length) % t.length : k === &#39;Home&#39; ? 0 : k === &#39;End&#39; ? t.length - 1 : -1; if (i &gt;= 0 &amp;&amp; n &gt;= 0) { evt.preventDefault(); t[n].focus(); t[n].click() }"><a role="tab" id="dj-tab-overview" href="?tab=overview" aria-controls="dj-panel-overview" aria-selected="true" tabindex="0" class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-selected:bg-foreground/10 aria-selected:text-foreground aria-selected:hover:bg-foreground/10" data-attr:aria-selected="$djTab === &#39;overview&#39; ? &#39;true&#39; : &#39;false&#39;" data-attr:tabindex="$djTab === &#39;overview&#39; ? 0 : -1" data-on:click__prevent="$djTab = &#39;overview&#39;; const u = new URL(location.href); u.searchParams.set(&#39;tab&#39;, &#39;overview&#39;); history.replaceState(null, &#39;&#39;, u)">Overview</a><a role="tab" id="dj-tab-attempts" href="?tab=attempts" aria-controls="dj-panel-attempts" aria-selected="false" tabindex="-1" class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-selected:bg-foreground/10 aria-selected:text-foreground aria-selected:hover:bg-foreground/10" data-attr:aria-selected="$djTab === &#39;attempts&#39; ? &#39;true&#39; : &#39;false&#39;" data-attr:tabindex="$djTab === &#39;attempts&#39; ? 0 : -1" data-on:click__prevent="$djTab = &#39;attempts&#39;; const u = new URL(location.href); u.searchParams.set(&#39;tab&#39;, &#39;attempts&#39;); history.replaceState(null, &#39;&#39;, u)">Attempts</a><a role="tab" id="dj-tab-payload" href="?tab=payload" aria-controls="dj-panel-payload" aria-selected="false" tabindex="-1" class="inline-flex shrink-0 cursor-pointer items-center justify-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium whitespace-nowrap transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background text-muted-foreground hover:bg-foreground/5 hover:text-foreground aria-selected:bg-foreground/10 aria-selected:text-foreground aria-selected:hover:bg-foreground/10" data-attr:aria-selected="$djTab === &#39;payload&#39; ? &#39;true&#39; : &#39;false&#39;" data-attr:tabindex="$djTab === &#39;payload&#39; ? 0 : -1" data-on:click__prevent="$djTab = &#39;payload&#39;; const u = new URL(location.href); u.searchParams.set(&#39;tab&#39;, &#39;payload&#39;); history.replaceState(null, &#39;&#39;, u)">Payload</a></div><div role="tabpanel" id="dj-panel-overview" aria-labelledby="dj-tab-overview" tabindex="0" data-show="$djTab === &#39;overview&#39;"><p class="text-sm">Overview panel.</p></div><div role="tabpanel" id="dj-panel-attempts" aria-labelledby="dj-tab-attempts" tabindex="0" data-show="$djTab === &#39;attempts&#39;" style="display: none"><p class="text-sm">Attempts panel.</p></div><div role="tabpanel" id="dj-panel-payload" aria-labelledby="dj-tab-payload" tabindex="0" data-show="$djTab === &#39;payload&#39;" style="display: none"><p class="text-sm">Payload panel.</p></div></div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div>` | `flex flex-col gap-4` + `data-signals:{kebab-signal}="'{selected}'"` | declares the selected-tab signal |
| Tablist | `<div role="tablist" aria-label="{label}">` | as `tabs.md`'s list + `data-on:keydown` (arrow keys) | |
| Tab | `<a role="tab" id="{id}-tab-{value}" href="?{param}={value}" aria-controls aria-selected tabindex>` | the `tabs.md` trigger classes with the selected look on `aria-selected` | roving tabindex: `0` on the selected tab, `-1` on the others |
| Panel | `<div role="tabpanel" id="{id}-panel-{value}" aria-labelledby="{id}-tab-{value}" tabindex="0">` | `data-show="$sig === 'v'"`; `style="display: none"` on every panel but the selected | |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/tab-list`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required; the tab and panel ids derive from it |
| `signal` | string | - | required; camelCase, unique per page |
| `label` | string | - | the tablist's `aria-label` |
| `tabs` | list<{value, label}> | [] | `value` is an id fragment (`[A-Za-z][A-Za-z0-9_-]*`, validated: it is inside an expression) |
| `selected` | string | first value | the initial tab; the server sets it from the query parameter (an unknown value is the caller's to reject) |
| `param` | string | `tab` | the query parameter; validated like an id |
| `listClass` | string | - | appended to the tablist's classes |
| `class` | string | - | on the root |

Slots: one per tab, named by its `value` (the panel's content).

## Accessibility

- `role="tablist"` / `tab` / `tabpanel` with `aria-controls` and `aria-labelledby`; `aria-selected` follows the signal.
- Roving tabindex: only the selected tab is in the Tab order; Tab moves into the panel.
- Keys on the tablist: Right/Left move to the next/previous tab (wrapping), Home/End to the first/last. Moving focus selects the tab (automatic activation), which suits panels that are already in the page.
- Panels are focusable (`tabindex="0"`) so a panel with no focusable content can still be reached.

## Behaviour

**(b)** Clicking a tab sets the signal and writes `?{param}={value}` to the address bar with `history.replaceState` (other query parameters are kept); no navigation, no history entry per tab. `data-attr:aria-selected`, `data-attr:tabindex` and `data-show` read the signal.

**(a)** Without Datastar the tabs are ordinary links (`?tab=value`): the server renders the page with that tab as `selected`. A deep link works the same way, so the server always reads the parameter and passes it as `selected`.

**(c)** For a lazy panel, add `@get(...)` to a tab's click through your own page code (the panel id is the patch target).

## Notes for a template author

- Signals are global to the page: one per tab list (`djTab`).
- Tab values and `param` go into expressions: validate them; labels are text and are escaped.
- Only the selected panel needs content on first render if the others load lazily.
