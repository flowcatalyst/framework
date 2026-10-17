<script setup lang="ts">
// The tickets application inside FwAppShell: the sidebar lists the pages the session may open, the
// public pages (signed out, sign-in failed, unavailable: the kit's FwStandalonePage) stand alone (no session, no shell).
import { FwAdminShell, FwAppShell, type AdminNavigationView, type FwNavItem } from "@flowcatalyst/ui";
import UApp from "@nuxt/ui/components/App.vue";
import { computed } from "vue";
import { RouterView, useRoute } from "vue-router";
import { ICON_ADMIN } from "./admin/icons.ts";
import { ADMIN_CONSOLE, adminPages } from "./admin/pages.ts";
import { getAdminNavigation } from "./api/tickets/sdk.gen.ts";
import { pages } from "./pages.ts";

const route = useRoute();
const navigation: FwNavItem[] = [
  ...pages.map((page) => ({ label: page.title, to: page.path, icon: page.icon, permission: page.permission })),
  // Shown to administrators only (the server decides what the area holds).
  { label: "Administration", to: "/admin", icon: ICON_ADMIN, permission: ADMIN_CONSOLE },
];

/** The admin shell's navigation: the generated client's answer, a rejection is the failed state. */
async function loadAdminNavigation(): Promise<AdminNavigationView> {
  const { data } = await getAdminNavigation({ throwOnError: true });
  return data;
}
const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Tickets";
});
const standalone = computed(() => route.meta.public === true);
const admin = computed(() => route.meta.admin === true);
</script>

<template>
  <UApp>
    <RouterView v-if="standalone" />
    <FwAdminShell v-else-if="admin" app-name="Tickets admin" :pages="adminPages" :load="loadAdminNavigation" back-to="/tickets" back-label="Back to Tickets">
      <RouterView />
    </FwAdminShell>
    <FwAppShell v-else app-name="Tickets" :navigation="navigation" :page-title="title">
      <RouterView />
    </FwAppShell>
  </UApp>
</template>
