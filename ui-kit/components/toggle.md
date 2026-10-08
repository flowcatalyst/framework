# Toggle and toggle group

**Source:** upstream Topcoat UI registry 0.9.0 (`toggle.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A pressed/unpressed button built on a native checkbox (independent) or radio (exclusive) inside a `<label>`.

## Structure

```html
<div class="inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1">
  <label class="inline-flex shrink-0 cursor-pointer items-center justify-center border border-transparent text-sm font-medium whitespace-nowrap transition-colors select-none text-muted-foreground hover:bg-foreground/5 hover:text-foreground has-[:checked]:bg-foreground/10 has-[:checked]:text-foreground has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-2 has-[:focus-visible]:ring-offset-background has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50 h-8 gap-1.5 rounded-md px-2"><input type="radio" name="range" value="day" class="sr-only">Day</label>
  <label class="inline-flex shrink-0 cursor-pointer items-center justify-center border border-transparent text-sm font-medium whitespace-nowrap transition-colors select-none text-muted-foreground hover:bg-foreground/5 hover:text-foreground has-[:checked]:bg-foreground/10 has-[:checked]:text-foreground has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-2 has-[:focus-visible]:ring-offset-background has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50 h-8 gap-1.5 rounded-md px-2"><input type="radio" name="range" value="week" class="sr-only" checked>Week</label>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Group | `<div>` | `inline-flex w-fit items-center gap-1 rounded-lg border border-border p-1` | Only arranges |
| Toggle | `<label>` | BASE + size | Wraps the input and the content |
| Input | `<input>` | `sr-only` | Kept focusable and announced; not `display:none` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### kind (default independent)

| Value | Input type |
|---|---|
| independent | `checkbox` |
| exclusive | `radio` (share a `name`) |

### size (default md)

| Value | Classes added to the label |
|---|---|
| sm | `h-8 gap-1.5 rounded-md px-2` |
| md | `h-9 gap-2 rounded-lg px-3` |
| lg | `h-10 gap-2 rounded-lg px-4` |

### BASE (label)

| Classes |
|---|
| `inline-flex shrink-0 cursor-pointer items-center justify-center border border-transparent text-sm font-medium whitespace-nowrap transition-colors select-none text-muted-foreground hover:bg-foreground/5 hover:text-foreground has-[:checked]:bg-foreground/10 has-[:checked]:text-foreground has-[:focus-visible]:ring-2 has-[:focus-visible]:ring-ring has-[:focus-visible]:ring-offset-2 has-[:focus-visible]:ring-offset-background has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50` |

## Accessibility

The input is `sr-only` (not hidden), so it keeps focus and is announced as a checkbox/radio named by the label text. Icon-only toggles need `aria-label` on the input.

## Behaviour

Native.

## Notes for a template author

Parameters: kind, size, name, value, checked. Slot: content (text or icon).
