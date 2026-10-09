# Flash stack (notification banners)

**Source:** port of the SPA's `NotificationBannerStack.vue` + `utils/errorBus.ts` (geometry, colours, lifetimes); the fallback for failed requests is new in the kit (2026-10-09)  
**Mechanism:** (c) server patches append messages; (b) auto-dismiss, dismiss button and the fallback listener

## Purpose

One region per page for feedback after an action: "Pool saved", "Requeue started", "Could not save". Server handlers append a **flash message** to it with a Datastar patch. The stack also shows a generic message when a Datastar request fails in a way the handler could not answer (a guard refused it, the server crashed, the network dropped), so a failed click is never silent.

`flash-banner.md` remains for an inline banner rendered with a full page (post/redirect/get); for action feedback use this stack.

## Structure

The stack (once per page, at the top of the content, present from the first render so its live region exists before anything is announced):

```html
<div id="flash-stack" class="fc-flash-stack" aria-live="polite"
     data-on:datastar-fetch="const t = evt.detail.type, s = evt.detail.argsRaw.status; const k = t === 'retrying' || t === 'retries-failed' ? 'offline' : t === 'error' && s !== '401' ? (s === '403' ? 'refused' : 'failed') : ''; k && !el.querySelector(':scope > [data-flash-fallback=' + k + ']') && el.append(el.querySelector(':scope > template[data-flash-template=' + k + ']').content.cloneNode(true))">
  <template data-flash-template="refused">{flash message: error, "The request was refused.", "Reload the page and try again.", attrs data-flash-fallback="refused"}</template>
  <template data-flash-template="failed">{flash message: error, "Something went wrong.", "The request failed. Try again, or reload the page.", attrs data-flash-fallback="failed"}</template>
  <template data-flash-template="offline">{flash message: warn, "The server could not be reached.", "Check your connection and try again. A change you just made may not have been saved.", attrs data-flash-fallback="offline"}</template>
</div>
```

A flash message:

```html
<div class="fc-flash fc-flash-success" data-init__delay.3s="el.remove()">
  <svg width="1em" height="1em" class="fc-flash-icon" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-circle-check"/></svg>
  <div class="fc-flash-body"><span class="fc-flash-summary">Pool saved.</span><span class="fc-flash-detail">Optional detail.</span></div>
  <button type="button" class="fc-flash-dismiss" aria-label="Dismiss notification" data-on:click="el.parentElement.remove()"><svg width="1em" height="1em" class="size-[0.9rem]" aria-hidden="true"><use href="/icons/lucide-sprite.svg#i-x"/></svg></button>
</div>
<div class="fc-flash fc-flash-error" role="alert">…<span class="fc-flash-summary">Could not save the pool.</span><span class="fc-flash-detail">A pool with code 'orders' already exists.</span>…</div>
```

## Parts

| Part | Element | Classes / attributes | Role |
|---|---|---|---|
| Stack | `<div id class="fc-flash-stack" aria-live="polite" data-on:datastar-fetch>` | sticky, top 0, z-index 45, column, 1px gap | the patch target (`#flash-stack`, mode `append`); the fallback listener |
| Fallback templates | `<template data-flash-template="refused\|failed\|offline">` | inert until cloned | one flash message each, marked `data-flash-fallback` |
| Message | `<div class="fc-flash fc-flash-{kind}">` | left border + tint per kind (light: the SPA's colours; dark: tinted) | `role="alert"` for warn/error |
| Icon | `<svg class="fc-flash-icon">` | 16px | success `circle-check`, info `info`, warn `triangle-alert`, error `circle-x` |
| Body | `.fc-flash-body > .fc-flash-summary (+ .fc-flash-detail)` | wraps; summary 600 |  |
| Dismiss | `<button class="fc-flash-dismiss" aria-label="Dismiss notification">` | icon `x` | removes the message |

## Options

### kind

| Value | Class | Lifetime | Role |
|---|---|---|---|
| success | fc-flash-success | removed after 3s (`data-init__delay.3s="el.remove()"`) | none (the stack's polite live region announces it) |
| info | fc-flash-info | removed after 5s (`data-init__delay.5s`) | none |
| warn | fc-flash-warn | stays until dismissed | `alert` |
| error | fc-flash-error | stays until dismissed | `alert` |

The lifetimes are the SPA's (`toast.success` 3000 ms, `toast.info` 5000 ms, warnings and errors persist). `persistent` keeps a success or info message.

## Props

Language-neutral parameters (see `_conventions.md`, Props). Fixtures, from the ui-kit root: `fixtures/flash-stack` and `fixtures/flash-message`.

**flash-stack**

| Prop | Type | Default | Notes |
|---|---|---|---|
| `id` | string | - | the stack's id (the platform uses `flash-stack`); the fallback texts are fixed (above) |

**flash-message**

| Prop | Type | Default | Notes |
|---|---|---|---|
| `kind` | enum(success\|info\|warn\|error) | success |  |
| `summary` | string | - | escaped; one short sentence |
| `detail` | string | - | escaped; optional |
| `persistent` | boolean | false | success/info: no auto-dismiss |
| `attrs` | attrs | - | on the message root |

## Accessibility

- The stack is a polite live region present from page load, so appended messages are announced; warnings and errors are `role="alert"` (assertive).
- Messages that need reading (warn, error) never disappear on a timer (WCAG 2.2.1); success and info do, as in the SPA, and are announced when they appear.
- Every message has a dismiss button with an accessible name.
- A failed action inside a dialog shows its error **inside the dialog** (the form's error region, `_conventions.md`), not in the stack: a modal dialog covers the page.

## Behaviour

**Server patch (c).** After an action, append a message:

```
event: datastar-patch-elements
data: selector #flash-stack
data: mode append
data: elements <div class="fc-flash fc-flash-success" data-init__delay.3s="el.remove()">…</div>
```

**Fallback (b).** Datastar 1.0.4 dispatches `datastar-fetch` on `document` for every request; the stack listens (Datastar binds that event to `document` whatever element carries it). It reacts to:

| Event | When | Message |
|---|---|---|
| `error`, status 401 | not signed in (session expired) | none: the application handles it (the platform navigates to its login page) |
| `error`, status 403 | a guard refused the request (CSRF, no access) | refused |
| `error`, any other status ≥ 400 | 404, 5xx, a proxy error, a crashed handler | failed |
| `retrying`, `retries-failed` | the network failed (Datastar retries a failed connection up to `retryMaxCount` times, default 10) | offline |

At most one message per fallback kind is shown at a time. Datastar does **not** process the body of a non-200 response (verified in the 1.0.4 bundle), so these messages are the only feedback a non-200 answer can give; handlers therefore answer expected failures with **200 and a patch** (`_conventions.md`, "Answering a (c) request").

## Notes for a template author

- Render the stack once, inside the page's main content, before the page header.
- Message text is data: escape it. Never put it in a `data-*` expression.
- Server code typically offers `flash(kind, summary, detail)` returning the message markup and a helper that appends it to the stack.
