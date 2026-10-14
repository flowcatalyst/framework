<script setup lang="ts">
// ui-kit detail-field.md: the read view of a record as a description list (`<dl>`), two columns that
// become one when the container is narrower than 640px (the kit's .fc-detail-grid). Holds
// FwDetailField children.
defineSlots<{
  default?: () => unknown;
}>();
</script>

<template>
  <div class="@container">
    <dl class="grid grid-cols-1 gap-x-5 gap-y-3 @[640px]:grid-cols-2">
      <slot />
    </dl>
  </div>
</template>
