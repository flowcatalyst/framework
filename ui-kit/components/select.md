# Select

**Source:** fc-web themed copy (`crates/fc-web/src/components/select.rs`)  
**Mechanism:** (a) native  

## Purpose

A native `<select>`, with a styled picker where `appearance: base-select` is supported (Chromium).

## Structure

```html
<span class="relative block has-[:disabled]:opacity-50 [&>select]:[appearance:base-select] [&:has(select:open)>svg]:rotate-180" style="--select-checkmark: url(&quot;data:image/svg+xml,...&quot;)"><select class="h-9 w-full appearance-none items-center rounded-lg border border-input bg-card pr-8 pl-3 text-left text-sm transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none [&::picker(select)]:[appearance:base-select] [&::picker(select)]:mt-1 [&::picker(select)]:rounded-lg [&::picker(select)]:border [&::picker(select)]:border-border [&::picker(select)]:bg-popover [&::picker(select)]:p-1 [&::picker(select)]:text-popover-foreground [&::picker(select)]:shadow-sm [&::picker-icon]:hidden [&_optgroup>legend]:px-2 [&_optgroup>legend]:py-1.5 [&_optgroup>legend]:text-xs [&_optgroup>legend]:font-medium [&_optgroup>legend]:text-muted-foreground [&_optgroup>legend]:cursor-default [&_optgroup>legend]:select-none [&_option]:flex [&_option]:items-center [&_option]:gap-2 [&_option]:rounded-md [&_option]:px-2 [&_option]:py-1.5 [&_option]:text-sm [&_option]:outline-none [&_option:hover]:bg-foreground/5 [&_option:focus]:bg-foreground/5 [&_option:checked]:font-medium [&_option::checkmark]:order-1 [&_option::checkmark]:ml-auto [&_option::checkmark]:size-4 [&_option::checkmark]:shrink-0 [&_option::checkmark]:content-[''] [&_option::checkmark]:bg-muted-foreground [&_option::checkmark]:[mask-size:100%_100%] [&_option::checkmark]:[mask-image:var(--select-checkmark)]" id="type" name="type"><option value="a">Anchor</option><option value="p" selected>Partner</option><option value="c">Client</option></select><svg width="1em" height="1em" class="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted-foreground transition-transform" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-chevron-down"/></svg></span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Wrapper | `<span>` | `relative block has-[:disabled]:opacity-50 [&>select]:[appearance:base-select] [&:has(select:open)>svg]:rotate-180` + `style="--select-checkmark: url(...)"` | The style attribute carries the checkmark mask image for the picker |
| Select | `<select>` | `h-9 w-full appearance-none items-center rounded-lg border border-input bg-card pr-8 pl-3 text-left text-sm transition-colors outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none` + picker classes (below) | All other attributes are passed through here |
| Chevron | `<svg>` | `pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted-foreground transition-transform` | Rotates 180deg while `select:open` |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### picker classes (on the select)

| Classes |
|---|
| `[&::picker(select)]:[appearance:base-select] [&::picker(select)]:mt-1 [&::picker(select)]:rounded-lg [&::picker(select)]:border [&::picker(select)]:border-border [&::picker(select)]:bg-popover [&::picker(select)]:p-1 [&::picker(select)]:text-popover-foreground [&::picker(select)]:shadow-sm [&::picker-icon]:hidden [&_optgroup>legend]:px-2 [&_optgroup>legend]:py-1.5 [&_optgroup>legend]:text-xs [&_optgroup>legend]:font-medium [&_optgroup>legend]:text-muted-foreground [&_optgroup>legend]:cursor-default [&_optgroup>legend]:select-none [&_option]:flex [&_option]:items-center [&_option]:gap-2 [&_option]:rounded-md [&_option]:px-2 [&_option]:py-1.5 [&_option]:text-sm [&_option]:outline-none [&_option:hover]:bg-foreground/5 [&_option:focus]:bg-foreground/5 [&_option:checked]:font-medium [&_option::checkmark]:order-1 [&_option::checkmark]:ml-auto [&_option::checkmark]:size-4 [&_option::checkmark]:shrink-0 [&_option::checkmark]:content-[''] [&_option::checkmark]:bg-muted-foreground [&_option::checkmark]:[mask-size:100%_100%] [&_option::checkmark]:[mask-image:var(--select-checkmark)]` |

### state

| State | How |
|---|---|
| invalid | `aria-invalid="true"` |
| disabled | `disabled` (wrapper `has-[:disabled]:opacity-50`) |

## Accessibility

Native `<select>` semantics and keyboard. Needs a label. Do not replace the native element with a div listbox.

## Behaviour

Native. Without `base-select` support the browser draws its normal popup; the field still looks right (`appearance-none` fallback plus the chevron).

Auto-submit on change (fc-web used inline `onchange`): `data-on:change="el.form.requestSubmit()"`.

## Notes for a template author

Parameters: options `(value, label)`, selected value, name/id. The `--select-checkmark` value is a data URI of the Lucide check: `url("data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'><path fill='none' stroke='currentColor' stroke-linecap='round' stroke-linejoin='round' stroke-width='2' d='M20 6L9 17l-5-5'/></svg>")` (percent-encode `%`, `"` and `#` only; in an HTML attribute write the quotes as `&quot;`).
