<script setup lang="ts">
// A status value (an enum from the API: OPEN, PLACED, CANCELLED) as an FwTag: the application maps
// its statuses to severities once; an unmapped status is secondary. The label is the status in
// sentence case unless given.
import { computed } from "vue";
import type { TagSeverity } from "../types.ts";
import FwTag from "./FwTag.vue";

const { status, severities, label } = defineProps<{
  status: string;
  severities: Readonly<Record<string, TagSeverity>>;
  label?: string;
}>();

const text = computed(() => label ?? humanise(status));
const severity = computed(() => severities[status] ?? "secondary");

function humanise(value: string): string {
  const words = value.replace(/[_-]+/g, " ").trim().toLowerCase();
  return words.charAt(0).toUpperCase() + words.slice(1);
}
</script>

<template>
  <FwTag :label="text" :severity="severity" />
</template>
