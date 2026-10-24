# Diagram

**Source:** new (Go platform port; the SPA's `ProcessDiagram`)  
**Mechanism:** (a) native fallback; (d) island (`<fc-diagram>` web element + a Mermaid bundle)  

## Purpose

A Mermaid diagram drawn from its source text, with the source kept below it. The server sends only the source; the browser renders the SVG with Mermaid in its strict security level. Without script the source is still readable.

## Structure

```html
<fc-diagram class="block" data-bundle="/assets/mermaid.min.js">
  <div class="fc-diagram-view overflow-auto rounded-md border border-border bg-card p-4 [&_svg]:mx-auto [&_svg]:block [&_svg]:h-auto [&_svg]:max-w-full" role="img" aria-label="Order flow" hidden></div>
  <details class="mt-3 text-sm">
    <summary class="cursor-pointer text-muted-foreground">Diagram source</summary>
    <pre class="fc-code-block mt-2" tabindex="0" aria-label="Order flow source"><code>graph TD
  A--&gt;B</code></pre>
  </details>
</fc-diagram>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<fc-diagram>` | `block`, `data-bundle` = URL of the Mermaid bundle | custom element |
| View | `<div class="fc-diagram-view">` | bordered, scrollable; `role="img"`, `aria-label`; `hidden` until drawn | the SVG goes here |
| Source | `<details>` with a `code-block` `<pre>` | closed | the Mermaid text; opens itself when the drawing fails |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/diagram`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `label` | string | - | the diagram's accessible name; the source's is `label + " source"` |
| `bundle` | string | - | URL of the Mermaid bundle |
| `class` | string | - | appended on the root |

Slot `default`: the Mermaid source as plain text (escaped by the template).

## Accessibility

The drawing is one image with the label; the full content is in the source, which is reachable (`tabindex="0"` on the scroll region) and is opened by the summary.

## Behaviour

`fc-diagram.js` (loaded by the page, once) defines the element. On connection it reads the source, loads the bundle from `data-bundle` the first time any diagram on the page needs it (a script tag added then, so pages without diagrams never fetch the 5 MB file), calls `mermaid.initialize({ startOnLoad: false, securityLevel: "strict", theme })` (the dark theme when `<html class="dark">`) and puts `mermaid.render`'s SVG into the view. A source Mermaid rejects, or a bundle that does not load, leaves the view hidden and opens the source.

## Notes for a template author

- Never put the source in an attribute or a script; it is element text, so the template's escaping is the only encoding needed.
- The page needs `script-src 'self'` only; Mermaid inserts `<style>` and `style=""`, which the kit's `style-src 'unsafe-inline'` already allows.
- The bundle is the unmodified `dist/mermaid.min.js` of the npm package, pinned and hash-checked by the host.
