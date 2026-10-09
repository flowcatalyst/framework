# Row actions

**Source:** new in the kit (2026-10-09), from the Go platform port. Replaces the SPA's per-row PrimeVue icon buttons (`v-tooltip` + `icon="pi pi-pencil"`) and the actions it keeps in record drawers  
**Mechanism:** (a) links, buttons and a native `popover`; (b) one Datastar expression positions the menu and one closes it on activation; (c) the actions themselves

## Purpose

The trailing cell of a data-table row: up to two frequent actions as icon buttons, everything else in an overflow menu. One pattern for every list page, so keyboard and screen-reader users meet the same thing everywhere.

## Rules

1. **Inline: at most two**, frequent and safe (Edit, View, Copy). Each is an `.fc-icon-btn` with a tooltip (`title`) and an accessible name that includes the row (`Edit Orders pool`).
2. **Menu: the rest.** A `More actions for {row}` button opens a native popover with text items (icon + label). Use the menu as soon as there are three actions or any destructive one.
3. **Destructive actions** (delete, archive, revoke, disable) go **last in the menu**; the kit puts a separator before the first one and colours it red (`danger`), and the label is text, never colour alone. They open a confirmation (`confirm-dialog`) or a server-rendered dialog; they never act on one click. An inline destructive icon (`.fc-icon-btn-danger`) is allowed only for a list whose row has a single destructive action and still opens a confirmation.
4. **Disabled with a reason.** An action the user may not run *on this row* (wrong state, missing prerequisite) is shown disabled with the reason, not hidden: `aria-disabled="true"` (not `disabled`, so it stays focusable and the reason can be read), no `href` and no handlers. The reason is visible in the menu (`.fc-menu-hint`), and inline it is the tooltip plus `aria-describedby`. An action the user may never run (no permission) is **left out**, as the API would refuse it.
5. **No row click inside.** Rows with actions should open their record from a link in the name cell. If the row itself is clickable, stop the click on the cell (`attrs: {"data-on:click__stop": "void 0"}`).

## Structure

Inline Edit, then the menu (a link, a Datastar action, a separator and a destructive item that opens a confirmation):

```html
<td class="p-3 align-middle whitespace-nowrap w-px text-right"><div class="flex items-center justify-end gap-1">
  <a href="/pools/7/edit" class="fc-icon-btn" title="Edit" aria-label="Edit Orders pool"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-pencil"/></svg></a>
  <button type="button" class="fc-icon-btn" title="More actions" aria-label="More actions for Orders pool" popovertarget="pool-7-actions-menu"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-ellipsis"/></svg></button>
  <div id="pool-7-actions-menu" popover class="fc-row-menu" data-on:toggle__capture="evt.newState === 'open' && (el.style.top = (el.previousElementSibling.getBoundingClientRect().bottom + 6) + 'px'); evt.newState === 'open' && (el.style.left = Math.max(16, el.previousElementSibling.getBoundingClientRect().right - el.offsetWidth) + 'px')">
    <a href="/pools/7" class="fc-menu-item" data-on:click__capture="el.closest('[popover]').hidePopover()"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-eye"/></svg><span>View details</span></a>
    <button type="button" class="fc-menu-item" data-on:click__capture="el.closest('[popover]').hidePopover()" data-on:click="@post('/pools/7/suspend', {retryMaxCount: 0})"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-pause"/></svg><span>Suspend</span></button>
    <div role="separator" class="fc-menu-separator"></div>
    <button type="button" class="fc-menu-item danger" data-on:click__capture="el.closest('[popover]').hidePopover()" data-on:click="document.getElementById('dlg-confirm').showModal()" aria-haspopup="dialog"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-trash-2"/></svg><span>Delete…</span></button>
  </div>
</div></td>
```

Disabled, inline and in the menu:

```html
<button type="button" class="fc-icon-btn" title="Edit: Archived pools cannot be edited." aria-label="Edit Legacy pool" aria-disabled="true" aria-describedby="pool-9-actions-a0"><svg …#i-pencil/></button><span id="pool-9-actions-a0" hidden>Archived pools cannot be edited.</span>
…
<button type="button" class="fc-menu-item danger" aria-disabled="true" aria-label="Delete…" aria-describedby="pool-9-actions-m1"><svg …#i-trash-2/><span>Delete…<span id="pool-9-actions-m1" class="fc-menu-hint">Only an archived pool with no jobs can be deleted.</span></span></button>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Cell | `<td>` | the table cell classes + `w-px text-right` | `attrs` go here |
| Wrapper | `<div>` | `flex items-center justify-end gap-1` |  |
| Inline action | `<a href>` or `<button type="button">` | `fc-icon-btn` (+ `fc-icon-btn-danger`) | `title` = label, `aria-label` = label + row label |
| Disabled inline | `<button type="button" aria-disabled="true" aria-describedby="{id}-a{i}">` + `<span id="{id}-a{i}" hidden>` | `fc-icon-btn` | `title` = `label: reason` |
| Menu trigger | `<button type="button" popovertarget="{id}-menu">` | `fc-icon-btn`, icon `ellipsis` | `title="More actions"`, `aria-label="More actions for {rowLabel}"` |
| Menu | `<div id="{id}-menu" popover class="fc-row-menu">` | fixed, min 200px, 4px padding, border, shadow | must follow its trigger (the positioning reads `previousElementSibling`) |
| Item | `<a class="fc-menu-item">` or `<button type="button" class="fc-menu-item">` | `danger` for destructive | `data-on:click__capture` closes the menu first |
| Separator | `<div role="separator" class="fc-menu-separator">` |  | before the first destructive item (when something precedes it) |
| Disabled item | `<button aria-disabled="true" aria-label="{label}" aria-describedby="{id}-m{i}">` | `fc-menu-item` (+ `danger`) | the label `<span>` holds the reason `<span id class="fc-menu-hint">` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/row-actions`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | prefix of the generated ids (`{id}-menu`, `{id}-a{i}`, `{id}-m{i}`); required when there is a menu or a disabled action; unique per page (e.g. `pool-{id}-actions`) |
| `rowLabel` | string | - | the row's name, appended to accessible names (`Edit Orders pool`, `More actions for Orders pool`) |
| `actions` | list<action> | [] | inline icon buttons (at most two) |
| `menu` | list<action> | [] | overflow menu items; empty → no menu |
| `attrs` | attrs | - | on the `<td>` |

**action:** `icon` (Lucide name), `label` (text; the menu item's visible label, the inline button's tooltip), `href` (a link; without it a button), `attrs` (on the link/button: the Datastar action or the dialog opener), `destructive` (boolean: last in the menu, red, separated), `disabledReason` (string: non-empty renders the disabled form described above; `href` and `attrs` are then ignored).

## Accessibility

- Every icon-only control has an accessible name that includes the row, so a screen-reader list of buttons reads "Edit Orders pool, More actions for Orders pool, Edit Billing pool, ...".
- The menu is a **disclosure**, not an ARIA `menu`: the trigger is a `popovertarget` button (browsers expose `aria-expanded`), the items are ordinary links and buttons in the tab order right after the trigger, Tab/Shift+Tab move through them, Escape and a click outside close it and return focus to the trigger. Arrow keys are not provided (the kit adds no roving focus, `PORTING-NOTES.md` item 13), so `role="menu"` is deliberately absent.
- Activating an item closes the menu first (`hidePopover()` in a capture listener), so focus goes back to the trigger before a dialog opens; closing the dialog then returns focus to the trigger, not to a hidden item.
- Disabled actions use `aria-disabled` and keep focus; the reason is the accessible description. Destructive items carry a text label (and an ellipsis when they open a confirmation).

## Behaviour

- **(a)** Links navigate; the popover's light dismiss and Escape are native.
- **(b)** Positioning: the table toolbar's expression (`data-on:toggle__capture`), so the menu opens 6px under the trigger, right-aligned to it, and is not clipped by the table's `overflow-x-auto` (popovers are in the top layer). Closing on activation: `data-on:click__capture="el.closest('[popover]').hidePopover()"` on every enabled item (capture listeners run before the item's own `data-on:click`).
- **(c)** Actions are the caller's `attrs`. Mutations use POST/PUT/PATCH/DELETE (never GET) and `{retryMaxCount: 0}` (see `_conventions.md`, "Answering a (c) request"); the server answers with patches: the changed row (by its `id`), a flash message (`flash-stack.md`), or a dialog. Destructive items open a confirmation first.

## Notes for a template author

- Build one `id` per row from a stable key (`pool-{id}-actions`); it must pass the id rule (`[A-Za-z][A-Za-z0-9_-]*`).
- Decide disabled reasons in the view model (state rules); leave out actions the user has no permission for.
- The menu is rendered in every row (one small popover each); it stays closed until used.
