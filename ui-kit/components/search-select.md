# Search select

**Source:** new (Go platform port; the platform's `SearchSelect`; replaces PrimeVue `Select` with `filter` where the choice is a navigation)  
**Mechanism:** (a) native `<details>` + links (works without Datastar); (b) option search; (c) the choice fetched live  

## Purpose

Choose **one** value from a list that is long enough to need a search box, where choosing is a navigation: a closed control that shows the chosen option (or a placeholder) and opens into a searchable list of links. Each option is an ordinary link built from its value (`?app=orders`), so the control works as plain links without script, and in a live page the same link is fetched instead of followed. Nothing is submitted: there is no input to hold the value, so use `select.md` for a form field and `multi-select.md` for several values.

## Structure

Chosen option, plain links:

```html
<div id="app-field" class="fc-form-field">
<span id="app-label" class="fc-field-label">Application</span>
<details class="fc-multiselect">
<summary class="fc-select fc-multiselect-trigger" aria-labelledby="app-label app-summary"><span id="app-summary" class="fc-multiselect-summary">Beta</span></summary>
<div class="fc-multiselect-panel" data-signals:app-search="&#39;&#39;">
<input type="search" class="fc-input fc-multiselect-search" placeholder="Search" aria-label="Search Application" autocomplete="off" data-bind:app-search>
<div id="app-options" class="fc-multiselect-options" role="listbox" aria-labelledby="app-label">
<a class="fc-multiselect-option" role="option" href="/x?app=a" aria-selected="false" data-filter-text="alpha a" data-show="!$appSearch.trim() || el.dataset.filterText.includes($appSearch.trim().toLowerCase())" data-on:click="const d = el.closest(&#39;details&#39;); d.querySelector(&#39;.fc-multiselect-summary&#39;).textContent = el.textContent; d.querySelectorAll(&#39;[role=option]&#39;).forEach(o =&gt; o.setAttribute(&#39;aria-selected&#39;, o === el)); d.open = false">Alpha</a>
<a class="fc-multiselect-option" role="option" href="/x?app=b" aria-selected="true" data-filter-text="beta b" data-show="!$appSearch.trim() || el.dataset.filterText.includes($appSearch.trim().toLowerCase())" data-on:click="const d = el.closest(&#39;details&#39;); d.querySelector(&#39;.fc-multiselect-summary&#39;).textContent = el.textContent; d.querySelectorAll(&#39;[role=option]&#39;).forEach(o =&gt; o.setAttribute(&#39;aria-selected&#39;, o === el)); d.open = false">Beta</a>
</div>
</div>
</details>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div id="{id}-field" class="fc-form-field">` | as `multi-select.md` | |
| Label | `<span id="{id}-label" class="fc-field-label">` | | names the trigger (with the summary) and the option list |
| Disclosure | `<details class="fc-multiselect">` | closed | native open/close (a) |
| Trigger | `<summary class="fc-select fc-multiselect-trigger" aria-labelledby="{id}-label {id}-summary">` | the select's chevron look | |
| Summary | `<span id="{id}-summary" class="fc-multiselect-summary">` | ellipsis | the chosen option's label, else `placeholder`, else empty; a live choice rewrites its text in the browser |
| Panel | `<div class="fc-multiselect-panel" data-signals:{kebab-signal}="''">` | inline below the trigger | declares the search signal |
| Search | `<input type="search" class="fc-input fc-multiselect-search" placeholder="Search" aria-label="Search {label}" autocomplete="off" data-bind:{kebab-signal}>` | no `name`: never submitted | always present |
| Options | `<div id="{id}-options" class="fc-multiselect-options" role="listbox" aria-labelledby="{id}-label">` | max-height 14rem, scrolls | |
| Option | `<a class="fc-multiselect-option" role="option" href aria-selected="true\|false" data-filter-text data-show data-on:click>` + the label as text | | one per option, in the order given |
| Empty | `<p class="fc-multiselect-empty">` | | shown instead of the options when there are none (`emptyText`) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/search-select`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required; validated (`[A-Za-z][A-Za-z0-9_-]*`); the parts derive from it (`{id}-field`, `{id}-label`, `{id}-summary`, `{id}-options`) |
| `label` | string | - | |
| `options` | list<{value, label}> | [] | shown in the order given: the caller sorts them |
| `selected` | string | - | the chosen value; one that is not among the options is the same as none |
| `href` | function(value) -> URL | - | required; the address an option links to, from its value. It passes the URL check (`server-rendered-ui.md` section 3 rule 3) like any `href`. **In a fixture** it is a string where `{value}` stands for the option's value, substituted as is: the test adapter of each implementation builds the function from it |
| `placeholder` | string | - | the summary when nothing is chosen |
| `signal` | string | - | required; the search text's signal (camelCase, unique per page) |
| `emptyText` | string | `No options` | |
| `optionAttrs` | attrs | - | on every option link (a live link: `{"data-on:click__prevent": "@get(el.href)"}`); see Behaviour |
| `class` | string | - | appended to the root's `fc-form-field` |

## Accessibility

- The trigger is a native `<summary>` (Enter/Space toggle it); its name is the label plus the current summary.
- The list is a `role="listbox"` named by the label, its links `role="option"` with `aria-selected`. The links are in the Tab order and activate with Enter; there is no arrow-key roving (as `multi-select.md`).
- The search box has an `aria-label`; filtering hides options with `display: none`, which removes them from the accessibility tree.
- Without Datastar every option is visible and each is a working link.

## Behaviour

**(a)** `<details>` opens inline (no positioning code). Choosing is following the option's link.

**(b)** Search: the input binds `{signal}`; each option has `data-filter-text` and `data-show="!${signal}.trim() || el.dataset.filterText.includes(${signal}.trim().toLowerCase())"` (user text is a signal value and a data attribute, never code). `data-filter-text` is the label lower-cased, followed by a space and the value lower-cased **when the value differs from the label** (the rule of `multi-select.md`).

**(b)** Each option's click runs first the choice handler: it writes the option's text into the summary, sets `aria-selected` on the options (true on this one only) and closes the disclosure:

`const d = el.closest('details'); d.querySelector('.fc-multiselect-summary').textContent = el.textContent; d.querySelectorAll('[role=option]').forEach(o => o.setAttribute('aria-selected', o === el)); d.open = false`

That is `data-on:click` on every option. Without further attributes the browser then follows the link.

**(c)** A live page puts the fetch in `optionAttrs`. The attributes are added to every option, a name that is also set by the control replaces it, and one case is composed: with `data-on:click__prevent` in `optionAttrs` the option carries no `data-on:click`, and its `data-on:click__prevent` is the choice handler, `; ` and the given expression, so the link does not navigate and the page is patched instead (`fixtures/search-select/live`). The answer patches the summary by its id (`{id}-summary`) if it changes the choice (for example when the choice is rejected), and the options by `{id}-options`; it never re-renders the disclosure, so it stays as the choice handler left it and the search text is kept.

## Notes for a template author

- Values and labels are user data: they go into `href` (through the URL check), `data-filter-text` and text, all escaped; never into an expression. The caller builds `href` with its own query escaping.
- `placeholder` and `emptyText` are text.
- The control keeps no selection of its own: render it again from the request (`selected`) on every page.
