# Button

**Source:** fc-web themed copy (`crates/fc-web/src/components/button.rs`)  
**Mechanism:** (a) native  

## Purpose

A clickable control. The same class list also styles a link (`<a>`) as a button.

## Structure

```html
<button class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent bg-primary text-primary-foreground shadow-xs hover:bg-primary/90 active:bg-primary/80 h-9 gap-2 rounded-lg px-4" type="button">Label</button>
```

As a link: put the same class list on an `<a href="/x">`. Icon button: `<button type="button" aria-label="Close" class="... size-9 rounded-lg">` containing one icon.

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<button>` (or `<a>`) | `BASE + variant + size [+ extra]` | The control; `type` and every other attribute are passed through |
| Content | text and/or inline icon | - | Slot |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### variant (default `primary`)

| Value | Classes added |
|---|---|
| primary | `border-transparent bg-primary text-primary-foreground shadow-xs hover:bg-primary/90 active:bg-primary/80` |
| secondary | `border-transparent bg-foreground/5 text-foreground shadow-xs hover:bg-foreground/10 active:bg-foreground/15` |
| outline | `border-border text-foreground hover:bg-foreground/5 active:bg-foreground/10` |
| ghost | `border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10` |
| destructive | `border-transparent bg-destructive text-destructive-foreground shadow-xs hover:bg-destructive/90 active:bg-destructive/80` |
| text | `border-transparent text-primary hover:bg-primary/10 active:bg-primary/15` |
| destructive-outline | `border-destructive text-destructive hover:bg-destructive/5 active:bg-destructive/10` |
| success-outline | `border-success text-success hover:bg-success/5 active:bg-success/10` |

### size (default `md`)

| Value | Classes added |
|---|---|
| sm | `h-8 gap-1.5 rounded-md px-3` |
| md | `h-9 gap-2 rounded-lg px-4` |
| lg | `h-10 gap-2 rounded-lg px-5` |
| icon | `size-9 rounded-lg` |

### BASE (always)

| Classes |
|---|
| `inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50` |

### state: disabled

| Mechanism | Effect |
|---|---|
| `disabled` attribute | `disabled:pointer-events-none disabled:opacity-50` (in BASE) |
| link: `aria-disabled="true"` | add `pointer-events-none opacity-50` yourself and `tabindex="-1"`; a link cannot be natively disabled |

## Accessibility

- Native `<button>` keeps role, Enter/Space activation and focus.
- Always give `type` (`button` unless it submits). A missing `type` defaults to `submit` inside a form.
- Icon-only buttons need `aria-label`.
- Focus ring: `focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2` is in BASE; do not remove it.
- A link styled as a button stays a link (navigation = link, action = button).

## Behaviour

None of its own. Hover/active colours are Tailwind `hover:`/`active:` variants. Disabled is the native attribute.

To open a dialog or toggle a signal, add a Datastar `data-on:click` attribute (see the component being controlled).

## Notes for a template author

- Parameters: `variant`, `size`, `type`, extra classes, extra attributes (pass-through).
- Slot: content.
- Class conflicts: classes are concatenated, not merged. To override a base class use an important variant, e.g. `[&]:size-8`.
- The legacy hand-built `.fc-btn` family is in `legacy-fc-btn.md`.
