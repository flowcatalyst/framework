# Textarea

**Source:** upstream Topcoat UI registry 0.9.0 (`textarea.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A multi-line input that grows with its content (`field-sizing: content`).

## Structure

```html
<textarea class="field-sizing-content min-h-16 w-full rounded-lg border border-input bg-card px-3 py-2 text-sm transition-colors outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" placeholder="Notes" aria-label="Notes"></textarea>
```

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### state

| State | How | Effect |
|---|---|---|
| invalid | `aria-invalid="true"` | destructive border and ring |
| disabled | `disabled` | dimmed |

## Accessibility

As `input`.

## Behaviour

`field-sizing: content` auto-grows in Chromium; other browsers fall back to a fixed `min-h-16` (set `rows`).

## Notes for a template author

Differs from upstream: `border-input bg-card` instead of `border-border bg-transparent`, to match `input` (the same edit fc-web made to input/select/checkbox).
