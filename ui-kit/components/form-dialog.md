# Form dialog (create/edit form in a modal)

**Source:** new in the kit (2026-10-09), from the Go platform port (dispatch pools, connections). Replaces the SPA's create drawers and inline edit forms (`EntityDrawer` + vee-validate/zod + `useDirtyForm` + the discard-changes confirmation)  
**Mechanism:** (b) Datastar opens the native modal, tracks dirty and busy state and asks before discarding; (c) the form posts through Datastar and the server answers with patches; (a) the same form posts natively without Datastar

## Purpose

One pattern for every create and edit form: a modal dialog holding a `<form>` that the **server** validates. Field errors come back as patches next to the fields, a rule that belongs to no field goes into the form's error region, success closes the dialog and the page shows a flash message. It composes `dialog.md`, `dirty-form.md`, `field.md` and the confirm dialog look (`confirm-dialog.md`) for the discard question.

## Structure

```html
<dialog id="pool-form" aria-labelledby="pool-form-title" aria-describedby="pool-form-desc" open
        data-init="el.open && el.close(); el.showModal()"
        data-on:click="evt.target === el && ($poolFormDirty ? document.getElementById('pool-form-discard').showModal() : document.getElementById('pool-form').close())"
        data-on:cancel="$poolFormDirty && (evt.preventDefault(), document.getElementById('pool-form-discard').showModal())"
        class="{dialog overlay classes}">
  <div class="{dialog content classes} [&]:max-w-[700px]">
    <div class="flex flex-col gap-1.5">
      <h2 id="pool-form-title" class="text-lg leading-none font-semibold">Create Dispatch Pool</h2>
      <p id="pool-form-desc" class="text-sm text-muted-foreground">Configure a new pool for dispatch jobs</p>
    </div>
    <form id="pool-form-form" method="post" action="/ui/dispatch-pools/new" class="flex flex-col gap-4"
          data-signals="{poolFormDirty: false, poolFormBusy: false}"
          data-indicator="poolFormBusy"
          data-init="el.dataset.snapshot = new URLSearchParams(new FormData(el)).toString()"
          data-on:input="$poolFormDirty = new URLSearchParams(new FormData(el)).toString() !== el.dataset.snapshot"
          data-on:submit="@post(el.action, {contentType: 'form', retryMaxCount: 0})"
          data-attr:aria-busy="$poolFormBusy ? 'true' : 'false'">
      <div id="pool-form-error"></div>
      <input type="hidden" name="drawer" value="" data-attr:value="$selPool">
      <!-- slot: fields, each a field (field.md) whose root has a stable id -->
      <div id="pool-form-code-field" role="group" data-slot="field" class="group/field flex min-w-0 flex-col gap-2">
        <label for="pool-form-code" data-slot="field-label" class="{field label classes}">Code <span class="text-destructive" aria-hidden="true">*</span></label>
        <input id="pool-form-code" name="code" required autofocus class="{input classes}">
      </div>
    </form>
    <dialog id="pool-form-discard" role="alertdialog" class="fc-dialog w-[28rem] max-w-[calc(100vw-2rem)]" aria-labelledby="pool-form-discard-title" aria-describedby="pool-form-discard-desc" data-on:click="evt.target === el && el.close()">
      <div class="fc-dialog-header"><span id="pool-form-discard-title">Discard Changes</span></div>
      <div class="fc-dialog-body flex items-start gap-3 text-subtle-foreground">
        <svg width="1em" height="1em" class="size-6 mt-0.5 shrink-0 text-red-600" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-triangle-alert"/></svg>
        <p id="pool-form-discard-desc">You have unsaved changes. Discard them?</p>
      </div>
      <div class="fc-dialog-footer">
        <button type="button" class="fc-btn fc-btn-text" autofocus data-on:click="el.closest('dialog').close()">Keep Editing</button>
        <button type="button" class="fc-btn fc-btn-danger" data-on:click="el.closest('dialog').close(); document.getElementById('pool-form').close()">Discard</button>
      </div>
    </dialog>
    <div class="flex flex-wrap items-center justify-end gap-2">
      <button type="button" class="{button ghost md}" data-attr:disabled="$poolFormBusy" data-on:click="$poolFormDirty ? document.getElementById('pool-form-discard').showModal() : document.getElementById('pool-form').close()">Cancel</button>
      <button type="submit" form="pool-form-form" class="{button primary md}" data-attr:disabled="$poolFormBusy">Create Pool</button>
    </div>
  </div>
</dialog>
```

The class lists in braces are `dialog.md`'s (overlay, content, `width` option), `field.md`'s and `button.md`'s, unchanged; the fixtures carry them in full.

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Dialog | `<dialog id>` (`dialog.md`, persistent) | the dialog overlay classes; `data-on:click` / `data-on:cancel` ask before discarding; `open` + `data-init` when server-opened | modal; light dismiss and Escape go through the dirty check |
| Header | `dialog_header` with `h2#{id}-title` and `p#{id}-desc` | as `dialog.md` | `aria-labelledby` / `aria-describedby` |
| Form | `<form id="{id}-form" method="post" action>` | `flex flex-col gap-4`; `data-signals`, `data-indicator`, the dirty snapshot (`dirty-form.md`), `data-on:submit`, `data-attr:aria-busy` | posts natively (a) or through Datastar (c) |
| Error region | `<div id="{id}-error">` | empty, or a destructive `alert.md` with `role="alert"` (icon `circle-x`, the message as its title) | the form-level error; always present so the server can patch it |
| Hidden fields | `<input type="hidden">` | `attrs` (e.g. `data-attr:value` bound to a page signal) | state the server needs (which record a drawer shows) |
| Fields | slot | `field.md` fields whose root carries `id="{control id}-field"` | each one a patch target |
| Discard dialog | `<dialog id="{id}-discard" role="alertdialog">` | the confirm dialog look (`fc-dialog`), danger icon | "You have unsaved changes. Discard them?": Keep Editing (autofocus) / Discard |
| Footer | `dialog_footer` | Cancel (`button` ghost, md), Submit (`button` primary, md, `type="submit" form="{id}-form"`) | outside the form, linked by `form=` |

## Options

### submit state

| Value | Submit button |
|---|---|
| create (default) | `data-attr:disabled="${signal}Busy"` |
| edit (`requireChange`) | static `disabled` + `data-attr:disabled="${signal}Busy \|\| !${signal}Dirty"`: Save is enabled only after a change |

### open

| Value | Dialog |
|---|---|
| closed (default) | opened by a trigger (`data-on:click="document.getElementById('{id}').showModal()"`) |
| open | `open` + `data-init="el.open && el.close(); el.showModal()"`: rendered visible (also without Datastar, non-modal) and made modal as soon as Datastar sees it; used when the server patches the dialog in or a page loads with it |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/form-dialog`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required; parts derive from it (`{id}-title`, `{id}-desc`, `{id}-form`, `{id}-error`, `{id}-discard`) |
| `title` | string | - |  |
| `description` | string | - | optional |
| `width` | enum(default\|narrow\|wide) | default | `dialog.md` width |
| `action` | string | - | the form's POST target (both mechanisms; the Datastar handler reads `el.action`) |
| `submitLabel` | string | - | "Create Pool", "Save" |
| `signal` | string | - | camelCase prefix of the two signals, `{signal}Dirty` and `{signal}Busy`; unique per page |
| `requireChange` | boolean | false | edit forms: Submit disabled until a value changes |
| `open` | boolean | false | server-opened (above) |
| `error` | string | - | the form-level error (escaped) |
| `hidden` | list<{name, value, attrs}> | [] | hidden inputs |
| `default` | slot | - | the fields |

## Accessibility

- Native modal: focus trap, Escape, inert page, focus returns to the element that opened it (also when the server opened it: the trigger that sent the request still has focus).
- Initial focus: the first focusable element, i.e. the first field; put `autofocus` on it so it wins over anything earlier.
- **Field errors** are `field.md` errors: the control gets `aria-invalid="true"` and `aria-describedby="{control}-err"`, the message is `role="alert"` (announced once when the patch inserts it), and the label turns red. After a failed submit the server moves focus to the first invalid control (`Behaviour`).
- The form-level error is a `role="alert"` alert, announced when patched in.
- `aria-busy` on the form while the request runs; Cancel and Submit are disabled then (no double submit).
- The discard question is an `alertdialog` with the safe action (Keep Editing) focused; Escape or a click on its mask keeps editing.
- Destructive or irreversible forms do not use this pattern; they use `confirm-dialog.md`.

## Behaviour

**Dirty (b).** The form snapshots its fields when inserted (`data-init`) and compares on every input (`dirty-form.md`), so typing a value back clears the state. Closing a changed form (Cancel, Escape, a click on the mask) opens the discard dialog instead (`data-on:cancel` prevents Escape's close); Discard closes both. A fresh server patch of the dialog resets the state (`data-signals` runs again).

**Submit (c).** `data-on:submit="@post(el.action, {contentType: 'form', retryMaxCount: 0})"`: the browser validates the native constraints first (`required`, `min`, `maxlength`), then Datastar posts the form's fields (no signals) to the form's own action, with network retries off (a POST that reached the server must not be repeated). `data-indicator` sets `{signal}Busy` while the request runs. The server answers 200 with patches (`_conventions.md`, "Answering a (c) request"):

| Outcome | Patches |
|---|---|
| validation failure | every field again (`{control}-field`, by id: values echoed, the failing ones with their error), the error region (cleared or with the form-level message), and focus on the first invalid control |
| business rule, refusal, conflict, unexpected failure | the error region with the message (and the fields, when a field caused it) |
| success | close the dialog (`document.getElementById('{id}').close()`, sent as a self-removing `data-init` element), the changed regions (table rows, a drawer), a success flash in `#flash-stack` |

Patch the **fields**, not the whole form: the form element keeps its snapshot (`data-snapshot`) and signals, so the dirty state survives a failed submit. The morph keeps focus and caret in a field the user is still in.

**Without Datastar (a).** The form posts natively. The server answers post/redirect/get: on success 303 to the list with a flash (`_conventions.md`, "Flash after a redirect"); on a validation failure the page again with the dialog `open` and the errors (status 422).

**Server-opened.** A trigger can be a link to the form's own page (`<a href="/pools/new" data-on:click__prevent="@get(el.href)">`): the server answers the Datastar request by patching the dialog (`open`) into a slot on the page, and a navigation (new tab, no Datastar) with the whole page, the dialog open.

## Notes for a template author

- One dialog per form, usually patched into an empty slot element (`<div id="pool-dialog-slot">`) on request. Signals are page-global: one `signal` per form.
- Build the fields with `field.md` (`required` for the marker) and give each field root `id="{control}-field"`; render them from one function used by the page, the dialog patch and the error patch, so they cannot drift.
- Map the server's error codes to fields in the handler (code → field id); anything unmapped goes into the error region.
- Keep business rules in the server's use cases; the form only adds native constraints (`required`, `min`, `maxlength`) as a first line.
