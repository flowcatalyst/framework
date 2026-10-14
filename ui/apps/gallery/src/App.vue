<script setup lang="ts">
// The gallery shell: Nuxt UI's dashboard layout composed by hand. FwAppShell (U2) replaces it.
import { FwColorModeButton } from "@flowcatalyst/ui";
import UApp from "@nuxt/ui/components/App.vue";
import UDashboardGroup from "@nuxt/ui/components/DashboardGroup.vue";
import UDashboardNavbar from "@nuxt/ui/components/DashboardNavbar.vue";
import UDashboardPanel from "@nuxt/ui/components/DashboardPanel.vue";
import UDashboardSidebar from "@nuxt/ui/components/DashboardSidebar.vue";
import UNavigationMenu from "@nuxt/ui/components/NavigationMenu.vue";
import type { NavigationMenuItem } from "@nuxt/ui/components/NavigationMenu.vue";
import { computed } from "vue";
import { RouterView, useRoute } from "vue-router";
import { pages } from "./router.ts";

const route = useRoute();
const items: NavigationMenuItem[] = pages.map((page) => ({ label: page.title, to: page.path }));
const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Gallery";
});
</script>

<template>
  <UApp>
    <UDashboardGroup>
      <UDashboardSidebar collapsible :resizable="false">
        <template #header>
          <span class="font-semibold text-highlighted">FlowCatalyst UI</span>
        </template>
        <UNavigationMenu :items="items" orientation="vertical" aria-label="Gallery pages" />
        <template #footer>
          <FwColorModeButton />
        </template>
      </UDashboardSidebar>
      <UDashboardPanel>
        <template #header>
          <!-- No `title` prop: UDashboardNavbar renders it as an <h1>, and the page's one <h1> is
               FwPageHeader's (ui-kit page-header spec). FwAppShell (U2) keeps this rule. -->
          <UDashboardNavbar>
            <template #leading>
              <span class="font-medium text-muted" data-testid="navbar-title">{{ title }}</span>
            </template>
          </UDashboardNavbar>
        </template>
        <template #body>
          <main class="mx-auto w-full max-w-5xl">
            <RouterView />
          </main>
        </template>
      </UDashboardPanel>
    </UDashboardGroup>
  </UApp>
</template>
