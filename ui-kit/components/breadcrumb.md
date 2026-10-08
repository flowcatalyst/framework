# Breadcrumb

**Source:** upstream Topcoat UI registry 0.9.0 (`breadcrumb.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A trail of links ending in the current page.

## Structure

```html
<nav aria-label="breadcrumb">
  <ol class="flex flex-wrap items-center gap-2 text-sm text-muted-foreground">
    <li class="inline-flex items-center gap-2"><a href="/" class="transition-colors hover:text-foreground">Home</a></li>
    <li aria-hidden="true"><svg width="1em" height="1em" class="size-3.5" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></li>
    <li class="inline-flex items-center gap-2"><a href="/users" class="transition-colors hover:text-foreground">Users</a></li>
    <li aria-hidden="true"><svg width="1em" height="1em" class="size-3.5" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></li>
    <li class="inline-flex items-center gap-2"><span aria-current="page" class="font-medium text-foreground">Ada</span></li>
  </ol>
</nav>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| breadcrumb | `<nav aria-label="breadcrumb">` | - |  |
| breadcrumb_list | `<ol>` | `flex flex-wrap items-center gap-2 text-sm text-muted-foreground` |  |
| breadcrumb_item | `<li>` | `inline-flex items-center gap-2` |  |
| breadcrumb_link | `<a>` | `transition-colors hover:text-foreground` |  |
| breadcrumb_page | `<span aria-current="page">` | `font-medium text-foreground` | Current page, not a link |
| breadcrumb_separator | `<li aria-hidden="true">` | chevron icon `size-3.5` |  |
| breadcrumb_ellipsis | `<span>` | `flex items-center` + icon + sr-only "More" | Collapsed segments |

## Accessibility

`<nav aria-label="breadcrumb">` landmark, ordered list, current page `aria-current="page"`, decorative separators `aria-hidden`.

## Behaviour

None.

## Notes for a template author

Parameters: items `(label, href|null)`.
