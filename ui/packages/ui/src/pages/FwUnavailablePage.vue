<script setup lang="ts">
// The session could not be read at all (offline, the application is down): nothing else can work
// (docs/ui/u3.md G13). `installSessionGuard` cancels the navigation and calls `onUnavailable`; send the
// user here (`router.replace("/unavailable")`). Try again reloads `retryTo` in full, which runs the
// guard again. A public route, outside the shell.
import UButton from "@nuxt/ui/components/Button.vue";
import FwStandalonePage from "../layouts/FwStandalonePage.vue";

const {
  appName,
  retryTo = "/",
} = defineProps<{
  appName?: string;
  /** Where Try again goes (a full page load). */
  retryTo?: string;
}>();

function retry(): void {
  window.location.assign(retryTo);
}
</script>

<template>
  <FwStandalonePage
    :title="appName === undefined ? 'Unavailable' : `${appName} is unavailable`"
    description="The application could not be reached. Try again in a moment."
    v-bind="appName === undefined ? {} : { appName }"
  >
    <template #actions>
      <UButton label="Try again" icon="i-lucide-refresh-cw" data-testid="fw-unavailable-retry" @click="retry" />
    </template>
  </FwStandalonePage>
</template>
