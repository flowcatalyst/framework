# Legacy button (.fc-btn) and icon button (.fc-icon-btn)

**Source:** hand-built (`ui.rs` `Btn`, `styles.css`)  
**Mechanism:** (a) native  

## Purpose

The older hand-built PrimeVue-style button used by the hand-built components (table toolbar, confirm dialog, cursor pager, drawer, empty state). Prefer `button` for new work; it is kept because the hand-built components' markup uses it.

## Structure

```html
<button class="fc-btn fc-btn-primary" type="button">Label</button>
```

```html
<button type="button" class="fc-icon-btn" aria-label="Refresh"><svg width="1em" height="1em" class="size-[1rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-refresh-cw"/></svg></button>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Root | `<button>` or `<a>` | `fc-btn fc-btn-{variant} [fc-btn-sm] [fc-btn-block]` | Defined in theme/fc-components.css |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### variant

| Value | Class | Look |
|---|---|---|
| primary | fc-btn-primary | emerald fill |
| primary-outline | fc-btn-primary-outline | emerald outline |
| secondary | fc-btn-secondary | grey fill |
| outline | fc-btn-outline | grey outline |
| warn-outline | fc-btn-warn-outline | orange outline |
| danger-outline | fc-btn-danger-outline | red outline |
| danger | fc-btn-danger | red fill |
| warn | fc-btn-warn | orange fill |
| text | fc-btn-text | grey text |
| text-primary | fc-btn-text-primary | emerald text |
| link | fc-btn-link | link style, no side padding |

### size / layout

| Value | Class |
|---|---|
| small | fc-btn-sm |
| full width | fc-btn-block |

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/legacy-button` and `fixtures/icon-button`.

| Prop | Type | Default | Notes |
|---|---|---|---|
| `variant` | enum(primary\|primary-outline\|secondary\|outline\|warn-outline\|danger-outline\|danger\|warn\|text\|text-primary\|link) | primary | `fc-btn-{variant}` |
| `small` | boolean | false | `fc-btn-sm` |
| `block` | boolean | false | `fc-btn-block` |
| `type` | string | button |  |
| `href` | string | - | renders `<a>`; disabled drops the href and sets `aria-disabled="true"` |
| `disabled` | boolean | false |  |
| `icon` | string | - | leading icon, class `size-[1rem]` |
| `class` | string | - |  |
| `attrs` | attrs | - |  |
| `default` | slot | - | label |

**Icon button** (`.fc-icon-btn`): `icon` (string), `label` (string, required: `aria-label`), `type` (button), `class`, `attrs`.

## Accessibility

Same as `button`: native `<button>`, `type` required, `aria-label` on icon-only buttons. Focus ring is `outline: 2px solid var(--primary)` in `.fc-btn:focus-visible`.

## Behaviour

None. Disabled: `disabled` attribute or `aria-disabled="true"` (both get `opacity: .6; pointer-events: none`).

## Notes for a template author

Parameters: variant, size, block. Slot: content. `.fc-icon-btn` is a 2rem round ghost icon button (drawer X, refresh).
