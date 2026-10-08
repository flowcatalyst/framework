# Avatar

**Source:** upstream Topcoat UI registry 0.9.0 (`avatar.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A round user image with an initials fallback.

## Structure

```html
<span class="relative flex shrink-0 overflow-hidden rounded-full size-10 text-sm">
  <img src="/u/ada.png" alt="Ada Lovelace" class="absolute inset-0 size-full object-cover">
  <span class="flex size-full items-center justify-center bg-foreground/10 font-medium text-foreground select-none">AL</span>
</span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<span>` | `relative flex shrink-0 overflow-hidden rounded-full` + size |  |
| Image | `<img>` | `absolute inset-0 size-full object-cover` | Optional; covers the fallback when it loads |
| Fallback | `<span>` | `flex size-full items-center justify-center bg-foreground/10 font-medium text-foreground select-none` | Initials |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### size (default md)

| Value | Classes added |
|---|---|
| sm | `size-8 text-xs` |
| md | `size-10 text-sm` |
| lg | `size-12 text-base` |

## Accessibility

Give the image meaningful `alt` (the person's name). If the avatar sits next to the name in text, use `alt=""`.

## Behaviour

The image overlays the fallback; a failed image reveals the initials (no script).

## Notes for a template author

Parameters: size, image URL (optional), alt, initials.
