# Tag

**Source:** hand-built (`ui::tag, Severity`)  
**Mechanism:** (a) native  

## Purpose

A PrimeVue `Tag` with a severity. In fc-web's newer sections this role is played by `badge`; `tag` remains for the older hand-built sections.

## Structure

```html
<span class="fc-tag fc-tag-success">Active</span>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<span>` | `.fc-tag` (12px, bold, 2px radius, `padding .2rem .45rem`) + severity |  |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### severity

| Value | Class | Fill |
|---|---|---|
| success | fc-tag-success | #16a34a |
| info | fc-tag-info | #0284c7 |
| warn | fc-tag-warn | #ea580c |
| danger | fc-tag-danger | #dc2626 |
| secondary | fc-tag-secondary | `--fc-secondary-bg` |
| primary | fc-tag-primary | `--primary` |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/tag`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `severity` | enum(success\|info\|warn\|danger\|secondary\|primary) | secondary |  |
| `label` | string | - |  |

## Accessibility

Text carries the meaning (never colour alone).

## Behaviour

None.

## Notes for a template author

Parameters: label, severity. See `badge.md` for the Tailwind equivalent (`success|info|warn|destructive|secondary|primary|outline`).
