# Pagination

**Source:** fc-web themed copy (`crates/fc-web/src/components/pagination.rs`)  
**Mechanism:** (a) native (links)  

## Purpose

A nav of page links: previous, numbered pages, an ellipsis, next. Links only: it has no rows-per-page select and no report (see `paginator.md`).

## Structure

```html
<nav aria-label="pagination" class="@container mx-auto flex w-full justify-center">
    <ul class="flex flex-row flex-wrap items-center justify-center gap-1">
      <li><a href="#" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 h-9 gap-2 rounded-lg px-4"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-left"/></svg><span class="sr-only @xs:not-sr-only">Previous</span></a></li>
      <li><a href="#" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg">1</a></li>
      <li><a href="#" aria-current="page" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-border text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg [&]:border-primary [&]:bg-primary [&]:text-primary-foreground">2</a></li>
      <li><a href="#" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg">3</a></li>
      <li><span class="flex size-9 items-center justify-center text-muted-foreground"><svg width="1em" height="1em" class="size-4" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-ellipsis"/></svg><span class="sr-only">More pages</span></span></li>
      <li><a href="#" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 h-9 gap-2 rounded-lg px-4"><span class="sr-only @xs:not-sr-only">Next</span><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></a></li>
    </ul>
</nav>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| pagination | `<nav aria-label="pagination">` | `@container mx-auto flex w-full justify-center` |  |
| pagination_content | `<ul>` | `flex flex-row flex-wrap items-center justify-center gap-1` |  |
| pagination_item | `<li>` | - |  |
| pagination_link | `<a>` | button classes: `outline`/`icon` when active, `ghost`/`icon` otherwise (see `button.md`) | `aria-current="page"` when active |
| pagination_previous / next | `<a>` | button `ghost`/`md` + inner `<span class="sr-only @xs:not-sr-only">` | Label hides below the `@xs` container width, leaving the icon |
| pagination_ellipsis | `<span>` | `flex size-9 items-center justify-center text-muted-foreground` + sr-only text "More pages" |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### link state

| State | Markup / classes |
|---|---|
| current page | `aria-current="page"` + button `outline` `icon`; the users list adds `[&]:border-primary [&]:bg-primary [&]:text-primary-foreground` for PrimeVue's primary fill |
| other page | button `ghost` `icon` |
| disabled prev/next | `aria-disabled="true" class="pointer-events-none opacity-50"` on the `<a>` (no `href`) |

## Accessibility

`<nav aria-label="pagination">`, current page marked `aria-current="page"`, previous/next have visible or `sr-only` text, ellipsis has `sr-only` text. Disabled links lose their `href`.

## Behaviour

Native links: each page is a URL, so it works without script. Optional Datastar enhancement (c): `data-on:click__prevent="@get('/users?page=3')"` on each link; the server patches the table and this nav.

## Notes for a template author

Parameters: page links `(number, href)`, current page, previous/next hrefs (absent = disabled). The set of visible numbers is the server's choice (the hand-built `paginator` shows five around the current page).
