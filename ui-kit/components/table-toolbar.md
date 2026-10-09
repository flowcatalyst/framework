# Table toolbar

**Source:** hand-built (`list::table_toolbar`); behaviour of the SPA's `FcTableToolbar`. The state part and the live Clear All are new in the kit (2026-10-09)  
**Mechanism:** (a) native form + `popover`; (b) one Datastar expression positions the popover, one clears the fields; (c) optional live updates  

## Purpose

The toolbar above a list: quick search (left); result-size select, refresh, Clear All and a Filters button with an active-count badge (right). The toolbar is the list's GET form, so the URL is the list state.

## Structure

```html
<form id="users-list" method="get" action="/users" class="fc-table-toolbar">
  <div class="fc-toolbar-start">
      <div class="fc-input-icon fc-toolbar-search">
        <svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-search"/></svg>
        <input type="search" name="q" class="fc-input" placeholder="Search users" aria-label="Search users">
      </div>
  </div>
  <div class="fc-toolbar-end">
    <select name="size" class="fc-select w-24" title="Result size" aria-label="Result size" data-on:change="el.form.requestSubmit()"><option value="50">50</option><option value="100">100</option><option value="200" selected>200</option><option value="500">500</option><option value="1000">1000</option></select>
    <button type="submit" class="fc-icon-btn" title="Refresh" aria-label="Refresh"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-refresh-cw"/></svg></button>
    <div id="users-list-state" class="fc-toolbar-state">
      <input type="hidden" name="sort" value="name"><input type="hidden" name="dir" value="desc">
      <a href="/users" class="fc-btn fc-btn-text"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-funnel-x"/></svg> Clear All</a>
      <button type="button" class="fc-btn fc-btn-primary-outline" popovertarget="users-list-filters"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-funnel"/></svg> Filters <span class="fc-badge">2</span></button>
    </div>
    <div id="users-list-filters" popover class="fc-filter-popover" data-on:toggle__capture="evt.newState === 'open' && (el.style.top = (el.previousElementSibling.getBoundingClientRect().bottom + 6) + 'px'); evt.newState === 'open' && (el.style.left = Math.max(16, el.previousElementSibling.getBoundingClientRect().right - el.offsetWidth) + 'px')">
      <div class="fc-filter-popover-body">
<div class="fc-form-field">
  <label class="fc-field-label" for="filter-scope">Type</label>
  <select id="filter-scope" name="scope" class="fc-select"><option value="">All types</option><option value="ANCHOR">Anchor</option><option value="CLIENT">Client</option></select>
</div>
      </div>
    </div>
  </div>
</form>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Form | `<form id method="get" action class="fc-table-toolbar">` | flex, space-between, wrap, bottom border |  |
| Search | `.fc-input-icon.fc-toolbar-search > input.fc-input[type=search][name=q]` | width 260px | `show_search` |
| Result size | `<select name="size" class="fc-select w-24">` | - | Firehose lists only: 50/100/200/500/1000 |
| Refresh | `<button type="submit" class="fc-icon-btn" aria-label="Refresh">` |  |  |
| **State** | `<div id="{form_id}-state" class="fc-toolbar-state">` | flex, 8px gap; `display: none` while it holds only hidden inputs | Everything in the toolbar the **server** renders from the list state, in one patch target: the hidden fields, Clear All, the Filters button |
| Hidden fields | `<input type="hidden">` (first children of the state) |  | e.g. the sort and direction, so a live request keeps them |
| Clear All | `<a class="fc-btn fc-btn-text">` |  | Only when a search or filter is active; links to the list without them |
| Filters button | `<button type="button" class="fc-btn fc-btn-primary-outline" popovertarget="{form_id}-filters">` |  | Last child of the state; contains the `.fc-badge` count when > 0 |
| Popover | `<div id="{form_id}-filters" popover class="fc-filter-popover">` | `position: fixed; width: min(360px, 100vw - 32px); padding 16px; border; shadow` | Immediately after the state (its positioning reads `previousElementSibling`); slot: the filter fields (`.fc-filter-popover-body` flex column, 14px gap) |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### state

| State | Markup |
|---|---|
| filters active | Clear All link rendered + `<span class="fc-badge">N</span>` in the Filters button |
| search active, no filters | Clear All link rendered, no badge |
| nothing active | neither (the state part is still rendered, possibly empty) |
| search hidden | omit the search wrapper (`show_search=false`) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/table-toolbar` and `fixtures/table-toolbar-state`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | form id; the state is `{id}-state`, the popover `{id}-filters` |
| `action` | string | - | GET target |
| `searchPlaceholder` | string | - |  |
| `searchValue` | string | - | omitted when empty |
| `hideSearch` | boolean | false |  |
| `size` | {options: list<number>, selected: number} | - | result-size select (firehose lists) |
| `hideRefresh` | boolean | false |  |
| `clearHref` | string | - | renders Clear All (only while a search or filter is active): the list's URL without them (keep the sort) |
| `clearAttrs` | attrs | - | on Clear All; for a live toolbar: `{"data-on:click__prevent": <Clear filters expression>}` (Behaviour) |
| `activeFilterCount` | number | 0 | badge in the Filters button when > 0 |
| `hidden` | list<{name, value}> | [] | hidden inputs (first children of the state part) |
| `searchAttrs` | attrs | - | on the search input |
| `formAttrs` | attrs | - | on the form |
| `filters` | slot | - | popover body; omitted → no Filters button |

**table-toolbar-state** (the state part on its own, for a live update): `formId` (string), `hidden`, `clearHref`, `clearAttrs`, `activeFilterCount` (as above) and `filters` (boolean: render the Filters button; true when the toolbar has a filters slot). The toolbar renders it with the same values, so the full page and a patch produce the same markup.

## Accessibility

- The Filters button is a `popovertarget` invoker (Safari 17+, Chrome 114+, Firefox 125+): native light dismiss and Escape; focus returns to the button.
- Search input has `aria-label`; the refresh button has `aria-label`/`title`.
- Labels inside the popover are real `<label for>`.
- Live Clear All moves focus to the search box (the link it was on disappears with the patch).

## Behaviour

**Popover position (b):** a native popover is centred by the browser. The popover opens under the Filters button, right-aligned to it, with one attribute on the popover `<div>`; it must be the element immediately after the state part, whose right and bottom edges are the Filters button's (the button is the state's last child):

`data-on:toggle__capture="evt.newState === 'open' && (el.style.top = (el.previousElementSibling.getBoundingClientRect().bottom + 6) + 'px'); evt.newState === 'open' && (el.style.left = Math.max(16, el.previousElementSibling.getBoundingClientRect().right - el.offsetWidth) + 'px')"`

This replaces fc-web's `ui.js` `data-anchor-popover` handler. `__capture` is needed because `toggle` does not bubble. Verified: the popover opens 6px under the button, right edges aligned, Escape closes.

**Native submit (a):** Enter in the search box and every filter's `data-on:change="el.form.requestSubmit()"` submit the GET form; page is not in the form, so any change resets to page 1. Clear All is a link to the unfiltered list.

**Live (c):** the form drives one request, `@get('/users', {contentType: 'form'})` (GET with the form's fields as query parameters, so the server reads the same parameters as for a full page), from `data-on:submit` on the form, `data-on:input__debounce.300ms` on the search box and `data-on:change` on each filter. The server answers with SSE patches of:

1. the rows (or the table, to move `aria-sort`) and the paginator or summary;
2. **the state part, by its id `{form_id}-state`** (default `outer` morph): the hidden sort fields, Clear All appearing or disappearing, the badge count;
3. the address bar (replace the URL with the canonical one for the new state).

It never patches the search box (the user may still be typing; a morph would reset the caret or drop keystrokes) or the popover (a morph would drop its inline position while it is open). Sort links and pager links (`@get(el.href)`) get the same answer, so the hidden sort fields follow a sort click.

**Live Clear All (b + c):** Clear All empties the user-editable fields itself and submits the form, so the server sees an ordinary request with no search or filters and answers as above (the state part loses Clear All and the badge; the rows and URL follow). With Datastar, `clearAttrs` is

`data-on:click__prevent="const f = el.closest('form'); f.querySelectorAll('.fc-toolbar-search [name], .fc-filter-popover [name]').forEach(c => c.type === 'checkbox' || c.type === 'radio' ? c.checked = false : c.value = ''); f.querySelector('.fc-toolbar-search input')?.focus(); f.requestSubmit()"`

It clears only the search box and the fields inside the filters popover (not the result size or the hidden sort), by class, so it needs no ids. Without Datastar the link navigates to `clearHref`. A list whose fields are bound to signals (`data-bind`) resets those signals in its handler or the server sends `datastar-patch-signals`; the form-driven pattern above has no filter signals.

## Notes for a template author

- Parameters: form id, action, search placeholder/value, active filter count, has-active flag, size select, show_refresh, clear href (+ live attrs), hidden fields.
- Slot: filter fields.
- Render the state part from the same view model on the full page and in the live answer (one function), so they cannot drift.
- The newer users-section variant replaces the popover with the themed `dropdown-menu` (`<details>`) holding `field` + `select` filters and a roles checkbox list; both are valid.
- Differs from fc-web: positioning handler is a Datastar expression, not `ui.js`; hidden fields, Clear All and the Filters button are wrapped in the state part (new).
