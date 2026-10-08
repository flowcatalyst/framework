# Separator

**Source:** upstream Topcoat UI registry 0.9.0 (`separator.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A horizontal or vertical rule.

## Structure

```html
<hr class="shrink-0 border-0 bg-border h-px w-full">
<hr aria-orientation="vertical" class="shrink-0 border-0 bg-border h-full w-px">
```

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### orientation (default horizontal)

| Value | Classes added | Attribute |
|---|---|---|
| horizontal | `h-px w-full` | - |
| vertical | `h-full w-px` | `aria-orientation="vertical"` |

### Base

| Classes |
|---|
| `shrink-0 border-0 bg-border` |

## Accessibility

`<hr>` has the implicit `separator` role. For purely decorative rules prefer `role="none"`.

## Behaviour

None.

## Notes for a template author

Parameter: orientation. A vertical separator needs a parent with a height (`flex h-6 items-center`).
