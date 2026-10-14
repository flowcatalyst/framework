<script setup lang="ts">
// KNOWN to break the strict CSP while dragging: Reka UI's splitter puts the drag cursor in an
// inline <style> element (`*{cursor: ...}`) for the length of the drag.
import USplitter from "@nuxt/ui/components/Splitter.vue";

const items = [{ defaultSize: 50 }, { defaultSize: 50 }];
</script>

<template>
  <USplitter :items="items" class="h-40 border border-default" data-testid="splitter">
    <template #panel-0><p class="p-2">Left</p></template>
    <template #panel-1><p class="p-2">Right</p></template>
  </USplitter>
</template>
