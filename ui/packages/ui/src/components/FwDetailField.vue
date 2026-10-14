<script setup lang="ts">
// ui-kit detail-field.md: one labelled read-only value inside FwDetailList (`<dt>` + `<dd>`). An empty
// value reads "—"; markup values (a tag, code, a link) go in the slot.
import { computed } from "vue";

const { label, value, span = false } = defineProps<{
  label: string;
  /** Plain text; when absent or empty, the slot is used, and without a slot "—". */
  value?: string | number | undefined;
  /** Spans both columns. */
  span?: boolean;
}>();

const slots = defineSlots<{
  default?: () => unknown;
}>();

const text = computed(() => (value === undefined || value === "" ? undefined : String(value)));
</script>

<template>
  <div :class="['min-w-0', span ? '@[640px]:col-span-2' : '']" data-testid="fw-detail-field">
    <dt class="mb-1 text-[13px] font-medium text-toned">{{ label }}</dt>
    <dd class="text-[14px] text-highlighted [overflow-wrap:anywhere]">
      <template v-if="text !== undefined">{{ text }}</template>
      <slot v-else-if="slots.default !== undefined" />
      <span v-else class="text-muted" aria-label="empty">—</span>
    </dd>
  </div>
</template>
