# Autocomplete (server-searched input; the client select)

**Source:** new in the kit (2026-10-09), from the Go platform port. Replaces the SPA's PrimeVue `AutoComplete`, first as its `ClientSelect` (search clients by name or identifier, pick one)  
**Mechanism:** (a) a native `<input list>` with a `<datalist>`; (c) the server refreshes the suggestions as the user types

## Purpose

Pick one value from a set too large to render as a `<select>`: the user types, the browser shows matching suggestions, and the server sends the suggestions that match what was typed. The input's value is what the form posts (for a client: its identifier), and the server resolves and validates it on submit.

## Structure

```html
<input type="text" id="pool-form-client" name="client" placeholder="Search for a client (optional)"
       list="pool-form-client-options" autocomplete="off"
       data-on:input__debounce.300ms="@get('/ui/clients/options?list=pool-form-client-options&q=' + encodeURIComponent(el.value))"
       class="{input.md classes}">
<datalist id="pool-form-client-options">
  <option value="acme">Acme Corp</option>
  <option value="globex">Globex</option>
</datalist>
```

The server's answer to the search (`datastar-patch-elements`, morphed by id):

```html
<datalist id="pool-form-client-options"><option value="acme">Acme Corp</option></datalist>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Input | `<input type="text" list="{id}-options" autocomplete="off">` | `input.md` classes | a combobox for assistive technology (the browser exposes `list`) |
| Search | `data-on:input__debounce.300ms` on the input | `@get('{source}?list={id}-options&q=' + encodeURIComponent(el.value))` | (c); left out when disabled or without a source |
| Options | `<datalist id="{id}-options">` of `<option value>{label}</option>` | - | `value` goes into the input; the label is shown beside it |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/autocomplete` and `fixtures/autocomplete-options`.

**autocomplete**

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required (the datalist is `{id}-options`) |
| `name` | string | - |  |
| `value` | string | - |  |
| `placeholder` | string | - |  |
| `source` | string | - | the search endpoint, a local path without a query (`/[A-Za-z0-9/_-]*`, validated: it is inside an expression) |
| `options` | list<{value, label}> | [] | suggestions rendered with the page |
| `required` / `disabled` / `invalid` | boolean | false | as `input.md` |
| `describedBy` | string | - | `aria-describedby` |
| `class` | string | - | on the input |
| `attrs` | attrs | - | on the input |

**autocomplete-options**: `id` (the datalist id), `options`.

## Accessibility

- The native `list` attribute makes the input a combobox with a listbox of suggestions; keyboard (arrows, Enter, Escape) is the browser's.
- Label it like any input (`field.md`); describe what the value is ("If specified, this pool will only be used for jobs scoped to this client.").
- The server validates the typed value on submit and answers with a field error ("No client with this identifier.") when it matches nothing: a datalist does not force a selection.

## Behaviour

**(a)** Without Datastar the input works with the suggestions rendered with the page; any value can be typed.

**(c)** Each input event (debounced 300 ms) sends `GET {source}?list={listId}&q={typed}` (`Datastar-Request: true`; a GET keeps Datastar's network retries). The server checks its permission, searches (case-insensitive, capped), and answers with the datalist patched by id. Answer with an **empty datalist**, not an error flash, when the user may not search: a failing search on every keystroke must not flood the flash stack. The typed text is read at run time (`el.value`), never rendered into the expression.

Browsers refresh an already open suggestion list on the next keystroke after the patch; the first suggestions come from the page's options.

## Notes for a template author

- Render the first suggestions with the page (the server's search for ""), so the list is useful before typing.
- Resolve the submitted value on the server (a client identifier → the client); keep the value human-readable (an identifier, not a database id).
- When the user may not search the set at all, render the input `disabled` with a description saying why, rather than an input that never suggests anything.
