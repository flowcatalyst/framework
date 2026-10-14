<script setup lang="ts">
// The session could not be read at all (offline, the application is down): nothing else can work.
import UButton from "@nuxt/ui/components/Button.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";

function retry(): void {
  window.location.assign("/orders");
}
</script>

<template>
  <div>
    <UPageHeader title="Orders is unavailable" description="The application could not be reached. Try again in a moment." />
    <UButton label="Try again" icon="i-lucide-refresh-cw" data-testid="retry" @click="retry" />
  </div>
</template>
