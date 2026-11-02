# Progress

**Source:** upstream Topcoat UI registry 0.9.0 (`progress.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A native `<progress>` bar.

## Structure

```html
<progress class="h-2 w-full appearance-none overflow-hidden rounded-full bg-foreground/10 [&::-webkit-progress-bar]:bg-transparent [&::-webkit-progress-value]:rounded-full [&::-webkit-progress-value]:bg-primary [&::-webkit-progress-value]:transition-all [&::-moz-progress-bar]:rounded-full [&::-moz-progress-bar]:bg-primary" value="62" max="100" aria-label="Upload progress"></progress>
```

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/progress`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | optional; lets the server patch the element |
| `value` | number | - | absent = indeterminate (no `value` attribute); `0` is a value |
| `max` | number | 100 | always rendered |
| `label` | string | - | `aria-label`; absent renders none (then label it with `<label for>`) |
| `class` | string | - | appended to the root element's class list (concatenated, not merged) |

## Accessibility

Native `progressbar` role. Always label it (`aria-label` or `<label for>`). Omit `value` for the indeterminate state.

## Behaviour

Native.

## Notes for a template author

Parameters: value (optional), max (default 100), label. For live progress the server patches the element (SSE `datastar-patch-elements`, matched by id).
