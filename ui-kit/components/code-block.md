# Code block

**Source:** new (Go platform port; the SPA's schema viewer `pre.code-block` with `schema-highlight.ts` classes)  
**Mechanism:** (a) native  

## Purpose

Source code or a document (JSON, TypeScript, PHP, Python, Java) on a dark panel, optionally syntax-highlighted by the server, scrollable in both directions, keyboard reachable, with an optional copy action. `json-block.md` stays the light, unhighlighted payload view; this is the code view.

## Structure

```html
<pre id="schema-code" class="fc-code-block" tabindex="0" aria-label="Schema"><code>{
  <span class="hl-key">&quot;type&quot;</span>: <span class="hl-str">&quot;object&quot;</span>,
  <span class="hl-key">&quot;minimum&quot;</span>: <span class="hl-num">0</span>
}</code></pre>
```

Copy button (a separate control, anywhere on the page):

```html
<button type="button" class="fc-icon-btn" aria-label="Copy" title="Copy" data-on:click="navigator.clipboard.writeText(document.getElementById('schema-code').textContent)"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-copy"/></svg></button>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<pre class="fc-code-block" tabindex="0" aria-label>` | monospace 13px/1.6, `#1e293b` background, `#e2e8f0` text, padding 16px, `white-space: pre`, scrolls, `min-height: 12rem`, `max-height` 60vh | scroll region |
| Code | `<code>` | inherits | the content |
| Highlight spans | `<span class="hl-{kind}">` | `hl-key` sky, `hl-str` green, `hl-num` amber, `hl-kw` violet, `hl-type` cyan, `hl-var` red, `hl-comment` slate italic | from the server's highlighter |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/code-block`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | needed for the copy action and as a patch target |
| `label` | string | - | `aria-label` of the scroll region |
| `class` | string | - | appended on the root |

Slot `default`: the code. Either plain text (escaped by the template) or the highlighter's HTML, which must be escaped text plus `<span class="hl-*">` only (the server's highlighter guarantees it; never pass user HTML).

## Accessibility

`tabindex="0"` and an `aria-label` make the overflowing region reachable and named. The copy control is a labelled button. Colour carries no meaning the text does not have.

## Behaviour

None (a). Copy is one Datastar expression on the button (b), reading the block's `textContent` by id; the page may add feedback (a flash) itself.

## Notes for a template author

- The panel is always dark; in dark mode it is darker than the card (`#0b1220`, a faint inset border) so it stays distinct.
- Highlighting happens on the server; the kit ships the classes only.
