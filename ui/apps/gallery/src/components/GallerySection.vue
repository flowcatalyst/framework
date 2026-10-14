<script setup lang="ts">
// One gallery entry: the ui-kit spec(s) it shows (data-spec, space-separated, checked by Playwright
// against ui-kit/components), a heading and the states.
import { useId } from "vue";

const { spec, title, description } = defineProps<{
  /** ui-kit spec names, space-separated (`button` or `select filter-select`). */
  spec: string;
  title: string;
  description?: string;
}>();

defineSlots<{
  default?: () => unknown;
}>();

const id = useId();
</script>

<template>
  <section :data-spec="spec" :aria-labelledby="id" class="space-y-3 border-b border-default pb-6 last:border-b-0">
    <div>
      <h2 :id="id" class="text-base font-semibold text-highlighted">{{ title }}</h2>
      <p v-if="description !== undefined" class="text-sm text-muted">{{ description }}</p>
    </div>
    <slot />
  </section>
</template>
