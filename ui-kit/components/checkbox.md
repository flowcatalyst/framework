# Checkbox

**Source:** fc-web themed copy (`crates/fc-web/src/components/checkbox.rs`)  
**Mechanism:** (a) native  

## Purpose

A styled native checkbox with an overlaid check icon.

## Structure

```html
<span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="terms" name="terms" checked><svg width="1em" height="1em" class="pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg></span>
<label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50" for="terms">Accept terms</label>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Wrapper | `<span>` | `peer relative inline-flex shrink-0 has-[:disabled]:opacity-50` | Receives the component's own `class`; `peer` for label state |
| Input | `<input type=checkbox>` | `peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none` | All other attributes (id, name, value, checked, disabled, form...) |
| Check mark | `<svg>` sibling after the input | `pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100` | Revealed by `peer-checked` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### state

| State | How |
|---|---|
| checked | `checked` attribute or user |
| disabled | `disabled` (wrapper dims via `has-[:disabled]:opacity-50`) |
| indeterminate | not styled (needs the DOM property) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/checkbox`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - |  |
| `name` | string | - |  |
| `value` | string | - |  |
| `checked` | boolean | false |  |
| `disabled` | boolean | false |  |
| `label` | string | - | renders the sibling `<label for>`; without it the caller labels the input |
| `class` | string | - | on the wrapper `<span>` |
| `attrs` | attrs | - | on the `<input>` (`data-bind:...`) |

## Accessibility

Native checkbox: role, Space key, form participation. Label every checkbox (`<label for>`). The check icon is `aria-hidden`.

## Behaviour

Native. Datastar optional: `data-bind:flag` (boolean) or, for a set of checkboxes with `value`s, one array signal (positional: see `pick-list.md`).

## Notes for a template author

Parameters: id, name, value, checked, disabled. The icon is the Lucide `check` from the sprite.
