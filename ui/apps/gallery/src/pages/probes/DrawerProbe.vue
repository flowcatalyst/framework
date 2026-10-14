<script setup lang="ts">
// KNOWN to break the strict CSP: UDrawer is built on vaul-vue, which inserts an inline <style>
// element when its module loads. The kit's drawer maps to USlideover instead (docs/go/ui.md
// section 10). The e2e suite asserts this page still violates, so a fixed vaul-vue is noticed.
import UButton from "@nuxt/ui/components/Button.vue";
import UDrawer from "@nuxt/ui/components/Drawer.vue";
</script>

<template>
  <UDrawer title="Drawer" description="vaul-vue drawer">
    <UButton label="Open drawer" data-testid="open-drawer" />
    <template #body><p>Body</p></template>
  </UDrawer>
</template>
