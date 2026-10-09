# Input

**Source:** fc-web themed copy (`crates/fc-web/src/components/input.rs`)  
**Mechanism:** (a) native  

## Purpose

A text-like `<input>`.

## Structure

```html
<input type="email" class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" id="email" name="email" placeholder="you@example.com">
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

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/input`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - |  |
| `name` | string | - |  |
| `type` | string | text | exactly one `type` attribute |
| `value` | string | - | omitted when empty |
| `placeholder` | string | - |  |
| `required` | boolean | false |  |
| `disabled` | boolean | false |  |
| `invalid` | boolean | false | `aria-invalid="true"` |
| `describedBy` | string | - | `aria-describedby` (error/help id) |
| `class` | string | - |  |
| `attrs` | attrs | - | on the `<input>` |

### Numbers

A number is this input with `type="number"` and its constraints as attributes: `min`, `max`, `step="1"` for whole numbers and `inputmode="numeric"` (passed through `attrs`). No separate component: the native control validates (the browser refuses a submit below `min`), and the server parses the value and answers a field error for anything else ("Enter a whole number."). PrimeVue's `InputNumber` grouping and spinner buttons are not reproduced. Fixture: `fixtures/input/number`.

## Accessibility

Needs an accessible name: a `<label for>` or `aria-label`. Link an error message with `aria-describedby`. Do not rely on colour for invalid; show the error text.

## Behaviour

Native. Optional Datastar: `data-bind:name` for two-way signal binding; `data-on:input__debounce.300ms="@post(...)"` for live validation (mechanism c).

## Notes for a template author

Parameters: type, name, value, id, placeholder, required/disabled/invalid. fc-web changed the border to `border-input` and the background to `bg-card` (white); kept.
