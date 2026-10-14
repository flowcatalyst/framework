<script setup lang="ts">
// Where the gallery's runtime "navigates" after logout or a 401 (U4 builds the real signed-out page).
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { computed } from "vue";
import { useRoute } from "vue-router";

const route = useRoute();
const from = computed(() => {
  const value: unknown = route.query["from"];
  return typeof value === "string" ? value : "";
});
</script>

<template>
  <div>
    <UPageHeader title="Signed out" description="The gallery's stand-in for the signed-out page" />
    <p class="text-sm" data-testid="signed-out-from">{{ from === "" ? "You signed out." : `Sign-in requested: ${from}` }}</p>
  </div>
</template>
