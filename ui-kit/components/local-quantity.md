# Local quantity

**Source:** new (docs/contracts/i18n.md section 5)  
**Mechanism:** (b) Datastar `data-init`  

## Purpose

A measured value and its unit symbol: the number is rewritten in the browser in the language of the page, the symbol follows after a space: `12.5 kg` reads `12,5 kg` in `af`. The component takes the two parts as strings; it does not depend on any units type.

## Structure

```html
<data value="12.5" data-unit="kg" data-init="el.firstElementChild.textContent = new Intl.NumberFormat(document.documentElement.lang || undefined, {minimumFractionDigits: 1, maximumFractionDigits: 1}).format('12.5')"><span data-ignore-morph>12.5</span> kg</data>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<data value="{value}" data-unit="{symbol}">` | `data-init` naming the same value | Holds the number span, then a space and the symbol as server text |
| Number | `<span>` | `data-ignore-morph` | The plain value, shown without script; then the localised number, which a morph leaves alone |

## Options

### fraction digits

As local-number: the digits of the value. A unit that is empty renders the number alone (no `data-unit`, no trailing space).

## Props

Fixtures, from the ui-kit root: `fixtures/local-quantity`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `value` | string | - | plain decimal text, `-?[0-9]+(\.[0-9]+)?`; anything else is refused; empty renders `empty` instead of a `<data>` |
| `unit` | string | - | the unit symbol, text (escaped in the attribute and the element; written into the page with `textContent`) |
| `empty` | string | `-` | the text when `value` is empty |

## Accessibility

As local-number.

## Behaviour

As local-number. Only the number is localised: the symbol is server text after the span, never in the expression, so a morph updates it like any other text and it needs no script escaping.

## Notes for a template author

Parameters: the value, the unit symbol, the empty text.
