# Local money

**Source:** new (docs/contracts/i18n.md section 5)  
**Mechanism:** (b) Datastar `data-init`  

## Purpose

An amount and its ISO 4217 currency code written by the server as plain text and rewritten in the browser in the language of the page, in currency style: `3132.48 ZAR` reads `R 3 132,48` in `en-ZA`. The component takes the two parts as strings; it does not depend on any money type.

## Structure

```html
<data value="3132.48" data-currency="ZAR" data-init="el.firstElementChild.textContent = new Intl.NumberFormat(document.documentElement.lang || undefined, {style: 'currency', currency: 'ZAR'}).format('3132.48')"><span data-ignore-morph>3132.48 ZAR</span></data>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<data value="{amount}" data-currency="{code}">` | `data-init` naming the same amount and code | Holds the text span; its `data-init` writes the localised amount into the span |
| Text | `<span>` | `data-ignore-morph` | `{amount} {code}`, shown without script; then the localised amount, which a morph leaves alone |

## Options

### digits

Absent: the currency's own minor units (`Intl` default: 2 for ZAR, 0 for JPY, 3 for BHD). Given: `minimumFractionDigits` and `maximumFractionDigits` are both set to it (0 to 20).

## Props

Fixtures, from the ui-kit root: `fixtures/local-money`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `amount` | string | - | plain decimal text, `-?[0-9]+(\.[0-9]+)?`; anything else is refused; empty renders `empty` instead of a `<data>` |
| `currency` | string | - | three capital letters; anything else is refused |
| `digits` | integer | - | see the option |
| `empty` | string | `-` | the text when `amount` is empty |

## Accessibility

As local-number.

## Behaviour

As local-number. The currency code is written into the expression too (`currency: '{code}'`), so a changed currency with the same amount also re-runs it.

## Notes for a template author

Parameters: the amount, the currency code, optional digits, the empty text.
