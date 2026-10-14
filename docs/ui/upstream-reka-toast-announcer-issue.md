# Draft: upstream issue for Reka UI (not posted)

Draft only (do not post anywhere without the owner's say-so). For
[github.com/unovue/reka-ui](https://github.com/unovue/reka-ui/issues). Facts checked on 2026-10-15
against the installed `reka-ui` 2.10.5 (`dist/Toast/ToastAnnounce.js`, `dist/Toast/ToastRootImpl.js`,
`dist/VisuallyHidden/VisuallyHidden.js`) and in Chrome 153 (Playwright 1.63). Not checked: a real
screen reader (the finding is about the accessibility tree), whether an open issue already covers it
(search `Toast announce aria-hidden` before posting), and Radix's current source (the claim in the last
paragraph is from memory and is worded to be checked).

How it was found: the FlowCatalyst UI gallery's Playwright suite looks for the toast text in a
`status`/`alert` region by role. Nuxt UI's `UToast` (a Reka `ToastRoot`) never matched, and the toast
text was only found with `includeHidden: true`. The kit therefore announces through its own live
regions (`FwAnnouncer`, docs/ui/u1.md and nuxt-native.md); this issue is the upstream half.

---

**Title:** `ToastRoot`'s announcer is `aria-hidden="true"`, so toasts are not announced

**Body:**

Versions: `reka-ui` 2.10.5, `vue` 3.5.43 (also seen through `@nuxt/ui` 4.11.3's `UToast`), Chrome 153.

`ToastRoot` renders a visually hidden live region (`role="alert"`, `aria-live="assertive"` for
`type="foreground"`, `"polite"` for `"background"`) that is meant to announce the toast. The element
carries `aria-hidden="true"`, so browsers remove it from the accessibility tree and a screen reader
gets no live-region update. The toast in the viewport (`<ol><li>`) has no live role, so the toast is
not announced at all.

**Reproduction** (plain Reka, no Nuxt UI):

```vue
<script setup lang="ts">
import { ToastProvider, ToastRoot, ToastTitle, ToastViewport } from "reka-ui";
</script>

<template>
  <ToastProvider>
    <ToastRoot :open="true"><ToastTitle>Saved</ToastTitle></ToastRoot>
    <ToastViewport />
  </ToastProvider>
</template>
```

Mount it, wait about a second (`ToastAnnounce` renders its text after two animation frames or 1 s),
and inspect the DOM. The announcer is:

```html
<span aria-hidden="true" data-hidden="" tabindex="-1" role="alert" aria-live="assertive"
      style="position: absolute; ... clip: rect(0, 0, 0, 0); ...">Notification Saved</span>
```

Observed: in the accessibility tree there is no alert with the toast's text; Playwright's
`page.getByRole("alert")` does not return it, and `page.getByRole("alert", { includeHidden: true })`
does. Expected: the live region is exposed (no `aria-hidden`), so "Notification Saved" is announced.

**Cause:** `ToastAnnounce.vue` wraps its text in `<VisuallyHidden feature="fully-hidden">`, and
`VisuallyHidden` sets `aria-hidden="true"` (and `tabindex="-1"`, `data-hidden`) for the `focusable` and
`fully-hidden` features (`"aria-hidden": feature === "focusable" || feature === "fully-hidden" ? "true" : void 0`).
`role` and `aria-live` from `ToastRootImpl` fall through onto that same element, so a hidden node
carries a live role. `aria-hidden` on a live region defeats it.

**Suggested fix:** do not mark the announcer `aria-hidden` (and it needs no `tabindex="-1"`): render it
with a visually-hidden style only, for example a `VisuallyHidden` feature that leaves `aria-hidden`
unset, or a plain visually hidden `span`. `FocusProxy`'s `aria-hidden` spans are a different case and
fine as they are. As far as I remember, Radix's React Toast announcer uses a `VisuallyHidden` without
`aria-hidden`; worth a look when fixing, since Reka's `Toast` is a port of it.

Workaround in the meantime: announce from application code through a permanent `role="status"` /
`role="alert"` region that is not `aria-hidden`.

Happy to open a pull request. Thank you for Reka.
