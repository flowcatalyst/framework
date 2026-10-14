<script setup lang="ts">
// Where logout ends (the platform's post-logout redirect, or FwAppShell's sign-out when the platform
// gave no end-session URL). No session is needed: signing in again goes through /auth/login.
import { useSession } from "@flowcatalyst/ui";
import UButton from "@nuxt/ui/components/Button.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";

const session = useSession();
function signIn(): void {
  session?.signIn("/orders");
}
</script>

<template>
  <div>
    <UPageHeader title="Signed out" description="You have been signed out of Orders." />
    <UButton label="Sign in again" icon="i-lucide-log-in" data-testid="sign-in" @click="signIn" />
  </div>
</template>
