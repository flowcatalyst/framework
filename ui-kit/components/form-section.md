# Form section (FcFormSection)

**Source:** hand-built (`form.rs` doc comment: plain markup because it has two slots)  
**Mechanism:** (a) native  

## Purpose

A titled block of a drawer or page with optional actions in its header; `danger` colours the title red.

## Structure

```html
<section class="fc-form-section">
  <header class="fc-section-header">
    <div>
      <h3 class="fc-section-title">Details</h3>
    <p class="fc-section-description">Read view</p>
    </div>
    <button type="button" class="fc-btn fc-btn-text-primary">Edit</button>
  </header>
  <div class="fc-section-body">
<!-- slot: fc-detail-grid or fc-form-grid -->
  </div>
</section>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<section class="fc-form-section [fc-danger-zone]">` | bottom rule + margin, last child none | `fc-card` variant adds a bordered 16px panel |
| Header | `<header class="fc-section-header">` | flex, space-between |  |
| Title | `<h3 class="fc-section-title">` | 16px/600 | Red under `.fc-danger-zone` |
| Description | `<p class="fc-section-description">` | 13px muted | Optional |
| Actions | any | - | Slot |
| Body | `<div class="fc-section-body">` | `container-type: inline-size` | The container the grids' 640px query measures |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/form-section`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `title` | string | - | `h3.fc-section-title` |
| `description` | string | - | optional |
| `danger` | boolean | false | `fc-danger-zone` (red title) |
| `card` | boolean | false | `fc-card` (bordered panel) |
| `actions` | slot | - | right of the title; nothing is rendered without it |
| `default` | slot | - | the body (`fc-detail-grid`, `fc-form-grid`, `fc-danger-item` rows) |

## Accessibility

Use the right heading level for the title (`h3` in a drawer under an `h2`).

## Behaviour

None.

## Notes for a template author

Parameters: title, description, danger. Slots: actions, body. The danger item row is `.fc-danger-item` (`strong` title + `p` text + button).
