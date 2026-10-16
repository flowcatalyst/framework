<script setup lang="ts">
// What an M4e page is: the page title and, below it, the kit's list page + record drawer + form dialog.
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Admin page";
});
</script>

<template>
  <div>
    <UPageHeader :title="title" description="An admin page: M4e puts a list page, a record drawer and a form dialog here, over typed operations." />
  </div>
</template>
