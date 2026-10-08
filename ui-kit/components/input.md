# Input

**Source:** fc-web themed copy (`crates/fc-web/src/components/input.rs`)  
**Mechanism:** (a) native  

## Purpose

A text-like `<input>`.

## Structure

```html
<input type="text" class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" id="email" type="email" name="email" placeholder="you@example.com">
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<input>` | `h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50` | Every attribute (type, name, value, placeholder, required, ...) is passed through |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### state

| State | How | Effect |
|---|---|---|
| invalid | `aria-invalid="true"` | `aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive`; the enclosing `field` label turns red |
| disabled | `disabled` | `disabled:pointer-events-none disabled:opacity-50` |
| file | `type="file"` | `file:` variants style the button |

## Accessibility

Needs an accessible name: a `<label for>` or `aria-label`. Link an error message with `aria-describedby`. Do not rely on colour for invalid; show the error text.

## Behaviour

Native. Optional Datastar: `data-bind:name` for two-way signal binding; `data-on:input__debounce.300ms="@post(...)"` for live validation (mechanism c).

## Notes for a template author

Parameters: type, name, value, id, placeholder, required/disabled/invalid. fc-web changed the border to `border-input` and the background to `bg-card` (white); kept.
