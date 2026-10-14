<script setup lang="ts">
// Where a failed sign-in lands (the demo's `LoginFailureHandler.redirectTo("/sign-in-failed")`): the
// callback's code is in `?error=` (sessions contract section 4.2). The codes mean different things to a
// person: some are worth trying again, none shows a token or the platform's own text.
import { useSession } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { computed } from "vue";
import { useRoute } from "vue-router";

const MESSAGES: Readonly<Record<string, string>> = {
  LOGIN_REFUSED: "The sign-in was refused. You may not have access to this application.",
  LOGIN_NOT_STARTED_HERE: "This sign-in was not started in this browser. Start it again from here.",
  INVALID_STATE: "The sign-in took too long or was already used. Start it again.",
  INVALID_CALLBACK: "The sign-in answer was incomplete. Start it again.",
  LOGIN_FAILED: "The platform did not accept the sign-in. Try again in a moment.",
  INVALID_ID_TOKEN: "The platform's answer could not be verified. Try again, and tell an administrator if it repeats.",
  PLATFORM_UNAVAILABLE: "The sign-in service is not reachable right now. Try again in a moment.",
};

const route = useRoute();
const session = useSession();
const code = computed(() => {
  const value: unknown = route.query["error"];
  return typeof value === "string" ? value : "";
});
const message = computed(() => MESSAGES[code.value] ?? "The sign-in did not complete. Try again.");

function retry(): void {
  session?.signIn("/orders");
}
</script>

<template>
  <div>
    <UPageHeader title="Sign-in failed" :description="message" />
    <p v-if="code !== ''" class="mb-4 text-xs text-muted">Code: <code data-testid="failure-code">{{ code }}</code></p>
    <UButton label="Try again" icon="i-lucide-log-in" data-testid="retry-sign-in" @click="retry" />
  </div>
</template>
