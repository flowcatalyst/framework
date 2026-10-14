// Live-region announcements for the whole application. Found at U1: Reka UI 2.10.5 (under Nuxt UI
// 4.11.3's toasts) renders its toast announcer with aria-hidden="true", so a toast is never read out
// (the live region is outside the accessibility tree). useFlash therefore also announces through
// these two regions, which FwAnnouncer renders once (FwAppShell includes it) and which exist from
// page load, as a live region must (ui-kit flash-stack.md: polite for success and info, assertive
// for warnings and errors).
import { nextTick, shallowRef, type ShallowRef } from "vue";

const polite = shallowRef("");
const assertive = shallowRef("");

/** The regions' current texts (FwAnnouncer renders them). */
export const announcements: { readonly polite: Readonly<ShallowRef<string>>; readonly assertive: Readonly<ShallowRef<string>> } = { polite, assertive };

/** Reads `text` out: politely, or at once (`urgent`). A repeat of the same text is read again. */
export function announce(text: string, urgent = false): void {
  const region = urgent ? assertive : polite;
  region.value = "";
  void nextTick(() => {
    region.value = text;
  });
}
