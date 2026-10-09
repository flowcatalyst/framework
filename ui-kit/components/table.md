# Table

**Source:** fc-web themed copy (`crates/fc-web/src/components/table.rs`); per-use overrides from `app/users.rs`  
**Mechanism:** (a) native; sorting/paging/filtering are (b) or (c) in `data-table.md`  

## Purpose

Table markup in a horizontally scrolling container. No sorting, selection or paging: those are layered on in `data-table.md`.

## Structure

```html
<div class="w-full overflow-x-auto">
  <table class="w-full caption-bottom border-collapse text-sm">
    <caption class="mt-4 text-sm text-muted-foreground">Optional caption</caption>
    <thead class="[&_tr]:border-b"><tr class="border-b border-border transition-colors hover:bg-foreground/5"><th class="h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground" scope="col">Name</th></tr></thead>
    <tbody class="[&_tr:last-child]:border-0"><tr class="border-b border-border transition-colors hover:bg-foreground/5"><td class="p-3 align-middle whitespace-nowrap">Ada</td></tr></tbody>
    <tfoot class="border-t border-border bg-foreground/5 font-medium [&>tr]:last:border-b-0"><tr class="border-b border-border transition-colors hover:bg-foreground/5"><td class="p-3 align-middle whitespace-nowrap">Total</td></tr></tfoot>
  </table>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Container | `<div>` | `w-full overflow-x-auto` | Scrolls horizontally |
| table | `<table>` | `w-full caption-bottom border-collapse text-sm` | `caption-bottom`: caption renders under the table |
| table_header | `<thead>` | `[&_tr]:border-b` |  |
| table_body | `<tbody>` | `[&_tr:last-child]:border-0` |  |
| table_footer | `<tfoot>` | `border-t border-border bg-foreground/5 font-medium [&>tr]:last:border-b-0` |  |
| table_row | `<tr>` | `border-b border-border transition-colors hover:bg-foreground/5` | Row hover tint |
| table_head | `<th>` | `h-10 px-3 text-left align-middle font-medium whitespace-nowrap text-muted-foreground` | Add `scope="col"` |
| table_cell | `<td>` | `p-3 align-middle whitespace-nowrap` |  |
| table_caption | `<caption>` | `mt-4 text-sm text-muted-foreground` |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### Per-use overrides used by the users list (add to the element shown)

| Option | Element | Classes added |
|---|---|---|
| dense | `<table>` | `[&_td]:py-[0.4rem] [&_td]:px-3 [&_th]:px-3` |
| caps header | `<thead>` | `bg-subtle [&_th]:h-9 [&_th]:text-[12px] [&_th]:font-semibold [&_th]:tracking-wider [&_th]:text-subtle-foreground [&_th]:uppercase` |
| striped rows | `<tbody>` | `[&_tr:nth-child(even)]:bg-subtle` |
| static header row (no hover) | header `<tr>` | `hover:bg-transparent` |
| clickable row | `<tr>` | `cursor-pointer` |
| fixed column width | `<th>` | `w-[20%]` etc. |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/table`, `fixtures/table-row`, `fixtures/table-head`, `fixtures/table-cell` and `fixtures/empty-row`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | on the scrolling container (patch target) |
| `caption` | string | - |  |
| `dense` | boolean | false | users-list cell padding |
| `class` | string | - | on `<table>` |
| `attrs` | attrs | - | on `<table>` |
| `default` | slot | - | thead/tbody/tfoot |

Parts: **thead** `caps` (boolean); **tbody** `id`, `striped` (boolean), `attrs`; **tfoot**; **tr** `static` (header row, no hover), `clickable` (`cursor-pointer`), `id`, `class`, `attrs`; **th** `class`, `attrs` (always `scope="col"`); **td** `colspan` (number), `class`, `attrs`; **empty row** `colspan` (number), `empty` (empty-state props): one `<tr><td colspan>` holding the empty state.

## Accessibility

- Use `<th scope="col">`; a caption (or `aria-label` on the table) names it.
- Sortable columns: put `aria-sort="ascending|descending"` on the active `<th>` and a real link or button inside it (see `data-table.md`).
- A clickable row is a mouse convenience only: the name cell must contain a real link so keyboard users can reach the detail.
- Empty tables: render one row with a `colspan` cell containing the empty state (`empty-state.md`).

## Behaviour

None (native table). Row hover is CSS.

## Notes for a template author

Differs from fc-web: `bg-[#f8fafc]` and `text-[#475569]` became `bg-subtle` and `text-subtle-foreground` (same light values) so dark mode works.

Parameters: dense/striped/caps flags, column widths. Slots: header cells, rows, cells, caption, footer.
