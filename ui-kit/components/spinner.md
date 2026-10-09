# Spinner

**Source:** upstream Topcoat UI registry 0.9.0 (`spinner.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

An animated loading indicator (Lucide `loader-circle`).

## Structure

```html
<svg width="1em" height="1em" class="animate-spin size-6" role="img" aria-label="Loading"><use href="/icons/lucide-sprite.svg#i-loader-circle"/></svg>
```

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/spinner`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `size` | string | size-6 | size class |
| `label` | string | Loading | `role="img"` + `aria-label` |
| `decorative` | boolean | false | `aria-hidden="true"` instead (inside a labelled button; no default size class) |

## Accessibility

Give it `role="img"` and an `aria-label` (default "Loading") when it is the only indication of progress. Inside a button that already has a text label make it `aria-hidden="true"`.

## Behaviour

CSS `animate-spin`. With Datastar `data-indicator:fetching` on a button, show it with `data-show="$fetching"` while a request is in flight.

## Notes for a template author

Parameters: size (class), label.
