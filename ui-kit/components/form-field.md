# Form field (FcFormField)

**Source:** hand-built (`form::form_field`)  
**Mechanism:** (a) native; (c) for live validation  

## Purpose

A labelled input with an optional required marker, help text or error message. Lays out in `.fc-form-grid` (two columns, one in narrow containers).

## Structure

```html
<div class="fc-form-grid">
<div class="fc-form-field">
  <label for="hf1" class="fc-field-label">Name <span class="fc-required">*</span></label>
  <input id="hf1" class="fc-input" value="Ada">
  <small class="fc-field-help">As shown in the list</small>
</div>
<div class="fc-form-field">
  <label for="hf2" class="fc-field-label">Email</label>
  <input id="hf2" type="email" class="fc-input" value="bad" aria-invalid="true" aria-describedby="hf2-msg">
  <small id="hf2-msg" class="fc-field-error">Enter a valid email</small>
</div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<div class="fc-form-field [fc-span-2]">` | `min-width: 0`; `fc-span-2` = full grid width |  |
| Label | `<label for class="fc-field-label">` | 13px/500/#475569 | Required marker `<span class="fc-required">*</span>` (red) |
| Control | `input.fc-input` / `select.fc-select` | see `legacy` classes in theme/fc-components.css | Slot |
| Help | `<small class="fc-field-help">` | 12px muted |  |
| Error | `<small class="fc-field-error">` | 12px red | Replaces help when present |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### grid

| Class | Effect |
|---|---|
| `.fc-form-grid` | 2 columns, 16px/20px gap; 1 column when the container is <= 640px (`.fc-section-body` is the container) |
| `.fc-span-2` | spans both columns |

## Accessibility

- `label for` = control `id`. The required marker is visual; also set `required` on the control (`aria-required` is implied).
- Link the error: control `aria-invalid="true"` and `aria-describedby="{id}-msg"` pointing at the error `<small>` (added; fc-web did not link them).

## Behaviour

Native (a). **Live validation (c)** (e.g. the email-domain check): on the input `data-on:input__debounce.300ms="@post('/users/check-email', {contentType: 'form'})"` (or bind `data-bind:email` and send the signal). Request: `POST /users/check-email` with `Datastar-Request: true`, body form-encoded `email=...`, header `X-CSRF-Token`. Reply (SSE):

```
event: datastar-patch-elements
data: elements <small id="hf2-msg" class="fc-field-error">Domain not allowed.</small>

```
Keep a stable `id` on the message element (even when empty) so the patch morphs it; clear it by patching an empty one.

## Notes for a template author

Parameters: label, `for` id, required, span, help, error. Slot: the control. Escape all text.
