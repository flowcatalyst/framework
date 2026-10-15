<script setup lang="ts">
// Where logout ends (the platform's post-logout redirect, or FwAppShell's sign-out when the platform
// gave no end-session URL; docs/ui/u3.md G10). No session is needed; Sign in again goes through
// /auth/login and returns to `returnTo`. A public route, outside the shell.
import UButton from "@nuxt/ui/components/Button.vue";
import { useSession } from "../composables/useSession.ts";
import FwStandalonePage from "../layouts/FwStandalonePage.vue";

const {
  appName,
  returnTo = "/",
} = defineProps<{
  appName?: string;
  /** Where Sign in again lands (a same-origin path). */
  returnTo?: string;
}>();

const session = useSession();
function signIn(): void {
  session?.signIn(returnTo);
}
</script>

<template>
  <FwStandalonePage
    title="Signed out"
    :description="appName === undefined ? 'You have been signed out.' : `You have been signed out of ${appName}.`"
    v-bind="appName === undefined ? {} : { appName }"
  >
    <template #actions>
      <UButton label="Sign in again" icon="i-lucide-log-in" data-testid="fw-sign-in-again" @click="signIn" />
    </template>
  </FwStandalonePage>
</template>
