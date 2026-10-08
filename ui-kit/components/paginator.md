# Paginator

**Source:** hand-built (`list::paginator, Pager`)  
**Mechanism:** (a) native links + select; (c) optional  

## Purpose

PrimeVue's paginator: first/previous/numbered/next/last links, a rows-per-page select, and a "Showing x to y of n" report.

## Structure

```html
<nav class="fc-paginator" aria-label="Pagination">
  <a class="fc-page-link" href="#" aria-label="First"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevrons-left"/></svg></a>
  <a class="fc-page-link" href="#" aria-label="Previous"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-left"/></svg></a>
  <a class="fc-page-link" href="#">1</a>
  <a class="fc-page-link" href="#">2</a>
  <span class="fc-page-link" aria-current="page">3</span>
  <a class="fc-page-link" href="#">4</a>
  <a class="fc-page-link" href="#">5</a>
  <a class="fc-page-link" href="#" aria-label="Next"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></a>
  <a class="fc-page-link" href="#" aria-label="Last"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevrons-right"/></svg></a>
  <select name="rows" form="list-form" class="fc-select ml-2" aria-label="Rows per page" data-on:change="el.form.requestSubmit()"><option value="50" selected>50</option><option value="100">100</option><option value="250">250</option><option value="500">500</option></select>
  <span class="fc-page-report">Showing 101 to 150 of 412 users</span>
</nav>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<nav class="fc-paginator" aria-label="Pagination">` | centred flex, top border, 1rem text, muted |  |
| Link | `<a class="fc-page-link">` | `min-width 2.5rem; height 2.5rem; radius 2px` | icon links carry `aria-label` (First/Previous/Next/Last) |
| Current | `<span class="fc-page-link" aria-current="page">` | tint + 600 weight |  |
| Disabled | `<span class="fc-page-link" aria-disabled="true">` | 50% opacity |  |
| Rows select | `<select name="rows" form="{form_id}" class="fc-select ml-2" aria-label="Rows per page">` | `.fc-select` (width auto) | Associated with the toolbar GET form by `form`; options 50/100/250/500 |
| Report | `<span class="fc-page-report">` |  |  |

## Accessibility

`<nav aria-label="Pagination">`; icon-only links labelled; current page `aria-current="page"`; select labelled.

## Behaviour

Window logic (server): five numbers around the current page (`start = max(1, min(max(page-2,1), max(pages-4,1)))`, `end = min(start+4, pages)`). Page links are URLs (a). The rows select auto-submits its form: `data-on:change="el.form.requestSubmit()"`. **(c):** `data-on:click__prevent="@get('/users?page=3')"` on links, `data-on:change="@get('/users')"` on the select (with `data-bind:rows`); reply patches the table body and this nav.

## Notes for a template author

Parameters: current page, page count, total, rows, noun, href builder, form id. Slot: none. Rows options are a server constant.
