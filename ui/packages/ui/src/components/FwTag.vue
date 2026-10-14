<script setup lang="ts">
// ui-kit tag.md: a short label with a severity (PrimeVue Tag), as a Nuxt UI badge. The text carries
// the meaning; the colour repeats it.
import UBadge from "@nuxt/ui/components/Badge.vue";
import { computed } from "vue";
import type { TagSeverity } from "../types.ts";

const { label, severity = "secondary" } = defineProps<{
  label: string;
  severity?: TagSeverity;
}>();

const COLOR = {
  success: "success",
  info: "info",
  warn: "warning",
  danger: "error",
  secondary: "neutral",
  primary: "primary",
} as const satisfies Record<TagSeverity, string>;

const color = computed(() => COLOR[severity]);
// The kit's tags are filled; its secondary tag is a grey tint.
const variant = computed(() => (severity === "secondary" ? "soft" : "solid"));
</script>

<template>
  <UBadge :label="label" :color="color" :variant="variant" size="sm" class="font-bold" :data-severity="severity" data-testid="fw-tag" />
</template>
