# Code editor

**Source:** new (Go platform port)  
**Mechanism:** (a) native textarea fallback; (d) island (`<fc-code-editor>` web element, CodeMirror 6)  

## Purpose

Editing or showing JSON, YAML or plain text with line numbers, bracket matching, folding and highlighting. It behaves as one form field: it posts under its `name`, binds to a Datastar signal like the other inputs, and can be read only. Without script the field is a plain textarea.

## Structure

```html
<fc-code-editor class="block" language="json">
  <textarea class="field-sizing-content min-h-16 w-full rounded-lg border border-input bg-card px-3 py-2 text-sm transition-colors outline-none placeholder:text-muted-foreground focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 aria-invalid:border-destructive aria-invalid:focus-visible:ring-destructive focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 font-mono" id="payload" name="payload" aria-label="Payload" spellcheck="false">{&quot;a&quot;: 1}</textarea>
</fc-code-editor>
```

(The textarea's classes are `textarea.md`'s plus `font-mono`.)

## Options

### language

| Value | Effect |
|---|---|
| (none) | plain text |
| `json` | JSON highlighting and bracket matching |
| `yaml` | YAML highlighting |

### readonly

`readonly` on the element and on the textarea: the editor cannot be edited or focused for typing, selection and copying still work.

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/code-editor`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | on the textarea (a `Field` label's `for`) |
| `name` | string | - | the form field name |
| `value` | string | - | the initial text |
| `language` | string | - | `json`, `yaml` or none |
| `readOnly` | boolean | false |  |
| `label` | string | - | `aria-label` of the textarea and the editor |
| `rows` | number | - | height of the fallback textarea |
| `invalid` | boolean | false | `aria-invalid="true"` on the textarea |
| `describedBy` | string | - |  |
| `class` | string | - | appended on the root |
| `attrs` | attrs | - | on the textarea (`{"data-bind:payload": true}`) |

## Accessibility

The editor's content element carries the label as `aria-label`. CodeMirror's own keyboard model applies; Tab inserts indentation, so Escape then Tab leaves the editor.

## Behaviour

`fc-code-editor.js` (loaded by the page; it contains CodeMirror) defines the element. On connection it replaces the textarea with a hidden `<input>` carrying the textarea's `name` and `data-bind*` attributes (so Datastar binds the signal to the input) and the editor. Every change writes the text into the input and dispatches a bubbling `input` event on it. A form `reset` restores the initial text. The editor does not follow later writes to the input or the signal.

## Notes for a template author

- Pass the initial text only as the textarea's content, never in an attribute or a script.
- Load `fc-code-editor.js` on pages that use the part and nowhere else.
- Colours come from the kit tokens (`--card`, `--border`, `--primary`, ...) and follow `<html class="dark">` when the editor is created.
