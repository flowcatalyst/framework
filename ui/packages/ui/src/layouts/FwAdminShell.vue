<script setup lang="ts">
// The admin shell (docs/contracts/admin.md section 6): FwAppShell with the admin navigation built from
// the server's sections (`load`, the application's generated getAdminNavigation) and the session's
// permissions, a "Back to the application" link, breadcrumbs from the matched routes, and an inline
// error with Retry when the navigation cannot be loaded. The route guard is the router's
// (adminRoutes sets `meta.permission`; installSessionGuard enforces it); the standalone pages of U4
// stay outside any shell. The server checks every operation whatever this shows.
import UAlert from "@nuxt/ui/components/Alert.vue";
import UBreadcrumb from "@nuxt/ui/components/Breadcrumb.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import { computed, watchEffect } from "vue";
import { useRoute } from "vue-router";
import { adminBreadcrumbs, adminNavigation, ADMIN_BASE, sectionsWithoutPage } from "../admin/navigation.ts";
import type { AdminNavigationView, AdminPage } from "../admin/types.ts";
import { useAdminNavigation } from "../composables/useAdminNavigation.ts";
import { usePermissions } from "../composables/useSession.ts";
import type { FwNavItem } from "../types.ts";
import FwAppShell from "./FwAppShell.vue";

const {
  appName,
  pages,
  load,
  backTo = "/",
  backLabel = "Back to the application",
  base = ADMIN_BASE,
} = defineProps<{
  appName: string;
  /** The application's admin pages (the same list `adminRoutes` builds the routes from). */
  pages: readonly AdminPage[];
  /** Loads `GET /api/admin/navigation` with the application's client; a rejection is the failed state. */
  load: () => Promise<AdminNavigationView>;
  /** Where "Back" goes. */
  backTo?: string;
  backLabel?: string;
  base?: string;
}>();

defineSlots<{
  default?: () => unknown;
}>();

const route = useRoute();
const permissions = usePermissions();
const nav = useAdminNavigation(load);

const navigation = computed<FwNavItem[]>(() => [
  { label: "Administration", type: "label" },
  ...adminNavigation(pages, nav.sections.value, (permission) => permissions.can(permission)),
  { label: backLabel, to: backTo, icon: "i-lucide-arrow-left" },
]);

const title = computed(() => {
  const meta: unknown = route.meta["title"];
  return typeof meta === "string" ? meta : "Administration";
});
// The last crumb is the current page: text, with aria-current="page" (UBreadcrumb sets it on `active`).
const crumbItems = computed(() =>
  adminBreadcrumbs(route.matched, base).map((crumb) => (crumb.to === undefined ? { label: crumb.label, active: true } : { label: crumb.label, to: crumb.to })),
);

// A section the server lists that no page serves: said once, in development, where a developer looks.
const reported = new Set<string>();
watchEffect(() => {
  if (nav.sections.value === undefined) {
    return;
  }
  for (const id of sectionsWithoutPage(pages, nav.sections.value)) {
    if (!reported.has(id)) {
      reported.add(id);
      console.warn(`admin: the server lists section '${id}' but the application has no admin page with that id`);
    }
  }
});
</script>

<template>
  <FwAppShell :app-name="appName" :navigation="navigation" :page-title="title">
    <UBreadcrumb :items="crumbItems" class="mb-4" data-testid="fw-admin-breadcrumbs" />
    <UAlert
      v-if="nav.failed.value"
      color="error"
      variant="subtle"
      icon="i-lucide-circle-alert"
      title="The administration menu could not be loaded."
      description="Pages you may open are not listed. Try again."
      class="mb-4"
      data-testid="fw-admin-nav-failed"
    >
      <template #actions>
        <UButton label="Try again" color="error" variant="outline" data-testid="fw-admin-nav-retry" @click="nav.reload()" />
      </template>
    </UAlert>
    <slot />
  </FwAppShell>
</template>
