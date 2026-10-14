<script setup lang="ts">
// KNOWN to break the strict CSP: UBanner with an `id` (persistent dismissal) adds an inline script
// and an inline style through useHead. Without `id` it adds nothing.
import UBanner from "@nuxt/ui/components/Banner.vue";
</script>

<template>
  <UBanner id="u0-probe" title="A dismissible banner" close />
</template>
