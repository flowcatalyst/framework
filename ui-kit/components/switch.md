# Switch

**Source:** fc-web themed copy (`crates/fc-web/src/components/switch.rs`)  
**Mechanism:** (a) native  

## Purpose

An on/off toggle built on a native checkbox with `role="switch"`.

## Structure

```html
<span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" role="switch" class="peer h-4.5 w-8 shrink-0 appearance-none rounded-full bg-foreground/20 shadow-xs transition-colors outline-none checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="s1" name="notify" checked aria-label="Notifications"><span class="pointer-events-none absolute top-1/2 left-0.5 size-3.5 -translate-y-1/2 rounded-full bg-background shadow-xs transition-transform peer-checked:translate-x-3.5"></span></span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Wrapper | `<span>` | `peer relative inline-flex shrink-0 has-[:disabled]:opacity-50` |  |
| Input | `<input type=checkbox role=switch>` | `peer h-4.5 w-8 shrink-0 appearance-none rounded-full bg-foreground/20 shadow-xs transition-colors outline-none checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none` | Track |
| Thumb | `<span>` | `pointer-events-none absolute top-1/2 left-0.5 size-3.5 -translate-y-1/2 rounded-full bg-background shadow-xs transition-transform peer-checked:translate-x-3.5` | Slides via `peer-checked:translate-x-3.5` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### state

| State | How |
|---|---|
| on | `checked` |
| off | unchecked |
| disabled | `disabled` |

## Accessibility

`role="switch"` with native checked state exposes on/off. Label it (`<label for>` or `aria-label`). Space toggles.

## Behaviour

Native. Datastar optional: `data-bind:dark` on the input (the gallery's theme switch). A server-persisted switch posts on change: `data-on:change="@post('/prefs')"` with the bound signal.

## Notes for a template author

Parameters: id, name, checked, disabled, label. Keep the real input; never fake a switch with a click handler on a `<span>`.
