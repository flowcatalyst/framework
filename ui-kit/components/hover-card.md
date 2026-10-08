# Hover card

**Source:** upstream Topcoat UI registry 0.9.0 (`hover_card.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) CSS only  

## Purpose

Richer supplementary content shown below its trigger after a 300ms delay, on hover or focus.

## Structure

```html
<span class="group relative inline-flex"><a href="/u/ada" aria-describedby="hc1" class="underline underline-offset-4">@ada</a><span id="hc1" class="flex flex-col gap-2 invisible absolute top-full left-0 z-50 mt-2 w-64 rounded-lg border border-border bg-popover p-4 text-popover-foreground opacity-0 shadow-sm [transition:opacity_150ms_ease-out_300ms,visibility_150ms_allow-discrete_300ms] group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100"><strong>Ada Lovelace</strong><span class="text-sm text-muted-foreground">Anchor user since 2024.</span></span></span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| hover_card | `<span>` | `group relative inline-flex` |  |
| hover_card_content | `<span>` | `flex flex-col gap-2` + `invisible absolute top-full left-0 z-50 mt-2 w-64 rounded-lg border border-border bg-popover p-4 text-popover-foreground opacity-0 shadow-sm [transition:opacity_150ms_ease-out_300ms,visibility_150ms_allow-discrete_300ms] group-hover:visible group-hover:opacity-100 group-focus-within:visible group-focus-within:opacity-100` | The 300ms is the delay (second value in each transition) |

## Accessibility

Supplementary only. The trigger is a link/button and the card is linked with `aria-describedby`. Content is not focusable, so nothing essential goes in it. Touch has no equivalent.

## Behaviour

CSS only.

## Notes for a template author

Parameters: trigger, id, content slot.
