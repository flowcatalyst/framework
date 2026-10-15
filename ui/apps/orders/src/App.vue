<script setup lang="ts">
// The orders application inside FwAppShell: the sidebar lists the pages the session may open, the
// public pages (signed out, sign-in failed, unavailable: the kit's FwStandalonePage) stand alone (no session, no shell).
import { FwAppShell, type FwNavItem } from "@flowcatalyst/ui";
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
    <RouterView v-if="standalone" />
    <FwAppShell v-else app-name="Orders" :navigation="navigation" :page-title="title">
      <RouterView />
    </FwAppShell>
  </UApp>
</template>
