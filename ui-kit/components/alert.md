# Alert

**Source:** fc-web themed copy (`crates/fc-web/src/components/alert.rs`)  
**Mechanism:** (a) native  

## Purpose

An inline notice. An optional first-child icon becomes a left icon column.

## Structure

```html
<div class="grid w-full grid-cols-[0_1fr] items-start gap-y-1 rounded-lg border bg-background px-4 py-3 text-sm has-[>svg]:grid-cols-[1rem_1fr] has-[>svg]:gap-x-3 [&>svg]:size-4 [&>svg]:translate-y-0.5 border-destructive/50 text-destructive" role="alert">
  <svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-triangle-alert"/></svg>
  <p class="col-start-2 font-medium tracking-tight">Build failed</p>
  <div class="col-start-2 text-sm text-muted-foreground">The last deploy did not finish.</div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div>` | `grid w-full grid-cols-[0_1fr] items-start gap-y-1 rounded-lg border bg-background px-4 py-3 text-sm has-[>svg]:grid-cols-[1rem_1fr] has-[>svg]:gap-x-3 [&>svg]:size-4 [&>svg]:translate-y-0.5` + variant | Grid: the icon column collapses (`grid-cols-[0_1fr]`) when no `svg` is the first child (`has-[>svg]:grid-cols-[1rem_1fr]`) |
| Icon | `<svg>` first child | `[&>svg]:size-4 [&>svg]:translate-y-0.5` from the root | Optional slot |
| Title | `<p>` | `col-start-2 font-medium tracking-tight` | Optional slot |
| Description | `<div>` | `col-start-2 text-sm text-muted-foreground` | Optional slot |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### variant (default `neutral`)

| Value | Classes added |
|---|---|
| neutral | `border-border text-foreground` |
| destructive | `border-destructive/50 text-destructive` |
| warn | `border-warning/50 text-warning` (new in the Go port: the SPA's `Message severity="warn"`) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/alert`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `variant` | enum(neutral\|destructive\|warn) | neutral |  |
| `role` | string | - | `alert` or `status`, only for messages inserted during the visit |
| `icon` | string | - | first-child icon |
| `title` | string | - |  |
| `description` | string | - |  |
| `id` | string | - | patch target |
| `class` | string | - |  |
| `default` | slot | - | extra content after the description |

## Accessibility

- `role="alert"` is deliberately absent by default: it interrupts a screen reader the moment the element appears, which suits a message that arrives during the visit but not one rendered with the page. Add `role="alert"` (assertive) or `role="status"` (polite) only for dynamically inserted messages.
- The icon is decorative (`aria-hidden="true"`); title and description carry the meaning.

## Behaviour

None.

## Notes for a template author

Parameters: variant, optional `role`. Slots: icon, title, description. A server-patched alert should carry the role.
