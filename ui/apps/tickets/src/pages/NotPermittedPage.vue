<script setup lang="ts">
import UButton from "@nuxt/ui/components/Button.vue";
import UPageHeader from "@nuxt/ui/components/PageHeader.vue";
import { useSession } from "@flowcatalyst/ui";

const session = useSession();
</script>

<template>
  <div>
    <UPageHeader title="Not permitted" description="Your account has no access to the tickets. Ask an administrator for the requester, agent or lead role." />
    <UButton label="Sign out" color="neutral" variant="outline" icon="i-lucide-log-out" @click="session?.logout()" />
  </div>
</template>
