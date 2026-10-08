# Radio group

**Source:** upstream Topcoat UI registry 0.9.0 (`radio_group.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A group of native radio buttons.

## Structure

```html
<div role="radiogroup" aria-label="Plan" class="grid gap-3">
  <div class="flex items-center gap-2"><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="radio" class="peer size-4 shrink-0 appearance-none rounded-full border border-input bg-background transition-colors outline-none checked:border-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="r1" name="plan" value="free" checked><span class="pointer-events-none absolute inset-0 m-auto size-2 rounded-full bg-primary opacity-0 transition-opacity peer-checked:opacity-100"></span></span><label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50" for="r1">Free</label></div>
  <div class="flex items-center gap-2"><span class="peer relative inline-flex shrink-0 has-[:disabled]:opacity-50"><input type="radio" class="peer size-4 shrink-0 appearance-none rounded-full border border-input bg-background transition-colors outline-none checked:border-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none" id="r2" name="plan" value="pro"><span class="pointer-events-none absolute inset-0 m-auto size-2 rounded-full bg-primary opacity-0 transition-opacity peer-checked:opacity-100"></span></span><label class="flex items-center gap-2 text-sm leading-none font-medium select-none peer-disabled:pointer-events-none peer-disabled:opacity-50 peer-has-[:disabled]:pointer-events-none peer-has-[:disabled]:opacity-50 has-[+:disabled]:pointer-events-none has-[+:disabled]:opacity-50 has-[:disabled]:pointer-events-none has-[:disabled]:opacity-50" for="r2">Pro</label></div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Group | `<div role=radiogroup>` | `grid gap-3` | Needs an accessible name |
| Item wrapper | `<span>` | `peer relative inline-flex shrink-0 has-[:disabled]:opacity-50` |  |
| Input | `<input type=radio>` | `peer size-4 shrink-0 appearance-none rounded-full border border-input bg-background transition-colors outline-none checked:border-primary focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none` | same `name` for the group |
| Dot | `<span>` | `pointer-events-none absolute inset-0 m-auto size-2 rounded-full bg-primary opacity-0 transition-opacity peer-checked:opacity-100` |  |

## Accessibility

Native radios give arrow-key navigation within a `name` group. Name the group (`aria-label`/`aria-labelledby`, or `<fieldset><legend>`). Label each radio.

## Behaviour

Native.

## Notes for a template author

Differs from upstream: the radio border is `border-input` (upstream `border-border`), matching the themed checkbox.
