# Page header

**Source:** hand-built (`ui::page_header`)  
**Mechanism:** (a) native  

## Purpose

The title block every page starts with: title, subtitle, and right-aligned actions.

## Structure

```html
<header class="fc-page-header">
  <div>
    <h1 class="fc-page-title">Users</h1>
    <p class="fc-page-subtitle">Manage platform users and their access</p>
  </div>
  <div class="flex items-center gap-2"><button class="fc-btn fc-btn-primary" type="button">New user</button></div>
</header>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<header>` | `.fc-page-header` (`mb-4 flex items-start justify-between gap-4`) |  |
| Title | `<h1 class="fc-page-title">` | 24px / 600, `--fc-title` | Exactly one `h1` per page |
| Subtitle | `<p class="fc-page-subtitle">` | 14px, `--fc-subtitle` |  |
| Actions | `<div class="flex items-center gap-2">` | - | Slot, right-aligned |

## Accessibility

One `<h1>`. Actions are buttons or links with visible text.

## Behaviour

None.

## Notes for a template author

Parameters: title, subtitle. Slot: actions (may be empty; keep the wrapper for alignment).
