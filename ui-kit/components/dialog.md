# Dialog

**Source:** fc-web themed copy (`crates/fc-web/src/components/dialog.rs`) (+ `MODAL` override from `app/users_drawer.rs`)  
**Mechanism:** (b) Datastar attributes opening a native modal `<dialog>`; (c) optional server-opened  

## Purpose

A modal panel over the page: a full-viewport `<dialog>` whose own mask is the overlay, with a centred content panel.

## Structure

```html
<button type="button" class="..." data-on:click="document.getElementById('dlg-edit').showModal()" aria-haspopup="dialog">Edit profile</button>

<dialog id="dlg-edit" aria-labelledby="dlg-edit-title" aria-describedby="dlg-edit-desc" data-on:click="evt.target === el && el.close()" class="fixed inset-0 z-50 size-full max-h-none max-w-none items-start justify-center overflow-y-auto bg-background/80 p-4 text-foreground backdrop-blur-sm open:flex opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]">
  <div class="relative my-auto flex w-full max-w-lg flex-col gap-4 rounded-xl border border-border bg-card p-6 text-card-foreground shadow-sm scale-95 opacity-0 in-[[open]]:scale-100 in-[[open]]:opacity-100 starting:in-[[open]]:scale-95 starting:in-[[open]]:opacity-0 [transition:scale_200ms_ease-out,opacity_200ms_ease-out]">
    <div class="flex flex-col gap-1.5">
      <h2 id="dlg-edit-title" class="text-lg leading-none font-semibold">Edit profile</h2>
      <p id="dlg-edit-desc" class="text-sm text-muted-foreground">Make changes to your profile here.</p>
    </div>
    <!-- slot: body -->
    <div class="flex flex-wrap items-center justify-end gap-2"><button class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent text-foreground hover:bg-foreground/5 active:bg-foreground/10 h-9 gap-2 rounded-lg px-4" type="button" data-on:click="el.closest('dialog').close()">Cancel</button><button class="inline-flex shrink-0 items-center justify-center border text-sm font-medium whitespace-nowrap transition-colors outline-none select-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 border-transparent bg-primary text-primary-foreground shadow-xs hover:bg-primary/90 active:bg-primary/80 h-9 gap-2 rounded-lg px-4" type="submit">Save</button></div>
  </div>
</dialog>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Dialog | `<dialog>` | `fixed inset-0 z-50 size-full max-h-none max-w-none items-start justify-center overflow-y-auto bg-background/80 p-4 text-foreground backdrop-blur-sm open:flex` + `opacity-0 open:opacity-100 starting:open:opacity-0 [transition:opacity_200ms_ease-out,display_200ms_allow-discrete]` | Full-viewport overlay; `open:flex` shows it only when open; fades via `allow-discrete` |
| dialog_content | `<div>` | `relative my-auto flex w-full max-w-lg flex-col gap-4 rounded-xl border border-border bg-card p-6 text-card-foreground shadow-sm` + `scale-95 opacity-0 in-[[open]]:scale-100 in-[[open]]:opacity-100 starting:in-[[open]]:scale-95 starting:in-[[open]]:opacity-0 [transition:scale_200ms_ease-out,opacity_200ms_ease-out]` | The panel; scales/fades in |
| dialog_header | `<div>` | `flex flex-col gap-1.5` |  |
| dialog_title | `<h2>` | `text-lg leading-none font-semibold` | `id="{id}-title"` |
| dialog_description | `<p>` | `text-sm text-muted-foreground` | `id="{id}-desc"` |
| dialog_footer | `<div>` | `flex flex-wrap items-center justify-end gap-2` | Actions |
| close button (optional) | `<button>` | button `ghost`/`icon` + `absolute top-3 right-3 [&]:size-8 [&]:rounded-full text-muted-foreground` | PrimeVue's `closable` X; `aria-label="Close"` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### mask (default frosted)

| Value | Classes added to the `<dialog>` |
|---|---|
| frosted | none (the base `bg-background/80 backdrop-blur-sm`) |
| dark | `[&]:w-screen [&]:bg-[rgb(15_23_42/0.4)] [&]:backdrop-blur-none backdrop:bg-transparent` (the SPA's dark mask, used by every users-section dialog) |

### width (on dialog_content)

| Value | Classes |
|---|---|
| default | `max-w-lg` (in base) |
| narrow | `[&]:max-w-[450px]` |
| wide | `[&]:max-w-[700px]` |

### light dismiss

| Value | Attribute on the `<dialog>` |
|---|---|
| on (default) | `data-on:click="evt.target === el && el.close()"` |
| off | omit it (always off for `alert-dialog`) |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/dialog`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | required; `{id}-title`/`{id}-desc` are derived |
| `title` | string | - |  |
| `description` | string | - |  |
| `mask` | enum(frosted\|dark) | frosted |  |
| `width` | enum(default\|narrow\|wide) | default |  |
| `persistent` | boolean | false | turns light dismiss off (no mask-click handler) |
| `closable` | boolean | false | the X button |
| `role` | string | - | `alertdialog` for alert-dialog.md (with `persistent`) |
| `attrs` | attrs | - | on `<dialog>` (e.g. `data-init="el.showModal()"` when server-opened) |
| `default` | slot | - | body |
| `footer` | slot | - | actions |

## Accessibility

- `aria-labelledby` -> the title id; `aria-describedby` -> the description id (or `aria-label` if there is no visible title).
- Opened with `showModal()`, the browser makes the rest of the page inert, traps Tab inside the dialog, closes on Escape, and returns focus to the element that opened it. All verified in the gallery tests.
- Initial focus goes to the first focusable element. Put `autofocus` on the safest control when the first one is destructive.
- The trigger should have `aria-haspopup="dialog"`.
- The close X has `aria-label="Close"`.

## Behaviour

**Opening.** `data-on:click="document.getElementById('{id}').showModal()"` on any trigger. The default is a Datastar handler, not the HTML invoker commands, because `commandfor`/`command` is Chrome 135+, Firefox 144+, **Safari 26.2+** (MDN browser-compat-data, checked 2026-10-06), so not yet universal. Native alternative for projects that only target current browsers: `<button commandfor="{id}" command="show-modal">` and `command="close"` on the close buttons; no script at all.

**Closing.** Close buttons: `data-on:click="el.closest('dialog').close()"` (or `commandfor`/`command="close"`). Escape: native. Mask click: `data-on:click="evt.target === el && el.close()"` on the `<dialog>`: the overlay fills the viewport, so a click on blank space lands on the `<dialog>` itself (`evt.target === el`); clicks inside the panel have a different target. This replaces fc-web's `ui.js` `data-light-dismiss`. (`closedby="any"` would do it natively but is Safari-preview only.)

**Server-opened (c).** The server may patch a dialog into the page already open: SSE `event: datastar-patch-elements`, `data: mode append`, `data: selector body`, `data: elements <dialog id="dlg-x" data-init="el.showModal()" ...>...</dialog>`. `data-init` runs when the element is patched in. Remove it afterwards with `mode remove`, or close it from the form's success reply by patching a script/`data-init="el.close()"`.

**One-time dialogs** (fc-web `data-open-on-load`, a secret shown once, not dismissible): add `data-init="el.showModal()"` and `data-on:cancel__prevent` so Escape does nothing; give it its own Done button.

## Notes for a template author

- Parameters: `id` (unique, used by the trigger and the aria ids), title, description, mask, width, light-dismiss, closable.
- Slots: body, footer actions.
- The overlay class list is long; emit it as one constant in the template.
- A form inside the dialog posts normally (a), or `@post(..., {contentType: 'form'})` (c).
- `scrollbar-gutter: stable` on `<html>` (carried over from fc-web) leaves a 15px strip beside the overlay on desktops with classic scrollbars; drop it from `kit.css` if undesirable.
