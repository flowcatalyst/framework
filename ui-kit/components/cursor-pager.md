# Cursor pager

**Source:** hand-built (`ui::cursor_pager`)  
**Mechanism:** (a) native links  

## Purpose

"Newest / Older" paging for keyset-paginated lists. Each page is its own URL; the browser Back button is "newer".

## Structure

```html
<div class="fc-table-footer">
  <span>Showing the 200 most recent events</span>
  <nav class="flex items-center gap-2" aria-label="Pagination">
    <span class="fc-btn fc-btn-outline fc-btn-sm" aria-disabled="true"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevrons-left"/></svg> Newest</span>
    <a class="fc-btn fc-btn-outline fc-btn-sm" href="#"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg> Older</a>
  </nav>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div class="fc-table-footer">` | flex, space-between, top border, 13px muted |  |
| Summary | `<span>` | - |  |
| Nav | `<nav class="flex items-center gap-2" aria-label="Pagination">` | - |  |
| Link | `<a class="fc-btn fc-btn-outline fc-btn-sm">` | - | Disabled: `<span ... aria-disabled="true">` (no href) |

## Accessibility

`<nav aria-label="Pagination">`; disabled controls are not links and carry `aria-disabled="true"`.

## Behaviour

Native links. With Datastar (c): `data-on:click__prevent="@get(href)"` per link; the server patches the table and this footer.

## Notes for a template author

Parameters: summary text, newest href (optional), older href (optional).
