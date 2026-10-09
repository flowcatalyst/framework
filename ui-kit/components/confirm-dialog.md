# Confirm dialog (hand-built)

**Source:** hand-built (`ui::confirm_dialog`)  
**Mechanism:** (b) Datastar opens/closes a native modal `<dialog>`; submit is a native POST (a) or (c)  

## Purpose

A confirm-then-POST modal (the SPA's `useConfirm` + `ConfirmDialog`): a warning icon, a message, Cancel and a confirming submit.

## Structure

```html
<button type="button" class="fc-btn fc-btn-danger-outline" data-on:click="document.getElementById('dlg-confirm').showModal()" aria-haspopup="dialog">Reset 2FA</button>

<dialog id="dlg-confirm" class="fc-dialog w-[28rem] max-w-[calc(100vw-2rem)]" closedby="any" aria-labelledby="dlg-confirm-title"
        data-on:click="evt.target === el && el.close()">
  <form method="post" action="/users/42/reset-2fa">
    <div class="fc-dialog-header"><span id="dlg-confirm-title">Reset 2FA</span></div>
    <div class="fc-dialog-body flex items-start gap-3 text-subtle-foreground">
      <svg width="1em" height="1em" class="size-6 mt-0.5 shrink-0 text-red-600" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-triangle-alert"/></svg>
      <p>Are you sure you want to reset two-factor authentication for Ada?</p>
    </div>
    <input type="hidden" name="csrf" value="{token}">
    <div class="fc-dialog-footer">
      <button type="button" class="fc-btn fc-btn-text" autofocus data-on:click="el.closest('dialog').close()">Cancel</button>
      <button type="submit" class="fc-btn fc-btn-danger">Reset</button>
    </div>
  </form>
</dialog>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Dialog | `<dialog id class="fc-dialog w-[28rem] max-w-[calc(100vw-2rem)]" closedby="any" aria-labelledby>` | `.fc-dialog` (margin auto, no padding, 8px radius, shadow; `::backdrop` rgb(15 23 42 / .4)) | Native `::backdrop` is the mask (this one is not full-viewport like `dialog`) |
| Header | `div.fc-dialog-header > span#{id}-title` | 18px/600 |  |
| Body | `div.fc-dialog-body.flex.items-start.gap-3.text-subtle-foreground` |  | Icon (`size-6`, red-600 for danger, amber-600 otherwise) + `<p>` message |
| Hidden fields | `<input type="hidden">` |  | e.g. CSRF, record id |
| Footer | `div.fc-dialog-footer` | right-aligned | Cancel `fc-btn fc-btn-text` (autofocus), confirm `fc-btn fc-btn-{danger\|warn\|primary}` `type=submit` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### tone

| Value | Confirm class | Icon class |
|---|---|---|
| danger | fc-btn-danger | `mt-0.5 shrink-0 text-red-600` |
| warn | fc-btn-warn | `mt-0.5 shrink-0 text-amber-600` |
| default | fc-btn-primary | `mt-0.5 shrink-0 text-amber-600` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/confirm-dialog`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - |  |
| `action` | string | - | POST target |
| `title` | string | - |  |
| `message` | string | - |  |
| `confirmLabel` | string | - |  |
| `tone` | enum(default\|danger\|warn) | default |  |
| `hidden` | list<{name, value}> | [] | record id, CSRF token if any |
| `formAttrs` | attrs | - | e.g. `data-on:submit` for `@post` (c) |

## Accessibility

- Native modal: focus trap, Escape, focus return (verified).
- `aria-labelledby` -> title id. Add `role="alertdialog"` and `aria-describedby` for destructive confirmations (`alert-dialog.md` is the fuller pattern).
- Cancel has `autofocus` so the safe action is focused first (added).

## Behaviour

Open: `data-on:click="document.getElementById('{id}').showModal()"`. Close: Cancel `data-on:click="el.closest('dialog').close()"`; Escape native; mask click `data-on:click="evt.target === el && el.close()"` on the `<dialog>` (the dialog has no padding overlay here, so only a `::backdrop` click, which targets the dialog, matches; `closedby="any"` does the same natively where supported). Replaces fc-web's `commandfor`/`command` and inline `ui.js`.

Submit is a normal POST (a). **(c):** put `data-on:submit="@post('/users/42/reset-2fa', {contentType: 'form'})"` on the form; reply: patch a flash into `#flash-stack`, patch the changed record region, and close the dialog by patching `<dialog id="dlg-confirm" data-init="el.close()">` or by adding `el.closest('dialog').close()` after the action in the same `data-on:submit` expression.

## Notes for a template author

Parameters: id, action URL, title, message, confirm label, tone, hidden fields (CSRF). The `fc-dialog` classes are in `theme/fc-components.css`.
