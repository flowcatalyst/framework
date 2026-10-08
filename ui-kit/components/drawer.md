# Drawer (record drawer)

**Source:** hand-built (`drawer::drawer_frame / drawer_header`) (EntityDrawer)  
**Mechanism:** (b) Datastar signal; (c) content loaded by the server  

## Purpose

A non-modal right panel over a list (the SPA's `EntityDrawer`): no mask, the list stays scrollable and clickable underneath, clicking another row switches the record, closed with its X or Escape.

## Structure

```html
<aside class="fc-drawer" role="complementary" aria-label="User" data-drawer style="display: none"
       data-show="$selUser !== ''"
       data-on:keydown__window="evt.key === 'Escape' && $selUser !== '' && !document.querySelector('dialog[open]:not([data-drawer]), [popover]:popover-open') && ($selUser = '')">
  <button type="button" class="fc-icon-btn absolute top-[1.1rem] right-[1.1rem] z-10" aria-label="Close" data-on:click="$selUser = ''"><svg width="1em" height="1em" class="size-[1.2rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></button>
  <header class="fc-drawer-header pr-14">
    <div class="fc-drawer-titles">
      <div class="min-w-0">
        <h2 class="fc-drawer-title" data-text="$selUser"></h2>
        <p class="fc-drawer-subtitle" data-text="$selEmail"></p>
      </div>
      <div class="flex shrink-0 items-center gap-2"><span class="inline-flex w-fit shrink-0 items-center justify-center gap-1 rounded-md border px-2 py-0.5 text-xs font-bold whitespace-nowrap border-transparent bg-success text-success-foreground">Active</span></div>
    </div>
  </header>
  <div class="fc-drawer-body">
<section class="fc-form-section">
  <header class="fc-section-header">
    <div>
      <h3 class="fc-section-title">Details</h3>
    </div>
    <button type="button" class="fc-btn fc-btn-text-primary">Edit</button>
  </header>
  <div class="fc-section-body">
<div class="fc-detail-grid"><div class="fc-detail-field">
  <span class="fc-field-label">Name</span>
  <div class="fc-detail-value"><span data-text="$selUser"></span></div>
</div><div class="fc-detail-field">
  <span class="fc-field-label">Email</span>
  <div class="fc-detail-value"><span data-text="$selEmail"></span></div>
</div></div>
  </div>
</section>
  </div>
  <div class="fc-drawer-footer"><button type="button" class="fc-btn fc-btn-text" data-on:click="$selUser = ''">Close</button></div>
</aside>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Frame | `<aside class="fc-drawer" role="complementary" aria-label data-drawer>` | `position: fixed; inset: 0 0 0 auto; z-index: 51; width: min(560px, 100vw - 24px); shadow -8px 0 32px` | `data-show="$sel !== ''"`; hidden until a record is selected |
| Close | `<button class="fc-icon-btn absolute top-[1.1rem] right-[1.1rem] z-10" aria-label="Close">` |  | `data-on:click="$sel = ''"` |
| Header | `<header class="fc-drawer-header pr-14">` > `.fc-drawer-titles` > title `h2.fc-drawer-title` / subtitle `p.fc-drawer-subtitle` | title 18px/600 ellipsis; subtitle 13px muted | Extras (status tags) beside the titles |
| Body | `<div class="fc-drawer-body">` | scrolls (`flex: 1; overflow-y: auto; padding: 0 20px 20px`) | Slot: `form-section`s |
| Footer | `<div class="fc-drawer-footer">` | `flex end; gap 8px; top border` | Slot: actions |

## Options

Each row maps one option value to the classes (or attributes) it adds. Exactly one value per option applies; the first listed is the default unless stated.

### size (class on the frame)

| Value | Classes added |
|---|---|
| default | none (560px) |
| wide | `fc-drawer-wide` (800px) |
| two-thirds | `fc-drawer-two-thirds` (66.667vw) |

## Accessibility

- `role="complementary"` with `aria-label`; close button `aria-label="Close"`.
- Non-modal, so there is **no focus trap** by design (the list must stay usable). Escape closes it unless a dialog or popover is open (that closes first).
- When a record opens, move focus to the drawer heading (`tabindex="-1"` + `.focus()` after the server patch) for keyboard users; not done in the gallery.

## Behaviour

**State (b):** one signal holds the selected record id (empty = closed). Rows set it: `data-on:click="$selUser = 'u-42'"`. The frame: `data-show="$selUser !== ''"` (+ `style="display: none"` to avoid a flash). Escape: `data-on:keydown__window="evt.key === 'Escape' && $selUser !== '' && !document.querySelector('dialog[open]:not([data-drawer]), [popover]:popover-open') && ($selUser = '')"`. This replaces fc-web's `ui.js` Escape handler and Topcoat `selected` signal.

**Content (c):** the body is server-rendered per record. Row click: `data-on:click="$selUser = 'u-42'; @get('/users/u-42/drawer')"`; reply `datastar-patch-elements` with `selector #user-drawer-body`, `mode inner`. (Or key the content off the signal: `data-effect="$selUser && @get('/users/' + $selUser + '/drawer')"`.) Edit mode toggles a second signal (`$editing`) with `data-show`; the edit form is a normal form (a) or `@post(..., {contentType: 'form'})` (c) whose reply patches the body and a flash.

## Notes for a template author

- Parameters: signal name, aria label, size, title, subtitle, extras (tags).
- Slots: body sections, footer actions.
- Signal name must be unique per drawer.
- In the gallery the content is bound to two signals (`data-text`) so it works without a server.
