# Date range

**Source:** new (Go platform port; replaces the SPA's pair of PrimeVue `DatePicker`s, "Created from" / "Created to")  
**Mechanism:** (a) native `<input type="date">` pair (works without Datastar); (b) each input bounds itself by the other on focus  

## Purpose

A whole-day range filter ("created between these two days") for a filters popover or a form. The values are `YYYY-MM-DD` strings under two names, so a GET form puts the range in the URL (`?from=2026-10-01&to=2026-10-06`); either end may be empty (open range).

## Structure

```html
<div id="filter-created" class="fc-form-field" role="group" aria-labelledby="filter-created-label">
  <span id="filter-created-label" class="fc-field-label">Created (UTC)</span>
  <div class="flex items-center gap-2">
    <input type="date" id="filter-created-from" name="from" class="fc-input min-w-0 flex-1" aria-label="From" value="2026-10-01" max="2026-10-06" data-on:focus="el.max = document.getElementById(&#39;filter-created-to&#39;).value">
    <span class="text-muted-foreground" aria-hidden="true">–</span>
    <input type="date" id="filter-created-to" name="to" class="fc-input min-w-0 flex-1" aria-label="To" value="2026-10-06" min="2026-10-01" data-on:focus="el.min = document.getElementById(&#39;filter-created-from&#39;).value">
  </div>
</div>
```

An empty end renders no `value` and leaves the other end's bound off (`min`/`max` absent).

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div id="{id}" class="fc-form-field" role="group" aria-labelledby="{id}-label">` | as `filter-select.md` | names the pair |
| Label | `<span id="{id}-label" class="fc-field-label">` | | |
| Row | `<div class="flex items-center gap-2">` | | the two inputs side by side, also at phone width |
| From | `<input type="date" id="{id}-from" name="{fromName}" class="fc-input min-w-0 flex-1">` | `max` = the "to" value | `aria-label` = `fromLabel` |
| Separator | `<span class="text-muted-foreground" aria-hidden="true">–</span>` | | decorative |
| To | `<input type="date" id="{id}-to" name="{toName}" class="fc-input min-w-0 flex-1">` | `min` = the "from" value | `aria-label` = `toLabel` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/date-range`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required; the parts derive from it (`{id}-label`, `{id}-from`, `{id}-to`) |
| `label` | string | - | the group's visible label (say the time zone the days are in) |
| `fromName` | string | `from` | |
| `toName` | string | `to` | |
| `from` | string | - | `YYYY-MM-DD`; empty = open start |
| `to` | string | - | `YYYY-MM-DD`; empty = open end |
| `fromLabel` | string | `From` | the first input's accessible name |
| `toLabel` | string | `To` | the second input's accessible name |
| `attrs` | attrs | - | on both inputs (a live filter: `{"data-on:change": "@get('/ui/x', {contentType: 'form'})"}`) |

## Accessibility

- Native date inputs: the browser's own keyboard entry and picker, localised display; the submitted value is always ISO `YYYY-MM-DD`.
- The pair is a `role="group"` named by the visible label; each input has its own short name ("From", "To").
- The separator is `aria-hidden`.

## Behaviour

**(a)** The server renders `max` on "from" and `min` on "to" from the current values, so the native picker cannot invert the range.

**(b)** On focus each input re-reads the other's current value into its bound (`el.max = …to.value`, `el.min = …from.value`), so the bounds stay right after the other end changes or after the table toolbar's Clear All empties both (Clear All sets values without input events). The bound handler uses `focus`, leaving `change` free for `attrs`.

**(c)** In a live filter form each input carries the form's `@get` on `change` (`attrs`). The server treats an unparseable value as empty, as it does any hand-edited URL.

## Notes for a template author

- Values are user data: they go into `value`/`min`/`max` attributes, escaped; the ids go into the focus expressions, so `id` must be a plain id (letters, digits, `-`, `_`).
- Interpreting a day (which time zone's midnight) is the page's business; say it in `label`.
