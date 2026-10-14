<script setup lang="ts">
// ui-kit json-block.md: a JSON value pretty-printed in a light, scrollable `<pre>`, reachable by
// keyboard (tabindex) and named for screen readers. Text only: nothing is parsed as HTML.
import { computed } from "vue";

const { value, label } = defineProps<{
  /** Any JSON value (an API payload, an event body). */
  value: unknown;
  /** The scroll region's accessible name. */
  label: string;
}>();

// JSON.stringify(undefined) is undefined at run time, whatever its declared type says.
const text = computed(() => (value === undefined ? "undefined" : JSON.stringify(value, null, 2)));
</script>

<template>
  <pre
    tabindex="0"
    :aria-label="label"
    class="m-0 max-h-[28rem] overflow-auto whitespace-pre rounded-[6px] border border-default bg-muted p-3 font-mono text-[12px] leading-[1.55] text-default focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-primary"
    data-testid="fw-json-block"
  >{{ text }}</pre>
</template>
