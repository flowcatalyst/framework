<script setup lang="ts">
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import ULink from "@nuxt/ui/components/Link.vue";
</script>

<template>
  <div>
    <UPageHeader title="Not found" description="There is no page at this address." />
    <ULink to="/orders" class="text-primary underline">Back to the orders</ULink>
  </div>
</template>
