# Flash banner

**Source:** hand-built (`ui::flash_banner`)  
**Mechanism:** (a) native; (c) for dynamically added messages  

## Purpose

A one-shot message after a post/redirect/get, or appended by a server patch.

## Structure

```html
<div role="status" class="fc-banner fc-banner-success mb-4">User saved.</div>
<div role="status" class="fc-banner fc-banner-error mb-4">Could not save: name is required.</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div role="status" class="fc-banner fc-banner-{kind} mb-4">` | `flex gap-2 rounded-8px px-16 py-12 14px` + kind colours |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### kind

| Value | Class |
|---|---|
| success | fc-banner-success |
| error | fc-banner-error |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/flash-banner`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `kind` | enum(success\|error) | success |  |
| `message` | string | - | escaped |
| `role` | string | status | `alert` for errors that need attention |
| `autoDismiss` | boolean | false | `data-init__delay.5s="el.remove()"` |

## Accessibility

`role="status"` (polite live region). For errors that need immediate attention use `role="alert"`. The message text is the whole content.

## Behaviour

Rendered at the top of the page from a flash cookie (a). **Server patch (c):** keep a container `<div id="flash-stack">` and send `event: datastar-patch-elements` / `data: selector #flash-stack` / `data: mode append` / `data: elements <div role="status" class="fc-banner fc-banner-success mb-4">Saved.</div>`. Auto-dismiss: add `data-init__delay.5s="el.remove()"` to the banner (Datastar `__delay` modifier on `data-init`).

## Notes for a template author

Parameters: kind, message (escaped).
