# Local number

**Source:** new (docs/contracts/i18n.md section 5)  
**Mechanism:** (b) Datastar `data-init`  

## Purpose

A decimal number written by the server as plain text and rewritten in the browser in the language of the page (`<html lang>`): `1234567.890` reads `1 234 567,890` in `af-ZA` and `1,234,567.890` in `en`. The server needs no locale formatting data.

## Structure

```html
<data value="1234.5" data-init="el.firstElementChild.textContent = new Intl.NumberFormat(document.documentElement.lang || undefined, {minimumFractionDigits: 1, maximumFractionDigits: 1}).format('1234.5')"><span data-ignore-morph>1234.5</span></data>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<data value="{plain decimal}">` | `data-init` naming the same value | Holds the text span; its `data-init` writes the localised number into the span |
| Text | `<span>` | `data-ignore-morph` | The plain value, shown without script; then the localised number, which a morph leaves alone |

## Options

### fraction digits

Always the number of fraction digits of the value, written as both `minimumFractionDigits` and `maximumFractionDigits`: the browser shows exactly the digits it was given (never rounded to its default of three, never a dropped trailing zero). A value with more than 20 fraction digits is refused.

## Props

Fixtures, from the ui-kit root: `fixtures/local-number`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `value` | string | - | plain decimal text, `-?[0-9]+(\.[0-9]+)?`; anything else is refused; empty renders `empty` instead of a `<data>` |
| `empty` | string | `-` | the text when `value` is empty |

## Accessibility

`<data value>` is the machine-readable value; the visible text is a localisation of it.

## Behaviour

As local-time, Behaviour: the value is written into the `data-init` expression (`format('{plain decimal}')`) and the text is in a `data-ignore-morph` span, so a morph keeps the localised number and a changed value re-runs it; any patch mode works. `Intl.NumberFormat` reads the decimal text exactly (no rounding through a floating-point number).

## Notes for a template author

Parameters: the value, the empty text. Do not format the number on the server.
