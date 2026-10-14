<script setup lang="ts">
// One gallery entry: a heading, the states, and the platform behaviours it demonstrates
// (`data-behaviour`, ids from src/behaviours.ts, checked by e2e/behaviours.spec.ts).
import { useId } from "vue";
import type { BehaviourId } from "../behaviours.ts";

const { behaviours = [], title, description } = defineProps<{
  /** The checklist rows this entry demonstrates. */
  behaviours?: readonly BehaviourId[];
  title: string;
  description?: string;
}>();

defineSlots<{
  default?: () => unknown;
}>();

const id = useId();
</script>

<template>
  <section :data-behaviour="behaviours.join(' ')" :aria-labelledby="id" class="space-y-3 border-b border-default pb-6 last:border-b-0">
    <div>
      <h2 :id="id" class="text-base font-semibold text-highlighted">{{ title }}</h2>
      <p v-if="description !== undefined" class="text-sm text-muted">{{ description }}</p>
    </div>
    <slot />
  </section>
</template>
