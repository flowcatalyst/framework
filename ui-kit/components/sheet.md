# Sheet

**Source:** fc-web themed copy (`crates/fc-web/src/components/sheet.rs`)  
**Mechanism:** (b) Datastar attributes opening a native modal `<dialog>`  

## Purpose

A modal `<dialog>` with an edge panel that slides in from the left, right, top or bottom. (A non-modal record drawer is `drawer.md`.)

## Structure

```html
<button type="button" data-on:click="document.getElementById('sh-right').showModal()" aria-haspopup="dialog" class="...">Open</button>

<dialog id="sh-right" aria-labelledby="sh-right-title" data-on:click="evt.target === el && el.close()" class="fixed inset-0 z-50 size-full max-h-none max-w-none overflow-hidden bg-background/80 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]">
  <div class="flex flex-col gap-4 overflow-y-auto border-border bg-card p-6 text-card-foreground shadow-sm [transition:translate_200ms_ease-out] ml-auto h-full w-full max-w-sm border-l translate-x-full in-[[open]]:translate-x-0 starting:in-[[open]]:translate-x-full">
    <div class="flex flex-col gap-1.5"><h2 id="sh-right-title" class="text-lg leading-none font-semibold">Title</h2><p class="text-sm text-muted-foreground">Description</p></div>
    <div class="flex-1"><!-- slot: body --></div>
    <div class="flex flex-wrap items-center justify-end gap-2"><button type="button" data-on:click="el.closest('dialog').close()" class="...">Close</button></div>
  </div>
</dialog>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| sheet | `<dialog>` | `fixed inset-0 z-50 size-full max-h-none max-w-none overflow-hidden bg-background/80 text-foreground backdrop-blur-sm open:flex` + `opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]` | Overlay |
| sheet_content | `<div>` | `flex flex-col gap-4 overflow-y-auto border-border bg-card p-6 text-card-foreground shadow-sm [transition:translate_200ms_ease-out]` + side + motion | The edge panel |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### side (default right)

| Value | Classes on the panel (side) | Classes on the panel (motion) |
|---|---|---|
| left | `mr-auto h-full w-full max-w-sm border-r` | `-translate-x-full in-[[open]]:translate-x-0 starting:in-[[open]]:-translate-x-full` |
| right | `ml-auto h-full w-full max-w-sm border-l` | `translate-x-full in-[[open]]:translate-x-0 starting:in-[[open]]:translate-x-full` |
| top | `mb-auto max-h-full w-full border-b` | `-translate-y-full in-[[open]]:translate-y-0 starting:in-[[open]]:-translate-y-full` |
| bottom | `mt-auto max-h-full w-full border-t` | `translate-y-full in-[[open]]:translate-y-0 starting:in-[[open]]:translate-y-full` |

### per-use drawer overrides (users section)

| Purpose | Classes |
|---|---|
| wide panel | `[&]:w-[min(800px,calc(100vw-24px))] [&]:max-w-none` |
| default drawer width | `[&]:w-[min(560px,calc(100vw-24px))] [&]:max-w-none` |
| no padding, flat gap, SPA shadow | `[&]:gap-0 [&]:p-0 [&]:shadow-[-8px_0_32px_rgb(15_23_42/0.18)]` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/sheet`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - |  |
| `side` | enum(right\|left\|top\|bottom) | right |  |
| `title` | string | - |  |
| `description` | string | - |  |
| `class` | string | - | on the panel (width overrides) |
| `default` | slot | - | body (inside `<div class="flex-1">`) |
| `footer` | slot | - |  |

## Accessibility

As `dialog.md` (label, title, focus trap, Escape and focus return come from `showModal()`). Give the close control `aria-label="Close"`.

## Behaviour

Open/close/mask-dismiss exactly as `dialog.md`. The slide animation is `translate` with `starting:in-[[open]]:` entry styles; there is no exit animation requirement.

## Notes for a template author

Parameters: id, side, title, description, width override. Slots: body, footer. For a non-modal panel that leaves the list usable use `drawer.md`.
