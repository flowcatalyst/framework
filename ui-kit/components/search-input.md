# Search input

**Source:** hand-built (`ui::search_input`)  
**Mechanism:** (a) native; (c) for live search  

## Purpose

A search box with a leading icon, submitting its GET form on Enter.

## Structure

```html
<div class="fc-input-icon w-80 max-w-full">
  <svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-search"/></svg>
  <input type="search" name="q" class="fc-input" placeholder="Search users" aria-label="Search users">
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Wrapper | `<div class="fc-input-icon w-80 max-w-full">` | `position: relative` |  |
| Icon | `<svg>` | `.fc-input-icon > svg` absolute, left .625rem |  |
| Input | `<input type="search" class="fc-input">` | `.fc-input` (+ left padding 2rem) |  |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/search-input`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `name` | string | q |  |
| `placeholder` | string | - | also the `aria-label` |
| `value` | string | - | omitted when empty |
| `class` | string | w-80 max-w-full | replaces the wrapper's width classes |
| `attrs` | attrs | - | on the `<input>` (live search) |

## Accessibility

`aria-label` (the placeholder text) names it; the placeholder is not a label.

## Behaviour

Native GET form submit. **Live search (c):** `data-bind:q` on the input plus `data-on:input__debounce.300ms="@get('/users')"`; Datastar sends all signals as `?datastar=<json>` (GET) and the server patches the table body. Client-only filtering: see `data-table.md`.

## Notes for a template author

Parameters: name, placeholder, value.
