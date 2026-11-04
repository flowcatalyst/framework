# Local time

**Source:** hand-built (`ui::local_time + ui.js `localise``)  
**Mechanism:** (b) Datastar `data-init`  

## Purpose

A timestamp rendered by the server in UTC and rewritten in the viewer's time zone and in the language of the page (`<html lang>`, the locale the server resolved: docs/contracts/i18n.md); a page without a `lang` falls back to the browser's locale.

## Structure

```html
<time datetime="2026-10-06T08:30:00Z" data-init="el.firstElementChild.textContent = new Date('2026-10-06T08:30:00Z').toLocaleString(document.documentElement.lang || undefined)"><span data-ignore-morph>2026-10-06 08:30:00 UTC</span></time>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<time datetime="{iso8601}">` | `data-init` naming the same instant | Holds the text span; its `data-init` writes the localised text into the span |
| Text | `<span>` | `data-ignore-morph` | The UTC fallback, shown without script; then the localised text, which a morph leaves alone |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### format

| Value | Expression in `data-init` |
|---|---|
| date and time (default) | `el.firstElementChild.textContent = new Date('{iso8601}').toLocaleString(document.documentElement.lang || undefined)` |
| date only | `el.firstElementChild.textContent = new Date('{iso8601}').toLocaleDateString(document.documentElement.lang || undefined)` |

`{iso8601}` is the `datetime` value, written into the expression.

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/local-time`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `at` | string (RFC 3339 instant) | - | rendered in UTC to whole seconds: `datetime="2026-10-06T08:30:00Z"`, fallback text `2026-10-06 08:30:00 UTC` (date only: `2026-10-06`); empty renders `empty` instead of a `<time>` |
| `dateOnly` | boolean | false | the `format` option "date only" |
| `empty` | string | `-` | the text when `at` is empty (the SPA's `-`) |

## Accessibility

`<time datetime>` is machine-readable; the visible text is a localisation of the same instant.

## Behaviour

`data-init` runs when the element is first processed and when it is patched into the DOM, so rows added by a server patch are localised too. A **morph** that keeps the element (Datastar's default patch mode) is handled by the structure, so a region holding local values may be patched in any mode. The same structure is used by local-number, local-money and local-quantity:

- **Same value:** the root's attributes are unchanged and both the old and the new span carry `data-ignore-morph`, so Datastar leaves the span and its localised text as they are.
- **Changed value:** the value is written into the `data-init` expression, so the morph changes that attribute and Datastar runs it again, writing the new value into the span. Only checked values go into an expression (an RFC 3339 instant, a plain decimal, a currency code), so none needs escaping inside the quotes.

The earlier structure (`el.textContent = ...` reading the value from the element's attributes, text directly inside) lost its localisation on every morph: the patch reset the text to the server fallback and the unchanged `data-init` stayed idle, so a live list read in UTC after its first filter (found in the Go dispatch-jobs list and the platform's field-worker pages, which patched in mode `replace` to avoid it). Verified in Chrome against the bundled Datastar v1.0.4: same-value morphs keep the local text, a changed value re-localises, and the earlier structure falls back. This replaces fc-web's 50-line `ui.js` (a MutationObserver). Datastar Pro's `@intl()` would do the same declaratively; it is not used because it is a paid action.

## Notes for a template author

Parameters: the instant, format. The `datetime` value must be ISO 8601 with a zone.
