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

## Accessibility

`<pre>` preserves text. A scrollable region should be keyboard reachable: add `tabindex="0"` and an `aria-label` when content can overflow.

## Behaviour

None.

## Notes for a template author

Parameter: the JSON value, pretty-printed by the server and **HTML-escaped**. No syntax highlighting.
