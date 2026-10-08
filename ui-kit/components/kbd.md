# Kbd

**Source:** upstream Topcoat UI registry 0.9.0 (`kbd.rs`), not installed in fc-web; theme tokens applied  
**Mechanism:** (a) native  

## Purpose

A keyboard key hint; group several in a `<span>`.

## Structure

```html
<span class="inline-flex items-center gap-1 whitespace-nowrap"><kbd class="inline-flex h-5 w-fit min-w-5 shrink-0 items-center justify-center gap-1 rounded-sm border border-border bg-foreground/5 px-1.5 font-sans text-xs font-medium text-muted-foreground">Ctrl</kbd><kbd class="inline-flex h-5 w-fit min-w-5 shrink-0 items-center justify-center gap-1 rounded-sm border border-border bg-foreground/5 px-1.5 font-sans text-xs font-medium text-muted-foreground">K</kbd></span>
```

## Accessibility

`<kbd>` is semantic. Spell out symbols that screen readers read poorly.

## Behaviour

None.

## Notes for a template author

Slot: key text. Group wrapper classes: `inline-flex items-center gap-1 whitespace-nowrap`.
