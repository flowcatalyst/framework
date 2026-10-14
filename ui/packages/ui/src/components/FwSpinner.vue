<script setup lang="ts">
// ui-kit spinner.md: Lucide loader-circle, spinning. Alone it is the only sign of progress, so it is
// an image named "Loading"; inside a labelled button it is decorative.
import UIcon from "@nuxt/ui/components/Icon.vue";

const {
  size = "size-6",
  label = "Loading",
  decorative = false,
} = defineProps<{
  /** A size class (`size-4`, `size-6`, ...). */
  size?: string;
  /** The accessible name when not decorative. */
  label?: string;
  /** Hidden from assistive technology (inside a button that has a text label). */
  decorative?: boolean;
}>();
</script>

<template>
  <UIcon v-if="decorative" name="i-lucide-loader-circle" :class="['shrink-0 animate-spin', size]" aria-hidden="true" />
  <span v-else role="img" :aria-label="label" class="inline-flex" data-testid="fw-spinner">
    <UIcon name="i-lucide-loader-circle" :class="['shrink-0 animate-spin', size]" aria-hidden="true" />
  </span>
</template>
