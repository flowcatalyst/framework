<script setup lang="ts">
import { FwPageHeader } from "@flowcatalyst/ui";
import ULink from "@nuxt/ui/components/Link.vue";
</script>

<template>
  <div>
    <FwPageHeader title="Not found" subtitle="No gallery page has this address" />
    <ULink to="/" class="text-primary underline">Back to the overview</ULink>
  </div>
</template>
