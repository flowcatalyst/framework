# Dirty form (Save enabled on change)

**Source:** hand-built behaviour (`ui.js` `data-dirty-form`, the SPA's `useDirtyForm`)  
**Mechanism:** (b) Datastar  

## Purpose

An edit form whose Save button stays disabled and Discard button hidden until a value differs from what the form was rendered with.

## Structure

```html
<form id="dirty-demo" class="flex flex-col gap-3" data-signals:dirty-demo="false"
      data-init="el.dataset.snapshot = new URLSearchParams(new FormData(el)).toString()"
      data-on:input="$dirtyDemo = new URLSearchParams(new FormData(el)).toString() !== el.dataset.snapshot"
      data-on:reset="setTimeout(() => $dirtyDemo = false)" data-on:submit="void 0">
  <div role="group" data-slot="field" class="group/field flex min-w-0 flex-col gap-2"><label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50 leading-snug group-has-[:disabled]/field:opacity-50 group-has-[[aria-invalid=true]]/field:text-destructive group-data-[invalid=true]/field:text-destructive" for="dd-name" data-slot="field-label">Name</label><input type="text" class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50" id="dd-name" name="name" value="Ada Lovelace"></div>
  <div role="group" data-slot="field" class="group/field flex min-w-0 flex-col gap-2"><label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50 leading-snug group-has-[:disabled]/field:opacity-50 group-has-[[aria-invalid=true]]/field:text-destructive group-data-[invalid=true]/field:text-destructive" for="dd-type" data-slot="field-label">Type</label><span class="relative block has-[:disabled]:opacity-50 [&>select]:[appearance:base-select] [&:has(select:open)>svg]:rotate-180" style="--select-checkmark: url(&quot;data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path fill='none' stroke='currentColor' stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M20 6L9 17l-5-5'/></svg>&quot;)"><select class="h-9 w-full appearance-none items-center rounded-lg border border-input bg-card pr-8 pl-3 text-left text-sm transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none [&::picker(select)]:[appearance:base-select] [&::picker(select)]:mt-1 [&::picker(select)]:rounded-lg [&::picker(select)]:border [&::picker(select)]:border-border [&::picker(select)]:bg-popover [&::picker(select)]:p-1 [&::picker(select)]:text-popover-foreground [&::picker(select)]:shadow-sm [&::picker-icon]:hidden [&_optgroup>legend]:px-2 [&_optgroup>legend]:py-1.5 [&_optgroup>legend]:text-xs [&_optgroup>legend]:font-medium [&_optgroup>legend]:text-muted-foreground [&_optgroup>legend]:cursor-default [&_optgroup>legend]:select-none [&_option]:flex [&_option]:items-center [&_option]:gap-2 [&_option]:rounded-md [&_option]:px-2 [&_option]:py-1.5 [&_option]:text-sm [&_option]:outline-none [&_option:hover]:bg-foreground/5 [&_option:focus]:bg-foreground/5 [&_option:checked]:font-medium [&_option::checkmark]:order-1 [&_option::checkmark]:ml-auto [&_option::checkmark]:size-4 [&_option::checkmark]:shrink-0 [&_option::checkmark]:content-[''] [&_option::checkmark]:bg-muted-foreground [&_option::checkmark]:[mask-size:100%_100%] [&_option::checkmark]:[mask-image:var(--select-checkmark)]" id="dd-type" name="type"><option value="a">Anchor</option><option value="p" selected>Partner</option><option value="c">Client</option></select><svg width="1em" height="1em" class="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted-foreground transition-transform" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-down"/></svg></span></div>
  <div class="flex gap-2">
    <button type="submit" data-attr:disabled="!$dirtyDemo" disabled class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent bg-primary text-primary-foreground shadow-xs hover:bg-primary/90 active:bg-primary/80 h-9 gap-2 rounded-lg px-4">Save</button>
    <button type="reset" data-show="$dirtyDemo" style="display: none" class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 h-9 gap-2 rounded-lg px-4">Discard</button>
  </div>
</form>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Form | `<form id data-signals:dirty-{id}="false" data-init data-on:input data-on:reset>` |  | Holds the snapshot in `el.dataset.snapshot` |
| Save | `button[type=submit]` | button `primary` | `data-attr:disabled="!$dirtyX"` (+ static `disabled` so it is disabled before Datastar loads) |
| Discard | `button[type=reset]` | button `ghost` | `data-show="$dirtyX"` (+ `style="display: none"`) |

## Accessibility

Disabled state is conveyed natively. Keep Save visible (disabled) rather than removing it. Do not trap the user: Enter in a field submits only when Save is enabled.

## Behaviour

Three attributes on the `<form>`:

- `data-init="el.dataset.snapshot = new URLSearchParams(new FormData(el)).toString()"` snapshots the rendered values.
- `data-on:input="$dirtyX = new URLSearchParams(new FormData(el)).toString() !== el.dataset.snapshot"` recomputes on every edit (`input` also fires for selects and checkboxes), so changing a value back clears dirty.
- `data-on:reset="setTimeout(() => $dirtyX = false)"` after the native reset restores values.

When the server patches a **new record into the same form** (a drawer re-rendering for another id) `data-init` runs again for the patched element and takes a fresh snapshot (as `data-dirty-key` did in `ui.js`). Verified in the gallery.

## Notes for a template author

Parameter: a unique signal name per form (`dirtyUserEdit`). Save/Discard may live outside the form (`form="id"`): then bind `data-attr:disabled` on them as usual since the signal is global.
