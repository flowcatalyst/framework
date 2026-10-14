<script setup lang="ts">
// The dialog useConfirm() opens through Nuxt UI's overlay manager (ui-kit confirm-dialog.md): an
// FwAlertDialog that closes with `true` (confirmed) or `false` (Cancel, Escape). The manager binds
// `v-model:open`, listens for `close` and unmounts on `after:leave`.
import type { AlertTone } from "../types.ts";
import FwAlertDialog from "./FwAlertDialog.vue";

const {
  title,
  message,
  confirmLabel,
  cancelLabel = "Cancel",
  tone = "primary",
} = defineProps<{
  title: string;
  message: string;
  confirmLabel: string;
  cancelLabel?: string;
  tone?: AlertTone;
}>();

const open = defineModel<boolean>("open", { default: true });

const emit = defineEmits<{
  close: [confirmed: boolean];
  "after:leave": [];
}>();
</script>

<template>
  <FwAlertDialog
    v-model:open="open"
    :title="title"
    :description="message"
    :confirm-label="confirmLabel"
    :cancel-label="cancelLabel"
    :tone="tone"
    @confirm="emit('close', true)"
    @cancel="emit('close', false)"
    @after:leave="emit('after:leave')"
  />
</template>
