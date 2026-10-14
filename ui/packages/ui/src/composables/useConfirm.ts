// A confirmation as a promise (ui-kit confirm-dialog.md; the platform SPA's useConfirm +
// confirmDiscardChanges shape), on Nuxt UI's overlay manager, so it needs UApp (which renders the
// overlays) somewhere above the caller.
//
//   const confirm = useConfirm();
//   if (await confirm({ title: "Cancel order 1042?", message: "...", confirmLabel: "Cancel order", tone: "danger" })) { ... }
import { useOverlay } from "@nuxt/ui/composables";
import type { Component } from "vue";
import FwConfirmDialog from "../components/FwConfirmDialog.vue";
import type { AlertTone } from "../types.ts";

// Widened to Component: the overlay manager's typing of a .vue import is not readable by the lint.
const confirmDialog: Component = FwConfirmDialog;

export interface ConfirmOptions {
  readonly title: string;
  readonly message: string;
  readonly confirmLabel: string;
  readonly cancelLabel?: string;
  readonly tone?: AlertTone;
}

export type Confirm = (options: ConfirmOptions) => Promise<boolean>;

/** Returns a function that opens a confirmation and resolves true when confirmed. */
export function useConfirm(): Confirm {
  const overlay = useOverlay();
  return async (options) => {
    // One overlay per question, destroyed when its close animation ends.
    const dialog = overlay.create(confirmDialog, { destroyOnClose: true });
    const result: unknown = await dialog.open({
      title: options.title,
      message: options.message,
      confirmLabel: options.confirmLabel,
      ...(options.cancelLabel === undefined ? {} : { cancelLabel: options.cancelLabel }),
      ...(options.tone === undefined ? {} : { tone: options.tone }),
    });
    return result === true;
  };
}

/** The platform's discard question: "You have unsaved changes. Discard them?". */
export function confirmDiscardChanges(confirm: Confirm): Promise<boolean> {
  return confirm({
    title: "Discard changes?",
    message: "You have unsaved changes. Discard them?",
    confirmLabel: "Discard",
    cancelLabel: "Keep editing",
    tone: "danger",
  });
}
