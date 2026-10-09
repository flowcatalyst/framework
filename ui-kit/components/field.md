# Field (form layout)

**Source:** fc-web themed copy (`crates/fc-web/src/components/field.rs`)  
**Mechanism:** (a) native  

## Purpose

The form-layout kit: groups a label, a control, a description and an error into a unit; also fieldsets, legends, groups and separators. The label turns red when its control is `aria-invalid`.

## Structure

```html
<div class="@container/field-group flex flex-col gap-5">
  <div role="group" data-slot="field" class="group/field flex min-w-0 flex-col gap-2">
    <label for="f-email" data-slot="field-label" class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50 leading-snug group-has-[:disabled]/field:opacity-50 group-has-[[aria-invalid=true]]/field:text-destructive group-data-[invalid=true]/field:text-destructive">Email</label>
    <input id="f-email" type="email" aria-invalid="true" aria-describedby="f-email-err" class="h-9 w-full min-w-0 rounded-lg border border-input bg-card px-3 text-sm transition-colors outline-none placeholder:text-muted-foreground file:mr-3 file:h-full file:border-0 file:bg-transparent file:text-sm file:font-medium focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50">
    <p class="text-sm leading-relaxed text-muted-foreground [&_a]:underline [&_a]:underline-offset-4">We never share it.</p>
    <div id="f-email-err" role="alert" class="text-sm text-destructive">Enter a valid email address.</div>
  </div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| field_group | `<div>` | `@container/field-group flex flex-col gap-5` | Optional wrapper; its `@container/field-group` drives the `responsive` orientation |
| field_set | `<fieldset>` | `flex min-w-0 flex-col gap-5` | Groups related fields |
| field_legend | `<legend>` | `mb-3` + variant | Names the fieldset |
| field | `<div role="group" data-slot="field">` | `group/field flex min-w-0` + orientation | One labelled control |
| field_content | `<div>` | `flex min-w-0 flex-1 flex-col gap-1.5` | Wraps title/description beside a control (horizontal rows) |
| field_label | `<label data-slot="field-label">` | `flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50` + `leading-snug group-has-[:disabled]/field:opacity-50 group-has-[[aria-invalid=true]]/field:text-destructive group-data-[invalid=true]/field:text-destructive` | Red when the control is `aria-invalid=true`, dimmed when disabled |
| field_title | `<div>` | `text-sm leading-snug font-medium` | A non-label heading for a row |
| field_description | `<p>` | `text-sm leading-relaxed text-muted-foreground [&_a]:underline [&_a]:underline-offset-4` | Help text |
| field_separator | `<div><span class="empty:hidden">` | `flex items-center gap-3 text-xs text-muted-foreground has-[>span:empty]:gap-0 before:h-px before:flex-1 before:bg-border after:h-px after:flex-1 after:bg-border` | Rule with optional centre text |
| field_error | `<div role="alert">` | `text-sm text-destructive` | Error message |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### field orientation (default vertical)

| Value | Classes added to `field` |
|---|---|
| vertical | `flex-col gap-2` |
| horizontal | `flex-row items-center gap-3 [&>[data-slot=field-label]]:flex-1` |
| responsive | `flex-col gap-2 @md/field-group:flex-row @md/field-group:items-start @md/field-group:gap-4 @md/field-group:[&>[data-slot=field-label]]:w-1/3 @md/field-group:[&>[data-slot=field-label]]:shrink-0` |

### field_legend variant (default legend)

| Value | Classes added |
|---|---|
| legend | `text-base font-semibold` |
| label | `text-sm font-medium` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/field` and `fixtures/field-group`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `label` | string | - | field_label text; omitted when empty (e.g. a horizontal row with field_content) |
| `for` | string | - | the control's id |
| `orientation` | enum(vertical\|horizontal\|responsive) | vertical | responsive needs a field_group ancestor |
| `description` | string | - | field_description after the control |
| `error` | string | - | field_error (`role="alert"`), rendered only when non-empty |
| `errorId` | string | {for}-err | the error's id (the control's `aria-describedby` points at it) |
| `required` | boolean | false | a red `*` after the label text (`<span class="text-destructive" aria-hidden="true">*</span>`); the control carries `required` itself |
| `class` | string | - |  |
| `attrs` | attrs | - |  |
| `default` | slot | - | the control (marked `aria-invalid`/`aria-describedby` by the caller) |

**field_group**: `class`, default slot (the fields).

## Accessibility

- Every control inside needs its label (`<label for>`); `field_label` is the label.
- Link error/help text with `aria-describedby`; mark the control `aria-invalid="true"` (this is what turns the label red: `group-has-[[aria-invalid=true]]/field:text-destructive`).
- `field_error` carries `role="alert"`: only render it when there is an error, or it announces on every render. A server patch that inserts it is announced once.
- `field_set` + `field_legend` is the correct grouping for radio sets.

## Behaviour

CSS only (`group/field` + `has-[...]` variants).

Live validation is mechanism (c): the input carries `data-on:input__debounce.300ms="@post('/x/check', {contentType: 'form'})"`; the server answers with a `datastar-patch-elements` event for the field's error element (see `form-field.md` for the request/patch pair).

## Notes for a template author

- Parameters: orientation, legend variant, `for`/`id`, invalid flag, error text, help text.
- Slots: control, title, description, error.
- `responsive` needs an ancestor `field_group` (container query `@md/field-group`).
