# JSON block

**Source:** hand-built (`ui::json_block`)  
**Mechanism:** (a) native  

## Purpose

Pretty-printed JSON in a scrollable `<pre>`.

## Structure

```html
<pre class="fc-json">{
  &quot;id&quot;: &quot;evt_01&quot;,
  &quot;ok&quot;: true
}</pre>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<pre class="fc-json">` | monospace 12px, `--subtle` background, 1px border, 6px radius, `max-height: 28rem; overflow: auto; white-space: pre` |  |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/json-block`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `value` | any | - | pretty-printed by the template (below), then HTML-escaped |
| `attrs` | attrs | - | extra attributes on the `<pre>` (e.g. `tabindex`, `aria-label`) |
| `class` | string | - | appended to the root element's class list (concatenated, not merged); after `fc-json` |

Pretty-print format (identical in every implementation): 2-space indent, `"key": value` (one space after the colon), one member or element per line, `{}` and `[]` when empty, object keys in the order given (never sorted), no `\u003c`/`\u003e`/`\u0026` escapes for `<`, `>`, `&`; the HTML escape is applied to the formatted text afterwards. Two traps: Go's `json.Marshal` HTML-escapes unless disabled, and Go maps lose key order, so decode the value as raw JSON (`json.RawMessage` / an ordered decoder); Jackson's default pretty printer writes `"a" : 1` (space before the colon), so configure it.

## Accessibility

`<pre>` preserves text. A scrollable region should be keyboard reachable: add `tabindex="0"` and an `aria-label` when content can overflow.

## Behaviour

None.

## Notes for a template author

Parameter: the JSON value, pretty-printed by the server and **HTML-escaped**. No syntax highlighting.
