<script setup lang="ts">
// ui-kit empty-state.md: in place of a list's rows when there are none: an icon, a message, and
// "Clear filters" while a filter is active. `announce` makes it a status region, so a filter change
// that empties the list is read out.
import UEmpty from "@nuxt/ui/components/Empty.vue";
import { computed } from "vue";

const {
  message,
  icon = "i-lucide-inbox",
  filtered = false,
  announce = false,
} = defineProps<{
  message: string;
  icon?: string;
  /** A filter is active: offer "Clear filters". */
  filtered?: boolean;
  /** role="status" (the region appeared after a filter change). */
  announce?: boolean;
}>();

const emit = defineEmits<{
  clear: [];
}>();

const actions = computed(() =>
  filtered
    ? [{ label: "Clear filters", color: "primary" as const, variant: "link" as const, onClick: () => { emit("clear"); } }]
    : [],
);
</script>

<template>
  <div :role="announce ? 'status' : undefined" data-testid="fw-empty-state">
    <UEmpty :icon="icon" :description="message" :actions="actions" variant="naked" />
  </div>
</template>
