# Tooltip

**Source:** fc-web themed copy (`crates/fc-web/src/components/tooltip.rs`)  
**Mechanism:** (a) CSS only  

## Purpose

A short text label shown above its trigger on hover or keyboard focus.

## Structure

```html
<span class="group relative inline-flex"><button type="button" aria-describedby="tt1" class="...">Save</button><span id="tt1" role="tooltip" class="pointer-events-none invisible absolute bottom-full left-1/2 z-50 mb-2 -translate-x-1/2 rounded-md bg-foreground px-2.5 py-1 text-xs font-medium text-background opacity-0 shadow-sm whitespace-nowrap [transition:opacity_150ms_ease-out,visibility_150ms_allow-discrete] group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100">Saves the record</span></span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| tooltip | `<span>` | `group relative inline-flex` | Wrapper; the `group` for hover/focus |
| tooltip_content | `<span role="tooltip" id>` | `pointer-events-none invisible absolute bottom-full left-1/2 z-50 mb-2 -translate-x-1/2 rounded-md bg-foreground px-2.5 py-1 text-xs font-medium text-background opacity-0 shadow-sm whitespace-nowrap [transition:opacity_150ms_ease-out,visibility_150ms_allow-discrete] group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100` | The bubble; hidden by default, shown by `group-hover` / `group-focus-within` |

## Accessibility

- The bubble has `role="tooltip"`; **the trigger must reference it with `aria-describedby`** (added in this port; the Topcoat original omits the link, so screen readers do not announce the text).
- Shown on focus as well as hover (`group-focus-within`), so keyboard users get it. It is not dismissible with Escape (as upstream); keep tooltips to short, supplementary text.
- Never put essential information or interactive content in it.
- Touch devices: hover does not occur; do not rely on it.

## Behaviour

CSS only: `opacity-0 invisible` to `group-hover:opacity-100 group-hover:visible` with a 150ms transition. No placement options (always above, centred).

## Notes for a template author

Parameters: text, unique id, the trigger element (slot). Differs from fc-web: `aria-describedby`/`id` added.
