# Filter select

**Source:** hand-built (`ui::filter_select`)  
**Mechanism:** (a) native; (c) for server-side filtering  

## Purpose

A labelled `<select>` in a filter row or the Filters popover that submits its GET form on change, so the URL is the list state. The empty option clears the filter.

## Structure

```html
<div class="fc-form-field">
  <label class="fc-field-label" for="filter-scope">Type</label>
  <select id="filter-scope" name="scope" class="fc-select" data-on:change="el.form.requestSubmit()"><option value="">All types</option><option value="ANCHOR">Anchor</option><option value="CLIENT">Client</option></select>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Wrapper | `<div class="fc-form-field">` | `min-width: 0` |  |
| Label | `<label class="fc-field-label" for="filter-{name}">` | 13px/500/#475569 |  |
| Select | `<select id="filter-{name}" class="fc-select">` | `.fc-select` (custom chevron background) |  |

## Accessibility

Visible label with `for`/`id`.

## Behaviour

Native: `data-on:change="el.form.requestSubmit()"` replaces fc-web's inline `onchange`. (c): `data-on:change="@get('/users')"` and a server patch of the list; note that each select must be inside the form or bound with `data-bind`.

## Notes for a template author

Parameters: name, label, placeholder (empty option), options `(value, label)`, selected value. The users section's newer variant uses the themed `field` + `select` and also sets a `fo=1` hidden field to keep the popover open; see `table-toolbar.md`.
