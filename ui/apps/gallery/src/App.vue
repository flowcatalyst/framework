<script setup lang="ts">
// The gallery runs inside FwAppShell (the package's own shell, U2).
import { FwAppShell, type FwNavItem } from "@flowcatalyst/ui";
import UApp from "@nuxt/ui/components/App.vue";
import { computed } from "vue";
import { RouterView, useRoute } from "vue-router";
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
const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Gallery";
});
</script>

<template>
  <UApp>
    <FwAppShell app-name="FlowCatalyst UI" :navigation="navigation" :page-title="title" :user="{ name: 'Ada Lovelace', email: 'ada@example.com' }">
      <RouterView />
    </FwAppShell>
  </UApp>
</template>
