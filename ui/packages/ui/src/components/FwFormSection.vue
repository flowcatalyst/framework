<script setup lang="ts">
// ui-kit form-section.md: a titled block of a drawer or page, optional actions beside the title,
// `danger` colours the title (the danger zone), `card` draws a bordered panel. The heading level
// follows the page: h3 in a drawer under its h2 (the default), h2 directly on a page.
import { computed } from "vue";

const {
  title,
  description,
  danger = false,
  card = false,
  level = 3,
} = defineProps<{
  title: string;
  description?: string;
  danger?: boolean;
  card?: boolean;
  level?: 2 | 3 | 4;
}>();

defineSlots<{
  default?: () => unknown;
  actions?: () => unknown;
}>();

const heading = computed(() => `h${String(level)}`);
</script>

<template>
  <section
    :class="['border-b border-default pb-4 mb-4 last:mb-0 last:border-b-0', card ? 'rounded-[2px] border bg-default p-4' : '']"
    data-testid="fw-form-section"
  >
    <header class="mb-3 flex items-start justify-between gap-3">
      <div class="min-w-0">
        <component :is="heading" :class="['m-0 text-[16px] font-semibold', danger ? 'text-error' : 'text-highlighted']">{{ title }}</component>
        <p v-if="description !== undefined" class="mt-1 text-[13px] text-muted">{{ description }}</p>
      </div>
      <div class="flex shrink-0 items-center gap-2">
        <slot name="actions" />
      </div>
    </header>
    <div class="@container">
      <slot />
    </div>
  </section>
</template>
