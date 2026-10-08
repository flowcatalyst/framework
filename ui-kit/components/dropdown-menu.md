# Dropdown menu

**Source:** fc-web themed copy (`crates/fc-web/src/components/dropdown_menu.rs`)  
**Mechanism:** (a) native `<details>` + (b) Datastar light dismiss  

## Purpose

A menu of actions opened from a trigger. `<details>`/`<summary>` is the native disclosure; Datastar adds outside-click and Escape dismissal.

## Structure

```html
<details id="menu-1" class="group relative inline-block" data-on:click__window="el.open && !el.contains(evt.target) && (el.open = false)" data-on:keydown__window="evt.key === 'Escape' && el.open && (el.open = false, el.querySelector('summary').focus())">
  <summary class="cursor-pointer list-none [&::-webkit-details-marker]:hidden"><span class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-border text-foreground hover:bg-foreground/5 active:bg-foreground/10 h-9 gap-2 rounded-lg px-4">Options</span></summary>
  <div class="absolute z-50 min-w-40 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-sm top-full left-0 mt-1">
    <p class="px-2 py-1.5 text-xs font-medium text-muted-foreground">My account</p>
    <button type="button" class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50" data-on:click="el.closest('details').open = false">Profile</button>
    <hr class="-mx-1 my-1 border-border">
    <details class="group/sub relative">
      <summary class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50 cursor-pointer list-none [&::-webkit-details-marker]:hidden group-open/sub:bg-foreground/5">Invite<svg width="1em" height="1em" class="ml-auto size-4" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-right"/></svg></summary>
      <div class="absolute z-50 min-w-40 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-sm top-0 left-full ml-1">
    <button type="button" class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50" data-on:click="el.closest('details').open = false">Email</button>
    <button type="button" class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50" data-on:click="el.closest('details').open = false">Link</button>
      </div>
    </details>
    <hr class="-mx-1 my-1 border-border">
    <button type="button" class="flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50 text-destructive" data-on:click="el.closest('details').open = false">Sign out</button>
  </div>
</details>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| dropdown_menu | `<details id>` | `group relative inline-block` + the two dismiss attributes below | Root; `open` state is the native attribute |
| trigger | `<summary>` | `cursor-pointer list-none [&::-webkit-details-marker]:hidden` | Contains the visible trigger |
| content | `<div>` | `absolute z-50 min-w-40 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-sm` + position | Absolutely positioned panel |
| item | `<button type="button">` | `flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50` | Action |
| label | `<p>` | `px-2 py-1.5 text-xs font-medium text-muted-foreground` | Group caption |
| separator | `<hr>` | `-mx-1 my-1 border-border` |  |
| sub | `<details class="group/sub relative">` | - | Submenu root |
| sub_trigger | `<summary>` | `flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-sm whitespace-nowrap outline-none hover:bg-foreground/5 focus-visible:bg-foreground/5 active:bg-foreground/10 disabled:pointer-events-none disabled:opacity-50` `cursor-pointer list-none [&::-webkit-details-marker]:hidden` `group-open/sub:bg-foreground/5` + trailing chevron |  |
| sub_content | `<div>` | `absolute z-50 min-w-40 rounded-lg border border-border bg-popover p-1 text-popover-foreground shadow-sm` `top-0 left-full ml-1` |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### align (content position)

| Value | Classes added to the panel |
|---|---|
| left (default) | `top-full left-0 mt-1` |
| right | `top-full right-0 mt-1` (the users Filters panel uses `[&]:right-0 [&]:left-auto` plus a width) |

### Dismiss attributes (on the root `<details>`)

| Behaviour | Attribute |
|---|---|
| outside click | `data-on:click__window="el.open && !el.contains(evt.target) && (el.open = false)"` |
| Escape (focus returns to the trigger) | `data-on:keydown__window="evt.key === 'Escape' && el.open && (el.open = false, el.querySelector('summary').focus())"` |
| close after choosing an item | `data-on:click="el.closest('details').open = false"` on each item |

## Accessibility

- `<summary>` is focusable and toggles with Enter/Space.
- Escape closes and returns focus to the summary (Datastar attribute above).
- Items are real `<button type="button">`; use `<a>` for navigation items.
- Limitations (as upstream): no `role="menu"`, no arrow-key roving focus, no collision/flip handling. Tab moves through the items. Do not add `role="menu"` without implementing arrow keys, or screen reader users are misled.

## Behaviour

Native disclosure (a). Upstream Topcoat has no light dismiss; the two `__window` attributes are this port's addition (b). `__window` listeners exist per menu; keep the menu count on a page modest.

A filter panel that must stay open while its fields change (fc-web users list) simply omits the item-close attribute and the outside-click attribute.

## Notes for a template author

- Parameters: `id`, trigger content, align, items. Slots: trigger, items, submenus.
- Items are slots because actions differ (a form `<button type="submit">`, a Datastar `data-on:click`, a link).
