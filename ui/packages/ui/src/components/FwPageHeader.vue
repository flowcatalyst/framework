<script setup lang="ts">
// ui-kit/components/page-header.md: the page's one <h1>, a subtitle and right-aligned actions.
// U0 builds it so the gallery has a title block; U1 completes its gallery entry and checks.
defineProps<{
  title: string;
  subtitle?: string;
}>();

defineSlots<{
  actions?: () => unknown;
}>();
</script>

<template>
  <header class="mb-4 flex items-start justify-between gap-4">
    <div>
      <h1 class="text-2xl font-semibold text-highlighted">{{ title }}</h1>
      <p v-if="subtitle !== undefined" class="text-sm text-muted">{{ subtitle }}</p>
    </div>
    <div class="flex items-center gap-2">
      <slot name="actions" />
    </div>
  </header>
</template>
