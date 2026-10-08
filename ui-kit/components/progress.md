# Progress

**Source:** upstream Topcoat UI registry 0.9.0 (`progress.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A native `<progress>` bar.

## Structure

```html
<progress class="h-2 w-full appearance-none overflow-hidden rounded-full bg-foreground/10 [&::-webkit-progress-bar]:bg-transparent [&::-webkit-progress-value]:rounded-full [&::-webkit-progress-value]:bg-primary [&::-webkit-progress-value]:transition-all [&::-moz-progress-bar]:rounded-full [&::-moz-progress-bar]:bg-primary" value="62" max="100" aria-label="Upload progress"></progress>
```

## Accessibility

Native `progressbar` role. Always label it (`aria-label` or `<label for>`). Omit `value` for the indeterminate state.

## Behaviour

Native.

## Notes for a template author

Parameters: value (optional), max (default 100), label. For live progress the server patches the element (SSE `datastar-patch-elements`, matched by id).
