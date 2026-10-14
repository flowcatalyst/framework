<script setup lang="ts">
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import ULink from "@nuxt/ui/components/Link.vue";
</script>

<template>
  <div>
    <UPageHeader title="Not found" description="No gallery page has this address" />
    <ULink to="/" class="text-primary underline">Back to the overview</ULink>
  </div>
</template>
