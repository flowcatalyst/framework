# Local time

**Source:** hand-built (`ui::local_time + ui.js `localise``)  
**Mechanism:** (b) Datastar `data-init`  

## Purpose

A timestamp rendered by the server in UTC and rewritten in the viewer's locale and time zone (the SPA's `toLocaleString()`).

## Structure

```html
<time datetime="2026-10-06T08:30:00Z" data-init="el.textContent = new Date(el.getAttribute('datetime')).toLocaleString()">2026-10-06 08:30:00 UTC</time>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<time datetime="{iso8601}">` | - | Text content is the UTC fallback, shown without script |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### format

| Value | Expression in `data-init` |
|---|---|
| date and time (default) | `el.textContent = new Date(el.getAttribute('datetime')).toLocaleString()` |
| date only | `el.textContent = new Date(el.getAttribute('datetime')).toLocaleDateString()` |

## Accessibility

`<time datetime>` is machine-readable; the visible text is a localisation of the same instant.

## Behaviour

`data-init` runs when the element is first processed **and when the element is patched into the DOM**, so rows added by a server patch are localised too. This replaces fc-web's 50-line `ui.js` (a MutationObserver). Datastar Pro's `@intl()` would do the same declaratively; it is not used because it is a paid action. Verified in the gallery.

## Notes for a template author

Parameters: the instant, format. The `datetime` value must be ISO 8601 with a zone.
