# Multi-select

**Source:** new (Go platform port; replaces PrimeVue `MultiSelect` in filter popovers and forms)  
**Mechanism:** (a) native `<details>` + checkboxes (works without Datastar); (b) option search; (c) live summary and options patched by the server  

## Purpose

Choose any number of values from a list: a closed control that says what is chosen ("All applications", "orders, billing", "3 apps selected") and opens into a checklist, optionally with a search box that narrows the list. The checkboxes share one `name`, so a GET form puts every chosen value in the URL (`?app=orders&app=billing`) and a POST form sends them all; no signal carries the selection.

## Structure

```html
<div id="filter-app-field" class="fc-form-field">
  <span id="filter-app-label" class="fc-field-label">Applications</span>
  <details class="fc-multiselect" data-placeholder="All Applications" data-max-labels="2" data-many-label="{0} selected" data-on:change="const c = [...el.querySelectorAll(&#x27;.fc-multiselect-option input:checked&#x27;)].map(i =&gt; i.nextElementSibling.textContent); el.querySelector(&#x27;.fc-multiselect-summary&#x27;).textContent = c.length === 0 ? el.dataset.placeholder : c.length &lt;= +el.dataset.maxLabels ? c.join(&#x27;, &#x27;) : el.dataset.manyLabel.replace(&#x27;{0}&#x27;, c.length)">
    <summary class="fc-select fc-multiselect-trigger" aria-labelledby="filter-app-label filter-app-summary"><span id="filter-app-summary" class="fc-multiselect-summary">orders, billing</span></summary>
    <div class="fc-multiselect-panel" data-signals:filter-app-q="''">
      <input type="search" class="fc-input fc-multiselect-search" placeholder="Search" aria-label="Search Applications" autocomplete="off" data-bind:filter-app-q>
      <div id="filter-app-options" class="fc-multiselect-options" role="group" aria-labelledby="filter-app-label">
        <label class="fc-multiselect-option" data-filter-text="billing" data-show="!$filterAppQ.trim() || el.dataset.filterText.includes($filterAppQ.trim().toLowerCase())"><input type="checkbox" name="app" value="billing" checked><span>billing</span></label>
        <label class="fc-multiselect-option" data-filter-text="orders" data-show="!$filterAppQ.trim() || el.dataset.filterText.includes($filterAppQ.trim().toLowerCase())"><input type="checkbox" name="app" value="orders" checked><span>orders</span></label>
        <label class="fc-multiselect-option" data-filter-text="platform" data-show="!$filterAppQ.trim() || el.dataset.filterText.includes($filterAppQ.trim().toLowerCase())"><input type="checkbox" name="app" value="platform"><span>platform</span></label>
      </div>
    </div>
  </details>
</div>
```

Without `search` the panel has no search input, no signal and no `data-show`/`data-filter-text` on the options.

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div id="{id}-field" class="fc-form-field">` | as `filter-select.md` | |
| Label | `<span id="{id}-label" class="fc-field-label">` | | names the trigger (with the summary) and the option group |
| Disclosure | `<details class="fc-multiselect" data-placeholder data-max-labels data-many-label data-on:change>` | `open` when rendered open | native open/close (a); recomputes the summary on every change (b) |
| Trigger | `<summary class="fc-select fc-multiselect-trigger" aria-labelledby="{id}-label {id}-summary">` | the select's chevron look | |
| Summary | `<span id="{id}-summary" class="fc-multiselect-summary">` | ellipsis | **part `multi-select-summary`**: the text of the selection; a live list patches it by id |
| Panel | `<div class="fc-multiselect-panel">` | inline below the trigger (it grows the popover or form it sits in) | declares the search signal |
| Search | `<input type="search" class="fc-input fc-multiselect-search">` | no `name`: never submitted | `data-bind:{signal}` |
| Options | `<div id="{id}-options" class="fc-multiselect-options" role="group">` | max-height 14rem, scrolls | **part `multi-select-options`**: a dependent list (subdomains of the chosen applications) is patched by id |
| Option | `<label class="fc-multiselect-option">` + native checkbox `name="{name}" value` + `<span>` label | `accent-color: var(--primary)` | `data-filter-text` = the label, plus the value when it differs from the label, lower-cased |
| Empty | `<p class="fc-multiselect-empty">` | | shown instead of the options when there are none (`emptyText`) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/multi-select`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required; the parts derive from it (`{id}-field`, `{id}-label`, `{id}-summary`, `{id}-options`) |
| `name` | string | - | the checkboxes' name (repeated in the query or body) |
| `label` | string | - | |
| `options` | list<{value, label}> | [] | |
| `selected` | list<string> | [] | values checked when rendered |
| `placeholder` | string | - | the summary when nothing is chosen (`All Applications`) |
| `maxLabels` | number | 2 | up to this many chosen labels are listed, comma-separated |
| `manyLabel` | string | `{0} selected` | the summary above `maxLabels`; `{0}` is the count (`{0} apps selected`) |
| `search` | boolean | false | adds the search box |
| `signal` | string | - | required with `search`: the search text's signal (camelCase, unique per page) |
| `emptyText` | string | `No options` | |
| `open` | boolean | false | render the disclosure open |
| `optionAttrs` | attrs | - | on every checkbox (a live filter: `{"data-on:change": "@get('/ui/x', {contentType: 'form'})"}`) |
| `describedBy` | string | - | `aria-describedby` on the option group (help or error text in a form) |
| `class` | string | - | on the root |

The summary text is computed by the template from `options`, `selected`, `placeholder`, `maxLabels` and `manyLabel`, in option order.

## Accessibility

- The trigger is a native `<summary>` (Enter/Space toggle it); its name is the label plus the current summary.
- The options are real checkboxes inside `<label>`s, in a `role="group"` named by the label: keyboard and screen readers get native checkbox behaviour; no `listbox` emulation, no arrow-key roving (as `pick-list.md`).
- The search box has an `aria-label`; filtering hides options with `display: none`, which removes them from the accessibility tree.
- Without Datastar every option is visible and the form still submits the checked values.

## Behaviour

**(a)** `<details>` opens inline (no positioning code, so it works inside the table toolbar's filters popover and at phone width). Checked boxes are submitted with the form.

**(b)** Summary: a change of any checkbox recomputes the summary in the browser from the checked options' labels and the `data-placeholder`, `data-max-labels` and `data-many-label` attributes (the same rule as the server's; the props are data attributes, never code), so a form shows the choice at once. In a live list the server's patched summary then confirms it.

**(b)** Search: the input binds `{signal}`; each option has `data-show="!${signal}.trim() || el.dataset.filterText.includes(${signal}.trim().toLowerCase())"` (user text is a signal value and a data attribute, never code).

**(c)** In a live filter form (`table-toolbar.md`), each checkbox carries the form's live `@get` on `change`. The answer patches the **summary part** (the text the server computed for the new selection) and, for dependent lists, the **options part** of the dependent multi-select (with its pruned selection); it never re-renders the disclosure, so it stays open and the search text is kept. The table toolbar's live Clear All unchecks the boxes (they are `[name]` controls inside `.fc-filter-popover`) and submits; the answer then patches every summary back to its placeholder.

## Notes for a template author

- Render the summary, the options list and the whole control from the same props, so a patch of a part matches the page.
- The values are user data: they go into `value`, `data-filter-text` and text, all escaped; never into an expression.
- The SPA's `MultiSelect` options `showClear`, `maxSelectedLabels` and `selectedItemsLabel` map to Clear All (toolbar), `maxLabels` and `manyLabel`.
