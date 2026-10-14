<script setup lang="ts">
// A domain status (an enum value from the API: OPEN, PLACED, CANCELLED) as Nuxt UI's UBadge. The
// application maps its statuses to colours once (`colors`); an unmapped status is neutral. The label
// is the status in sentence case unless given; the text carries the meaning, the colour repeats it.
// Solid by default: every alias passes 4.5:1 as a fill under white text (docs/ui/u1.md, "Accessibility").
import UBadge from "@nuxt/ui/components/Badge.vue";
import { computed } from "vue";
import type { StatusColor } from "../types.ts";

const {
  status,
  colors,
  label,
  variant = "solid",
} = defineProps<{
  status: string;
  /** The application's status-to-colour map. */
  colors: Readonly<Record<string, StatusColor>>;
  label?: string;
  variant?: "solid" | "outline" | "soft" | "subtle";
}>();

const text = computed(() => label ?? humanise(status));
const color = computed<StatusColor>(() => colors[status] ?? "neutral");

function humanise(value: string): string {
  const words = value.replace(/[_-]+/g, " ").trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
</script>

<template>
  <UBadge :label="text" :color="color" :variant="variant" :data-status="status" :data-color="color" data-testid="fw-status-badge" />
</template>
