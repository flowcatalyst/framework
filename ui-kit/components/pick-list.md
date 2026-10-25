# Pick list (dual pane)

**Source:** hand-built (`app/users_drawer.rs` `picker_dialog` + `ui.js` mirror/filter)  
**Mechanism:** (b) Datastar signals (array bind, filter); submit (a) or (c)  

## Purpose

The SPA's dual-pane pickers (Manage Roles, Manage Application Access): checkboxes on the left, filtered as you type; what is ticked is mirrored on the right with a count, where x unticks it. Save stays disabled until the selection changes. Normally inside a `dialog`.

## Structure

```html
<form id="pick-demo" data-signals="{picked: ['', '', 'ops', ''], pickQ: ''}" class="grid grid-cols-1 gap-4 sm:grid-cols-2"
      data-init="el.dataset.snapshot = JSON.stringify($picked)" data-on:submit="void 0">
  <div class="flex min-w-0 flex-col overflow-hidden rounded-lg border border-border">
    <div class="flex flex-col gap-2 border-b border-border bg-subtle p-3"><h4 class="text-[12px] font-semibold tracking-wider text-subtle-foreground uppercase">Available roles</h4>
      <input class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" type="search" placeholder="Filter roles" aria-label="Filter roles" data-bind:pick-q></div>
    <div class="h-[200px] overflow-y-auto p-1"><label for="pk-admin" data-show="!$pickQ.trim() || el.dataset.filterText.includes($pickQ.trim().toLowerCase())" data-filter-text="administrator platform:admin" class="flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Administrator</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:admin</span></span><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="pk-admin" name="roles" value="admin" data-bind:picked><svg width="1em" height="1em" class="pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg></span></label><label for="pk-viewer" data-show="!$pickQ.trim() || el.dataset.filterText.includes($pickQ.trim().toLowerCase())" data-filter-text="viewer platform:viewer" class="flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Viewer</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:viewer</span></span><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="pk-viewer" name="roles" value="viewer" data-bind:picked><svg width="1em" height="1em" class="pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg></span></label><label for="pk-ops" data-show="!$pickQ.trim() || el.dataset.filterText.includes($pickQ.trim().toLowerCase())" data-filter-text="operator platform:ops" class="flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Operator</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:ops</span></span><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="pk-ops" name="roles" value="ops" checked data-bind:picked><svg width="1em" height="1em" class="pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg></span></label><label for="pk-billing" data-show="!$pickQ.trim() || el.dataset.filterText.includes($pickQ.trim().toLowerCase())" data-filter-text="billing platform:billing" class="flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Billing</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:billing</span></span><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="checkbox" class="peer size-4 shrink-0 appearance-none rounded-[4px] border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="pk-billing" name="roles" value="billing" data-bind:picked><svg width="1em" height="1em" class="pointer-events-none absolute inset-0 m-auto size-3.5 text-primary-foreground opacity-0 peer-checked:opacity-100" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg></span></label></div>
  </div>
  <div class="flex min-w-0 flex-col overflow-hidden rounded-lg border border-border">
    <div class="border-b border-border bg-subtle p-3"><h4 class="text-[12px] font-semibold tracking-wider text-subtle-foreground uppercase">Selected (<span data-text="$picked.filter(Boolean).length">1</span>)</h4></div>
    <div class="h-[200px] overflow-y-auto p-1"><div data-value="admin" data-show="$picked.includes(el.dataset.value)" style="display: none" class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Administrator</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:admin</span></span><label for="pk-admin" title="Remove" aria-label="Remove Administrator" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg [&]:size-7 [&]:rounded-full cursor-pointer text-destructive"><svg width="1em" height="1em" class="size-[0.9rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></label></div><div data-value="viewer" data-show="$picked.includes(el.dataset.value)" style="display: none" class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Viewer</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:viewer</span></span><label for="pk-viewer" title="Remove" aria-label="Remove Viewer" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg [&]:size-7 [&]:rounded-full cursor-pointer text-destructive"><svg width="1em" height="1em" class="size-[0.9rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></label></div><div data-value="ops" data-show="$picked.includes(el.dataset.value)" class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Operator</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:ops</span></span><label for="pk-ops" title="Remove" aria-label="Remove Operator" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg [&]:size-7 [&]:rounded-full cursor-pointer text-destructive"><svg width="1em" height="1em" class="size-[0.9rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></label></div><div data-value="billing" data-show="$picked.includes(el.dataset.value)" style="display: none" class="flex items-center justify-between gap-3 rounded-md px-2 py-1.5"><span class="flex min-w-0 flex-col"><span class="truncate font-medium">Billing</span><span class="truncate font-mono text-[12px] text-muted-foreground">platform:billing</span></span><label for="pk-billing" title="Remove" aria-label="Remove Billing" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 size-9 rounded-lg [&]:size-7 [&]:rounded-full cursor-pointer text-destructive"><svg width="1em" height="1em" class="size-[0.9rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></label></div><p data-show="$picked.filter(Boolean).length === 0" style="display: none" class="p-3 text-center text-muted-foreground">No roles selected</p></div>
  </div>
  <div class="sm:col-span-2 flex justify-end gap-2"><button type="submit" disabled data-attr:disabled="JSON.stringify($picked) === el.form.dataset.snapshot" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent bg-primary text-primary-foreground shadow-xs hover:bg-primary/90 active:bg-primary/80 h-9 gap-2 rounded-lg px-4"><svg width="1em" height="1em" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-check"/></svg> Save roles</button></div>
</form>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Form | `<form id class="grid grid-cols-1 gap-4 sm:grid-cols-2" data-signals="{picked: [...], pickQ: ''}">` |  | Signals: positional `picked` array, filter text |
| Left pane | `div.flex.min-w-0.flex-col.overflow-hidden.rounded-lg.border.border-border` | header `bg-subtle` + `h4` caps + filter input; list `h-[200px] overflow-y-auto p-1` |  |
| Row (left) | `<label for data-filter-text="{name lower} {code lower}" data-show=...>` | `flex cursor-pointer items-center justify-between gap-3 rounded-md px-2 py-1.5 hover:bg-foreground/5 has-[:checked]:bg-primary/10` | Checkbox from `checkbox.md` with `name="{name}" value data-bind:picked` (`checked` when picked, so the form is right without Datastar); every checkbox shares the name, so the form sends the picked values as a list |
| Right pane | same panel structure |  | Rows `data-value="{value}" data-show="$picked.includes(el.dataset.value)"` (the value is data, never in the expression), `style="display: none"` unless picked; remove control is a `<label for="{checkbox id}">` styled as a ghost icon button |
| Count | `<span data-text="$picked.filter(Boolean).length">` |  |  |
| Save | `button[type=submit]` | button `primary` | `data-attr:disabled="JSON.stringify($picked) === el.form.dataset.snapshot"` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/pick-list`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | the form id |
| `signal` | string | - | the picked array's signal (camelCase, unique per page) |
| `filterSignal` | string | - | the filter text's signal |
| `name` | string | - | the checkboxes' shared name |
| `itemIdPrefix` | string | - | checkbox ids are `{itemIdPrefix}{value}` (values must be id-safe) |
| `items` | list<{value, key, name, code, note, checked, group, description, locked}> | [] | `note` is a short marker under the code (`Inactive`); see **Roles and other long lists** for `key`, `group`, `description`, `locked` |
| `bulk` | bool | false | adds **Select shown** and **Clear** under the filter |
| `embedded` | variant | - | the second template, `PickListFields`: the two panes in a `div`, no `form` and no Save button, for a picker inside a form that already submits (a dialog) |
| `availableTitle` | string | - | left pane heading |
| `selectedTitle` | string | `Selected` | right pane heading, followed by the count |
| `filterPlaceholder` | string | - | also the filter's `aria-label` |
| `emptyText` | string | - | right pane, nothing picked |
| `saveLabel` | string | - | |
| `action` | string | - | renders `method="post" action` on the form |
| `formAttrs` | attrs | - | on the form (Datastar submit) |

## Roles and other long lists

Added for role pickers (a user's or service account's roles), where the list is long, spans applications and has entries the editor may not change.

- **`key`**: the id-safe token of a row's checkbox id (`{itemIdPrefix}{key}`), for a `value` that is not id-safe (a role name such as `orders:approver`). Unique within the list; with no `key` the `value` is the token, as before. Values are written into the signal as quoted string literals, so any text is safe there.
- **`group`**: a heading over a run of items (an application's name or code). Items are given sorted so each group is contiguous. A heading in the left pane shows while the filter matches any of its rows; one in the right pane shows while any of its rows is picked.
- **`description`**: one truncated line under the code, the full text in `title`. The filter matches it as well as the name and code.
- **`locked`**: a pick the user may see but not change (a role above the editor's own access). It is shown ticked and disabled on the left, listed on the right with a lock instead of the remove control, **counted** in the heading, and **still submitted**: the checkbox is disabled, so a hidden input with the same `name` carries it. It has no slot in the positional `picked` array (it is not bound), and the count is `$picked.filter(Boolean).length + {number of locked}`. Use `note` to say why.
- **`bulk`**: *Select shown* ticks every row the filter shows and leaves the others as they are; *Clear* unticks every unlocked row, shown or not.

Fixture: `fixtures/pick-list/roles`.

## Accessibility

- Left list is real checkboxes with visible labels (works with the keyboard and without Datastar). The right pane's x is a `<label for>` of the same checkbox, so it is a native toggle (give it `aria-label="Remove {name}"`).
- Filtering hides rows with `display: none` (`data-show`), removing them from the accessibility tree.
- Announce the count change: add `aria-live="polite"` to the count's parent if desired.

## Behaviour

**Datastar array binding is positional.** With `data-bind:picked` on several checkboxes that have `value`s, `$picked` is an array with **one slot per checkbox in DOM order**: the checkbox's value when ticked, `''` when not (`['', '', 'ops', '']`). Therefore: declare the initial array with that shape (`data-signals="{picked: ['', '', 'ops', '']}"`), count with `$picked.filter(Boolean).length`, test membership with `$picked.includes('ops')`. (Observed with Datastar 1.0.4; the docs describe `["fizz", ""]` semantics.)

- **Filter:** each left row `data-show="!$pickQ.trim() || el.dataset.filterText.includes($pickQ.trim().toLowerCase())"`; the input `data-bind:pick-q`. Replaces `ui.js` `data-filter-list`.
- **Mirror:** right-pane row `data-value="{value}" data-show="$picked.includes(el.dataset.value)"`. Replaces `ui.js` `data-mirror`.
- **Dirty:** snapshot at init: `data-init="el.dataset.snapshot = JSON.stringify($picked)"` on the form; Save `data-attr:disabled="JSON.stringify($picked) === el.form.dataset.snapshot"`. Replaces `ui.js` `useDirtyForm`.
- **Submit:** native form POST of the picked values under the one `name` (`roles=admin&roles=ops`) (a), or `@post('/users/42/roles', {contentType: 'form'})` (c) (the form fields are sent; the signals are not).

## Notes for a template author

- Parameters: id, title, items `(value, name, code, checked)`, name prefix, pane titles, filter placeholder, empty/none-selected text, save label, hint.
- Signal names (`picked`, `pickQ`) are page-global: suffix them per instance (`pickedRoles`, `pickRolesQ`).
- The array shape is generated from the item list in DOM order; keep the checkbox order stable between the initial array and the markup.
