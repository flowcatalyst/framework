# Detail field / detail value (FcDetailField)

**Source:** hand-built (`form::detail_field, detail_value`)  
**Mechanism:** (a) native  

## Purpose

A read-only labelled value in `.fc-detail-grid` (the read view of a record). `detail_value` shows "—" for an empty value.

## Structure

```html
<div class="fc-detail-grid">
<div class="fc-detail-field">
  <span class="fc-field-label">Type</span>
  <div class="fc-detail-value"><span class="fc-tag fc-tag-info">Anchor</span></div>
</div>
<div class="fc-detail-field">
  <span class="fc-field-label">Created</span>
  <div class="fc-detail-value">—</div>
</div>
<div class="fc-detail-field fc-span-2">
  <span class="fc-field-label">Notes</span>
  <div class="fc-detail-value">Spans both columns</div>
</div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div class="fc-detail-field [fc-span-2]">` | `min-width: 0` |  |
| Label | `<span class="fc-field-label">` | 13px/500/#475569 |  |
| Value | `<div class="fc-detail-value">` | 14px, `overflow-wrap: break-word` | Slot: text or markup (tag, code, link) |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### grid

| Class | Effect |
|---|---|
| `.fc-detail-grid` | 2 columns, 12px/20px gap; 1 column <= 640px |
| `.fc-dl-grid` | alternative `9rem 1fr` two-column label/value grid |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/detail-field`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `label` | string | - |  |
| `value` | string | - | escaped text |
| `span` | boolean | false | `fc-span-2` |
| `default` | slot | - | markup value (a tag, code, a link), used when `value` is empty; with neither the value is "—" |

## Accessibility

Pairs are visually grouped; for strong semantics use a `<dl>` (`<dt>` label, `<dd>` value) in a template if the data is a true description list. Empty values render "—".

## Behaviour

None.

## Notes for a template author

Parameters: label, value (escaped text) or markup slot, span, empty-fallback.
