# Card

**Source:** upstream Topcoat UI registry 0.9.0 (`card.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A bordered surface with header, content and footer regions.

## Structure

```html
<div class="flex flex-col gap-5 rounded-xl border border-border bg-card py-6 text-card-foreground shadow-sm">
  <div class="flex flex-col gap-1.5 px-6"><h3 class="text-base leading-none font-semibold">Title</h3><p class="text-sm text-muted-foreground">Description</p></div>
  <div class="px-6">Content</div>
  <div class="flex items-center gap-2 px-6">Footer actions</div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Card | `<div>` | `flex flex-col gap-5 rounded-xl border border-border bg-card py-6 text-card-foreground shadow-sm` | Root |
| Header | `<div>` | `flex flex-col gap-1.5 px-6` | Optional |
| Title | `<h3>` | `text-base leading-none font-semibold` | Choose the heading level for the page outline |
| Description | `<p>` | `text-sm text-muted-foreground` |  |
| Content | `<div>` | `px-6` |  |
| Footer | `<div>` | `flex items-center gap-2 px-6` |  |

## Accessibility

Use a real heading element in the title at the right level. A card is not a landmark; add `role="region"` + `aria-labelledby` only if it is a navigation target.

## Behaviour

None.

## Notes for a template author

Slots: header, title, description, content, footer. Parameter: heading level. Dark mode: `bg-card`/`border-border` swap automatically.
