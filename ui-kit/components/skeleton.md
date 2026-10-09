# Skeleton

**Source:** upstream Topcoat UI registry 0.9.0 (`skeleton.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A pulsing placeholder box for content that is loading.

## Structure

```html
<div class="animate-pulse rounded-md bg-foreground/10 h-4 w-3/4" aria-hidden="true"></div>
```

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/skeleton`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `class` | string | - | size/shape classes (`h-4 w-3/4`) |

## Accessibility

Placeholders are decorative: `aria-hidden="true"` on each; announce loading once on the container (`aria-busy="true"` or a visually hidden "Loading").

## Behaviour

CSS animation only. Add `motion-reduce:animate-none` to honour reduced motion.

## Notes for a template author

Parameter: size classes (width/height/shape). The server replaces the skeleton region with the real content by patching its id.
