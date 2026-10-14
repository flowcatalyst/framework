<script setup lang="ts">
// ui-kit code-chips.md: an event-type code (`app:subdomain:aggregate:event`) as coloured monospace
// segments. The text equals the code (colour is decoration).
import { computed } from "vue";

const { code } = defineProps<{
  code: string;
}>();

const KINDS = [
  "bg-[#dbeafe] text-[#1d4ed8] dark:bg-[rgb(29_78_216/0.3)] dark:text-[#93c5fd]",
  "bg-[#dcfce7] text-[#15803d] dark:bg-[rgb(21_128_61/0.3)] dark:text-[#86efac]",
  "bg-[#fef3c7] text-[#b45309] dark:bg-[rgb(180_83_9/0.3)] dark:text-[#fcd34d]",
  "bg-[#f3e8ff] text-[#7c3aed] dark:bg-[rgb(124_58_237/0.3)] dark:text-[#d8b4fe]",
] as const;

const segments = computed(() =>
  code.split(":").map((text, index) => ({ text, index, kind: KINDS[Math.min(index, KINDS.length - 1)] ?? KINDS[3] })),
);
</script>

<template>
  <span class="whitespace-nowrap font-mono text-[13px]" data-testid="fw-code-chips">
    <template v-for="segment in segments" :key="segment.index">
      <span v-if="segment.index > 0" class="mx-px text-[#64748b] dark:text-[#94a3b8]">:</span>
      <span :class="['rounded-[3px] px-1 py-0.5', segment.kind]">{{ segment.text }}</span>
    </template>
  </span>
</template>
