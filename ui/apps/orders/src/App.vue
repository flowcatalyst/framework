<script setup lang="ts">
// The orders application inside FwAppShell: the sidebar lists the pages the session may open, the
// signed-out and unavailable pages stand alone (no session, no shell).
import { FwAppShell, FwAnnouncer, type FwNavItem } from "@flowcatalyst/ui";
import UApp from "@nuxt/ui/components/App.vue";
import { computed } from "vue";
import { RouterView, useRoute } from "vue-router";
import { pages } from "./pages.ts";

const route = useRoute();
const navigation: FwNavItem[] = pages.map((page) => ({ label: page.title, to: page.path, icon: page.icon, permission: page.permission }));
const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Orders";
});
const standalone = computed(() => route.meta.public === true);
</script>

<template>
  <UApp>
    <main v-if="standalone" class="mx-auto w-full max-w-xl p-6">
      <RouterView />
      <FwAnnouncer />
    </main>
    <FwAppShell v-else app-name="Orders" :navigation="navigation" :page-title="title">
      <RouterView />
    </FwAppShell>
  </UApp>
</template>
