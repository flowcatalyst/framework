# Table toolbar

**Source:** hand-built (`list::table_toolbar`); behaviour of the SPA's `FcTableToolbar`  
**Mechanism:** (a) native form + `popover`; (b) one Datastar expression positions the popover; (c) optional live updates  

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
    <a href="/users" class="fc-btn fc-btn-text"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-funnel-x"/></svg> Clear All</a>
    <button type="button" class="fc-btn fc-btn-primary-outline" popovertarget="users-list-filters"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-funnel"/></svg> Filters <span class="fc-badge">2</span></button>
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
| Form | `<form id method="get" action class="fc-table-toolbar">` | flex, space-between, wrap, bottom border | Hidden inputs (e.g. page size) are the form's slot |
| Search | `.fc-input-icon.fc-toolbar-search > input.fc-input[type=search][name=q]` | width 260px | `show_search` |
| Result size | `<select name="size" class="fc-select w-24">` | - | Firehose lists only: 50/100/200/500/1000 |
| Refresh | `<button type="submit" class="fc-icon-btn" aria-label="Refresh">` |  |  |
| Clear All | `<a class="fc-btn fc-btn-text">` |  | Only when filters are active; links to the unfiltered list |
| Filters button | `<button type="button" class="fc-btn fc-btn-primary-outline" popovertarget="{form_id}-filters">` |  | Contains the `.fc-badge` count when > 0 |
| Popover | `<div id="{form_id}-filters" popover class="fc-filter-popover">` | `position: fixed; width: min(360px, 100vw - 32px); padding 16px; border; shadow` | Slot: the filter fields (`.fc-filter-popover-body` flex column, 14px gap) |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### state

| State | Markup |
|---|---|
| filters active | Clear All link rendered + `<span class="fc-badge">N</span>` in the Filters button |
| no filters | neither |
| search hidden | omit the search wrapper (`show_search=false`) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/table-toolbar`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | form id; the popover is `{id}-filters` |
| `action` | string | - | GET target |
| `searchPlaceholder` | string | - |  |
| `searchValue` | string | - | omitted when empty |
| `hideSearch` | boolean | false |  |
| `size` | {options: list<number>, selected: number} | - | result-size select (firehose lists) |
| `hideRefresh` | boolean | false |  |
| `clearHref` | string | - | renders Clear All (only while filters are active) |
| `activeFilterCount` | number | 0 | badge in the Filters button when > 0 |
| `hidden` | list<{name, value}> | [] | hidden inputs (first children of the form) |
| `searchAttrs` | attrs | - | on the search input |
| `formAttrs` | attrs | - | on the form |
| `filters` | slot | - | popover body; omitted → no Filters button |

## Accessibility

- The Filters button is a `popovertarget` invoker (Safari 17+, Chrome 114+, Firefox 125+): native light dismiss and Escape; focus returns to the button.
- Search input has `aria-label`; the refresh button has `aria-label`/`title`.
- Labels inside the popover are real `<label for>`.

## Behaviour

**Popover position (b):** a native popover is centred by the browser. The popover opens under its button, right-aligned to it, with one attribute on the popover `<div>` (it must be the element immediately after its trigger button):

`data-on:toggle__capture="evt.newState === 'open' && (el.style.top = (el.previousElementSibling.getBoundingClientRect().bottom + 6) + 'px'); evt.newState === 'open' && (el.style.left = Math.max(16, el.previousElementSibling.getBoundingClientRect().right - el.offsetWidth) + 'px')"`

This replaces fc-web's `ui.js` `data-anchor-popover` handler. `__capture` is needed because `toggle` does not bubble. Verified: the popover opens 6px under the button, right edges aligned, Escape closes.

**Native submit (a):** Enter in the search box and every filter's `data-on:change="el.form.requestSubmit()"` submit the GET form; page is not in the form, so any change resets to page 1.

**Live (c):** bind the search box (`data-bind:q`) and use `data-on:input__debounce.300ms="@get('/users')"`; each filter `data-on:change="@get('/users')"`. Datastar sends the signals as `?datastar=<json>`; answer with SSE `datastar-patch-elements` for `#users-rows` (mode inner), the paginator, and the toolbar (to refresh the badge and Clear All). For a filter that keeps the popover open across the update, patch only the rows and the badge, not the popover (a morph keeps open popovers open because the `popover` state is not an attribute).

## Notes for a template author

- Parameters: form id, action, search placeholder/value, active filter count, has-active flag, size select, show_refresh, clear href, hidden fields.
- Slot: filter fields.
- The newer users-section variant replaces the popover with the themed `dropdown-menu` (`<details>`) holding `field` + `select` filters and a roles checkbox list; both are valid.
- Differs from fc-web: positioning handler is a Datastar expression, not `ui.js`.
