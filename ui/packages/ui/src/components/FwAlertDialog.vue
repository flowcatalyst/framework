<script setup lang="ts">
// ui-kit alert-dialog.md and confirm-dialog.md: a modal that interrupts to ask for a decision, built on
// Reka UI's AlertDialog (Nuxt UI has none with role="alertdialog"): role alertdialog, labelled by its
// title and described by its message, the safe action (Cancel) focused first, Escape cancels, a click
// on the mask does NOT dismiss it, focus returns to the element that opened it. Confirm emits
// `confirm` and leaves closing to the owner; Cancel, Escape and closing otherwise emit `cancel`.
import UButton from "@nuxt/ui/components/Button.vue";
import UIcon from "@nuxt/ui/components/Icon.vue";
import {
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogOverlay,
  AlertDialogPortal,
  AlertDialogRoot,
  AlertDialogTitle,
  AlertDialogTrigger,
} from "reka-ui";
import { computed } from "vue";
import type { AlertTone } from "../types.ts";

const {
  title,
  description,
  confirmLabel,
  cancelLabel = "Cancel",
  tone = "primary",
  busy = false,
} = defineProps<{
  title: string;
  /** The question or consequence; becomes the dialog's description. */
  description: string;
  confirmLabel: string;
  cancelLabel?: string;
  tone?: AlertTone;
  /** The confirmed action is running: the confirm button shows it and both buttons wait. */
  busy?: boolean;
}>();

const open = defineModel<boolean>("open", { default: false });

const emit = defineEmits<{
  confirm: [];
  cancel: [];
  /** The close animation finished (Nuxt UI's overlay manager unmounts on it). */
  "after:leave": [];
}>();

const slots = defineSlots<{
  /** An element that opens the dialog (rendered as is). */
  trigger?: () => unknown;
  /** Extra content under the message (a list of what will be affected). */
  default?: () => unknown;
}>();

const color = computed(() => (tone === "danger" ? "error" : tone === "warn" ? "warning" : "primary"));
const iconClass = computed(() => (tone === "danger" ? "text-error" : "text-warning"));

// Reka's Presence dispatches an `after-leave` DOM event on the content when its close animation ends.
const leaveListener = {
  onAfterLeave: () => {
    emit("after:leave");
  },
};

function onOpenChange(next: boolean): void {
  if (!next && open.value) {
    emit("cancel");
  }
  open.value = next;
}
</script>

<template>
  <AlertDialogRoot :open="open" @update:open="onOpenChange">
    <AlertDialogTrigger v-if="slots.trigger !== undefined" as-child>
      <slot name="trigger" />
    </AlertDialogTrigger>
    <AlertDialogPortal>
      <AlertDialogOverlay class="fixed inset-0 bg-elevated/75 data-[state=closed]:animate-[fade-out_200ms_var(--ease-out)] data-[state=open]:animate-[fade-in_200ms_var(--ease-out)]" />
      <AlertDialogContent
        class="fixed top-1/2 left-1/2 flex w-[calc(100vw-2rem)] max-w-md -translate-x-1/2 -translate-y-1/2 flex-col divide-y divide-default rounded-lg bg-default shadow-lg ring ring-default focus:outline-none data-[state=closed]:animate-[scale-out_200ms_var(--ease-out)] data-[state=open]:animate-[scale-in_200ms_var(--ease-out)]"
        data-testid="fw-alert-dialog"
        v-bind="leaveListener"
      >
        <div class="flex items-start gap-3 p-4 sm:px-6">
          <UIcon name="i-lucide-triangle-alert" :class="['mt-0.5 size-6 shrink-0', iconClass]" aria-hidden="true" />
          <div class="min-w-0 space-y-1">
            <AlertDialogTitle class="text-lg font-semibold text-highlighted">{{ title }}</AlertDialogTitle>
            <AlertDialogDescription class="text-toned">{{ description }}</AlertDialogDescription>
            <slot />
          </div>
        </div>
        <div class="flex items-center justify-end gap-2 p-4 sm:px-6">
          <AlertDialogCancel as-child>
            <UButton :label="cancelLabel" color="neutral" variant="ghost" :disabled="busy" data-testid="fw-alert-cancel" />
          </AlertDialogCancel>
          <!-- Not Reka's AlertDialogAction, which closes the dialog by itself: the owner closes it
               (at once, or after the confirmed action finished while `busy`). -->
          <UButton :label="confirmLabel" :color="color" :loading="busy" data-testid="fw-alert-confirm" @click="emit('confirm')" />
        </div>
      </AlertDialogContent>
    </AlertDialogPortal>
  </AlertDialogRoot>
</template>
