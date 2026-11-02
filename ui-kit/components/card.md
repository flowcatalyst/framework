# Card

**Source:** upstream Topcoat UI registry 0.9.0 (`card.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A bordered surface with header, content and footer regions.

## Structure

```html
<div class="flex flex-col gap-5 rounded-xl border border-border bg-card py-6 text-card-foreground shadow-sm">
  <div class="flex flex-col gap-1.5 px-6"><h3 class="text-base leading-none font-semibold">Title</h3><p class="text-sm text-muted-foreground">Description</p></div>
  <div class="px-6">Content</div>
  <div class="flex items-center gap-2 px-6">Footer actions</div>
</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Card | `<div>` | `flex flex-col gap-5 rounded-xl border border-border bg-card py-6 text-card-foreground shadow-sm` | Root |
| Header | `<div>` | `flex flex-col gap-1.5 px-6` | Optional |
| Title | `<h3>` | `text-base leading-none font-semibold` | Choose the heading level for the page outline |
| Description | `<p>` | `text-sm text-muted-foreground` |  |
| Content | `<div>` | `px-6` |  |
| Footer | `<div>` | `flex items-center gap-2 px-6` |  |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/card`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `title` | string | - | text (escaped); not a slot |
| `description` | string | - | text (escaped); not a slot |
| `headingLevel` | number | 3 | 1-6; the title element is `<h{headingLevel}>` |
| `class` | string | - | appended to the root element's class list (concatenated, not merged) |

Slots: `default` (content) and `footer`. The header `<div>` renders only when `title` or `description` is given (and each of the two elements only when its prop is given); the content `<div>` and the footer `<div>` render only when their slot is non-empty.

## Accessibility

Use a real heading element in the title at the right level. A card is not a landmark; add `role="region"` + `aria-labelledby` only if it is a navigation target.

## Behaviour

None.

## Notes for a template author

Title and description are text props (not slots); slots are `default` and `footer`; the header, content and footer `<div>`s render only when there is something to put in them (see Props). Parameter: heading level. Dark mode: `bg-card`/`border-border` swap automatically.
