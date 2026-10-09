# Label

**Source:** fc-web themed copy (`crates/fc-web/src/components/label.rs`)  
**Mechanism:** (a) native  

## Purpose

A form label that follows its control's disabled state.

## Structure

```html
<label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50" for="email">Email</label>
```

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/label`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `for` | string | - | the control's id |
| `class` | string | - |  |
| `attrs` | attrs | - |  |
| `default` | slot | - | text |

## Accessibility

Always associate with the control: `for`/`id`, or wrap the control. The disabled styling is driven by CSS (`peer-disabled`, `has-[:disabled]`), so the control must be a sibling/descendant per the patterns in the class list.

## Behaviour

None.

## Notes for a template author

Parameters: `for` id, text. Slot: content.
