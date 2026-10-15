<script setup lang="ts">
// Where a failed sign-in lands (docs/ui/u3.md G11): the demo's `LoginFailureHandler.redirectTo(
// "/sign-in-failed")` sends the failure's code in `?error=` (sessions contract 4.2: the callback; 4.1.1: a rate-limited or
// unreachable `/auth/login` in a browser navigation). Says what
// happened in a sentence per code (signInFailureMessage), shows the code for an administrator, and
// offers Try again, which starts sign-in again and returns to `returnTo`. A public route, outside the shell.
import UButton from "@nuxt/ui/components/Button.vue";
import { computed } from "vue";
import { useRoute } from "vue-router";
import { useSession } from "../composables/useSession.ts";
import FwStandalonePage from "../layouts/FwStandalonePage.vue";
import { signInFailureMessage } from "../lib/signInFailure.ts";

const {
  appName,
  returnTo = "/",
} = defineProps<{
  appName?: string;
  /** Where Try again lands once the sign-in succeeds (a same-origin path). */
  returnTo?: string;
}>();

const route = useRoute();
const session = useSession();
const code = computed(() => {
  const value: unknown = route.query["error"];
  return typeof value === "string" ? value : "";
});

function retry(): void {
  session?.signIn(returnTo);
}
</script>

<template>
  <FwStandalonePage title="Sign-in failed" :description="signInFailureMessage(code)" v-bind="appName === undefined ? {} : { appName }">
    <p v-if="code !== ''" class="text-xs text-muted">Code: <code data-testid="fw-sign-in-failed-code">{{ code }}</code></p>
    <template #actions>
      <UButton label="Try again" icon="i-lucide-log-in" data-testid="fw-sign-in-retry" @click="retry" />
    </template>
  </FwStandalonePage>
</template>
