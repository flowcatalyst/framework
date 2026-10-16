<script setup lang="ts">
// The gallery runs inside FwAppShell (the package's own shell, U2); public routes (the kit's standalone
// pages, U4) are rendered without it.
import { FwAdminShell, FwAppShell, type FwNavItem } from "@flowcatalyst/ui";
import UApp from "@nuxt/ui/components/App.vue";
import { computed } from "vue";
import { RouterView, useRoute } from "vue-router";
import { adminPages } from "./admin/pages.ts";
import { fakeAdminNavigation } from "./fake-server.ts";
import { pages } from "./pages.ts";

const route = useRoute();
// Two section headings show the spacing of a grouped menu (the shell's sidebar).
const navigation: FwNavItem[] = [
  { label: "Components", type: "label" },
  ...pages.slice(0, 8).map((page) => ({ label: page.title, to: page.path, icon: page.icon })),
  { label: "Application", type: "label" },
  ...pages.slice(8).map((page) => ({ label: page.title, to: page.path, icon: page.icon })),
  // Never shown: the gallery's session holds no permission (FwAppShell hides what the user may not open).
  { label: "Administration", to: "/admin", icon: "i-lucide-shield", permission: "gallery:admin:manage" },
];
const standalone = computed(() => route.meta.public === true);
const admin = computed(() => route.meta.admin === true);

/** The admin shell's navigation, from the in-page fake server (a rejection is the failed state). */
const loadAdminNavigation = fakeAdminNavigation;
const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Gallery";
});
</script>

<template>
  <UApp>
    <RouterView v-if="standalone" />
    <FwAdminShell v-else-if="admin" app-name="Gallery admin" :pages="adminPages" :load="loadAdminNavigation" back-to="/admin-shell" back-label="Back to the gallery">
      <RouterView />
    </FwAdminShell>
    <FwAppShell v-else app-name="FlowCatalyst UI" :navigation="navigation" :page-title="title" :user="{ name: 'Ada Lovelace', email: 'ada@example.com' }">
      <RouterView />
    </FwAppShell>
  </UApp>
</template>
