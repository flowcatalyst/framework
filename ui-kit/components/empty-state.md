# Empty state

**Source:** hand-built (`ui::empty_state`)  
**Mechanism:** (a) native  

## Purpose

Shown in place of table rows when there are none: an icon, a message, and "Clear filters" when a filter is active.

## Structure

```html
<div class="fc-empty">
  <svg width="1em" height="1em" class="size-12" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-users"/></svg>
  <span>No users found</span>
  <a href="/users" class="fc-btn fc-btn-text-primary">Clear filters</a>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div class="fc-empty">` | centred, `padding 48px 24px`, muted | Place it in a `<td colspan>` inside a table, or on its own |
| Icon | `<svg class="size-12">` | `.fc-empty > svg` (block, centred, `#cbd5e1`) |  |
| Message | `<span>` | block |  |
| Clear | `<a class="fc-btn fc-btn-text-primary">` | - | Only when a filter is active; links to the unfiltered list |

## Accessibility

Add `role="status"` when the region appears after a filter change so it is announced. The icon is decorative.

## Behaviour

None. In a client-filtered list it is toggled by `data-show="$shown === 0"` (see `data-table.md`).

## Notes for a template author

Parameters: message, icon, clear-filters URL (optional).
