// A confirmation as a promise (ui-kit confirm-dialog.md; the platform SPA's useConfirm +
// confirmDiscardChanges shape): FwAlertDialog opened through Nuxt UI's overlay manager, so it needs
// UApp (which renders the overlays) somewhere above the caller.
//
//   const confirm = useConfirm();
//   if (await confirm({ title: "Cancel order 1042?", description: "...", confirmLabel: "Cancel order", color: "error" })) { ... }
import { useOverlay } from "@nuxt/ui/composables";
import type { Component } from "vue";
import FwAlertDialog from "../components/FwAlertDialog.vue";
import type { AlertColor } from "../types.ts";

// Widened to Component: the overlay manager's typing of a .vue import is not readable by the lint.
const alertDialog: Component = FwAlertDialog;

export interface ConfirmOptions {
  readonly title: string;
  readonly description: string;
  readonly confirmLabel: string;
  readonly cancelLabel?: string;
  /** The confirm button's colour (default primary; `error` for a destructive action). */
  readonly color?: AlertColor;
}

export type Confirm = (options: ConfirmOptions) => Promise<boolean>;

/** Returns a function that opens a confirmation and resolves true when confirmed. */
export function useConfirm(): Confirm {
  const overlay = useOverlay();
  return async (options) => {
    // One overlay per question, destroyed when its close animation ends.
    const dialog = overlay.create(alertDialog, { destroyOnClose: true });
    const result: unknown = await dialog.open({
      title: options.title,
      description: options.description,
      confirmLabel: options.confirmLabel,
      ...(options.cancelLabel === undefined ? {} : { cancelLabel: options.cancelLabel }),
      ...(options.color === undefined ? {} : { color: options.color }),
    });
    return result === true;
  };
}

/** The platform's discard question: "You have unsaved changes. Discard them?". */
export function confirmDiscardChanges(confirm: Confirm): Promise<boolean> {
  return confirm({
    title: "Discard changes?",
    description: "You have unsaved changes. Discard them?",
    confirmLabel: "Discard",
    cancelLabel: "Keep editing",
    color: "error",
  });
}
