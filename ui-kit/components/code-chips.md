# Code chips

**Source:** hand-built (`ui::code_chips`)  
**Mechanism:** (a) native  

## Purpose

An event-type code (`app:subdomain:aggregate:event`) shown as four coloured monospace segments.

## Structure

```html
<span class="fc-code"><span class="fc-code-seg app">orders</span><span class="fc-code-sep">:</span><span class="fc-code-seg subdomain">billing</span><span class="fc-code-sep">:</span><span class="fc-code-seg aggregate">invoice</span><span class="fc-code-sep">:</span><span class="fc-code-seg event">created</span></span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<span class="fc-code">` | monospace 13px, no wrap |  |
| Segment | `<span class="fc-code-seg {kind}">` | `padding 2px 4px; radius 3px` + kind colours | kind by index: app, subdomain, aggregate, event (index 3 and beyond use `event`) |
| Separator | `<span class="fc-code-sep">:</span>` | `#94a3b8` |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### kind

| Position | Class | Light colours |
|---|---|---|
| 1st | app | blue |
| 2nd | subdomain | green |
| 3rd | aggregate | amber |
| 4th+ | event | purple |

## Accessibility

Plain text; the concatenated text equals the code. Colour is decoration.

## Behaviour

None.

## Notes for a template author

Parameter: the code string; the template splits on `:`. Escape each segment. Dark-mode colours are new.
