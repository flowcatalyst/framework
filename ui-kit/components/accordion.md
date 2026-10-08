# Accordion

**Source:** upstream Topcoat UI registry 0.9.0 (`accordion.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native `<details>`  

## Purpose

Collapsible sections using `<details>`/`<summary>` with an animated height. Items sharing a `name` are exclusive (one open at a time).

## Structure

```html
<div class="w-full">
  <details class="group border-b border-border last:border-b-0 [interpolate-size:allow-keywords] [&::details-content]:h-0 [&::details-content]:overflow-hidden [&::details-content]:[transition:height_200ms_ease-out,content-visibility_200ms_allow-discrete] [&[open]::details-content]:h-auto" name="faq" open>
    <summary class="flex w-full cursor-pointer list-none items-center justify-between gap-4 py-4 text-left text-sm font-medium outline-none transition-colors hover:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background [&::-webkit-details-marker]:hidden">What is this?<svg width="1em" height="1em" class="size-4 shrink-0 text-muted-foreground transition-transform duration-200 ease-out group-open:rotate-180" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-down"/></svg></summary>
    <div class="pb-4 text-sm text-muted-foreground">Content.</div>
  </details>
  <details class="group border-b border-border last:border-b-0 [interpolate-size:allow-keywords] [&::details-content]:h-0 [&::details-content]:overflow-hidden [&::details-content]:[transition:height_200ms_ease-out,content-visibility_200ms_allow-discrete] [&[open]::details-content]:h-auto" name="faq">
    <summary class="flex w-full cursor-pointer list-none items-center justify-between gap-4 py-4 text-left text-sm font-medium outline-none transition-colors hover:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background [&::-webkit-details-marker]:hidden">Another?<svg width="1em" height="1em" class="size-4 shrink-0 text-muted-foreground transition-transform duration-200 ease-out group-open:rotate-180" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-down"/></svg></summary>
    <div class="pb-4 text-sm text-muted-foreground">Content.</div>
  </details>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| accordion | `<div>` | `w-full` |  |
| accordion_item | `<details>` | `group border-b border-border last:border-b-0 [interpolate-size:allow-keywords] [&::details-content]:h-0 [&::details-content]:overflow-hidden [&::details-content]:[transition:height_200ms_ease-out,content-visibility_200ms_allow-discrete] [&[open]::details-content]:h-auto` | Add `name="group"` for exclusive behaviour, `open` for initially open |
| accordion_trigger | `<summary>` | `flex w-full cursor-pointer list-none items-center justify-between gap-4 py-4 text-left text-sm font-medium outline-none transition-colors hover:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background [&::-webkit-details-marker]:hidden` + chevron `size-4 shrink-0 text-muted-foreground transition-transform duration-200 ease-out group-open:rotate-180` |  |
| accordion_content | `<div>` | `pb-4 text-sm text-muted-foreground` |  |

## Accessibility

`<summary>` is a native disclosure button (Enter/Space), exposed as expanded/collapsed. Keep trigger text meaningful.

## Behaviour

Native. The height animation uses `interpolate-size: allow-keywords` and `::details-content` (Chromium; others open instantly, no loss of function). Exclusive via `name` is native (Chrome 120+, Firefox 130+, Safari 17.2+).

## Notes for a template author

Parameters: items `(title, content)`, exclusive group name, initially-open item.
