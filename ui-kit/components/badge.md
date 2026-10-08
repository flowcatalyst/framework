# Badge

**Source:** fc-web themed copy (`crates/fc-web/src/components/badge.rs`)  
**Mechanism:** (a) native  

## Purpose

A small label for a status or count (PrimeVue `Tag` equivalent). The `.fc-tag` hand-built equivalent is in `tag.md`.

## Structure

```html
<span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-success text-success-foreground">Active</span>
```

As a link: put the same class list on an `<a>`.

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<span>` | `BASE + variant [+ extra]` | Label text is the content |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### variant (default `primary`)

| Value | Classes added |
|---|---|
| primary | `border-transparent bg-primary text-primary-foreground` |
| secondary | `border-transparent bg-foreground/5 text-foreground` |
| outline | `border-border text-foreground` |
| destructive | `border-transparent bg-destructive text-destructive-foreground` |
| success | `border-transparent bg-success text-success-foreground` |
| info | `border-transparent bg-info text-info-foreground` |
| warn | `border-transparent bg-warning text-warning-foreground` |

### BASE (always)

| Classes |
|---|
| `inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap` |

## Accessibility

Colour is never the only signal: the label text carries the status. All fills use the `*-foreground` token paired with the fill for contrast.

## Behaviour

None.

## Notes for a template author

Parameters: variant, label, extra classes (the users list adds `[&]:text-[11px]` for role chips). Slot: content (text, or icon + text).
