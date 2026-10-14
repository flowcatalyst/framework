<script setup lang="ts">
// A confirmation that interrupts (ui-kit alert-dialog.md, confirm-dialog.md) as Nuxt UI's UModal with
// the alert-dialog behaviour Nuxt UI has no component for (Reka's AlertDialog is the same DialogContent
// with these settings): role "alertdialog", labelled by the title and described by the message, Cancel
// focused first, a click on the mask does not dismiss it, Escape cancels, focus returns to the trigger.
// Confirm emits `confirm` and `close(true)` and leaves closing to the owner, so a `busy` action can keep
// it open; Cancel and Escape emit `cancel` and `close(false)`. `close` is what Nuxt UI's overlay
// manager (useOverlay, behind useConfirm) resolves with.
import UButton from "@nuxt/ui/components/Button.vue";
import UModal from "@nuxt/ui/components/Modal.vue";
import type { AlertColor } from "../types.ts";

const {
  title,
  description,
  confirmLabel,
  cancelLabel = "Cancel",
  color = "primary",
  busy = false,
} = defineProps<{
  title: string;
  /** The question or consequence; the dialog's description. */
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  /** The confirm button's colour: `error` for a destructive action. */
  color?: AlertColor;
  /** The confirmed action is running: the confirm button shows it, nothing else can be used. */
  busy?: boolean;
}>();

const open = defineModel<boolean>("open", { default: false });

const emit = defineEmits<{
  confirm: [];
  cancel: [];
  /** true when confirmed (the overlay manager's result). */
  close: [confirmed: boolean];
  /** The close animation finished (the overlay manager unmounts on it). */
  "after:leave": [];
}>();

const slots = defineSlots<{
  /** An element that opens the dialog. */
  trigger?: () => unknown;
  /** Extra content under the message (a list of what will be affected). */
  default?: () => unknown;
}>();

// Passed through UModal to Reka's DialogContent; `role` replaces the dialog role there, as Reka's own
// AlertDialogContent does.
const content = {
  role: "alertdialog",
  "data-testid": "fw-alert-dialog",
  onPointerDownOutside: (event: Event) => {
    event.preventDefault();
  },
  onInteractOutside: (event: Event) => {
    event.preventDefault();
  },
  onOpenAutoFocus: (event: Event) => {
    event.preventDefault();
    const root = event.target;
    if (root instanceof HTMLElement) {
      root.querySelector<HTMLElement>("[data-fw-alert-cancel]")?.focus();
    }
  },
};

function onOpenChange(next: boolean): void {
  if (!next && open.value) {
    emit("cancel");
    emit("close", false);
  }
  open.value = next;
}

function confirm(): void {
  emit("confirm");
  emit("close", true);
}
</script>

<template>
  <UModal
    :open="open"
    :title="title"
    :description="description"
    :dismissible="!busy"
    :close="false"
    :content="content"
    @update:open="onOpenChange"
    @after:leave="emit('after:leave')"
  >
    <template v-if="slots.trigger !== undefined" #default>
      <slot name="trigger" />
    </template>
    <template v-if="slots.default !== undefined" #body>
      <slot />
    </template>
    <template #footer>
      <div class="flex w-full items-center justify-end gap-2">
        <UButton :label="cancelLabel" color="neutral" variant="ghost" :disabled="busy" data-fw-alert-cancel data-testid="fw-alert-cancel" @click="onOpenChange(false)" />
        <UButton :label="confirmLabel" :color="color" :loading="busy" data-testid="fw-alert-confirm" @click="confirm" />
      </div>
    </template>
  </UModal>
</template>
