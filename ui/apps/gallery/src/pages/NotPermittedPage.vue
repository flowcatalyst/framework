<script setup lang="ts">
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { RouterLink } from "vue-router";
</script>

<template>
  <div>
    <UPageHeader title="Not permitted" description="Your session does not hold the permission this page needs." />
    <RouterLink class="text-primary underline text-sm" to="/admin-shell">Back to the admin shell page</RouterLink>
  </div>
</template>
