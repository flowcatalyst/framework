<script setup lang="ts">
// ui-kit app-shell.md: Nuxt UI's dashboard layout with the kit's navy sidebar (collapsible, a drawer on
// phones), navigation filtered by the session's permissions (show/hide only), the signed-in user's
// menu with Sign out, the colour-mode button, and the page in a `<main>`. The navbar shows the page's
// title as text only: the page's one `<h1>` is FwPageHeader's (UDashboardNavbar's `title` would add a
// second). Request failures (403, 5xx, offline) of the provided runtime become toasts here.
import type { DropdownMenuItem } from "@nuxt/ui/components/DropdownMenu.vue";
import type { NavigationMenuItem } from "@nuxt/ui/components/NavigationMenu.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UDashboardGroup from "@nuxt/ui/components/DashboardGroup.vue";
import UDashboardNavbar from "@nuxt/ui/components/DashboardNavbar.vue";
import UDashboardPanel from "@nuxt/ui/components/DashboardPanel.vue";
import UDashboardSidebar from "@nuxt/ui/components/DashboardSidebar.vue";
import UDropdownMenu from "@nuxt/ui/components/DropdownMenu.vue";
import UNavigationMenu from "@nuxt/ui/components/NavigationMenu.vue";
import { computed } from "vue";
import FwAnnouncer from "../components/FwAnnouncer.vue";
import FwColorModeButton from "../components/FwColorModeButton.vue";
import { useRequestFailureToasts } from "../composables/useRequestFailureToasts.ts";
import { useApiRuntime, usePermissions } from "../composables/useSession.ts";
import type { FwNavItem } from "../types.ts";

const {
  appName,
  navigation,
  pageTitle = "",
  user,
} = defineProps<{
  appName: string;
  navigation: readonly FwNavItem[];
  /** The current page's title for the navbar (text, not a heading). */
  pageTitle?: string;
  /** The signed-in user to show; default the session's. */
  user?: { readonly name: string; readonly email?: string };
}>();

const emit = defineEmits<{
  /** Sign out was chosen; with a runtime the shell signs out itself and this is informational. */
  "sign-out": [];
}>();

defineSlots<{
  default?: () => unknown;
  /** Controls on the navbar's right (search, help). */
  navbar?: () => unknown;
}>();

const runtime = useApiRuntime();
const permissions = usePermissions();
useRequestFailureToasts(runtime);

const visible = computed<NavigationMenuItem[]>(() =>
  navigation
    .filter((item) => item.permission === undefined || permissions.can(item.permission))
    .map((item) => ({ label: item.label, to: item.to, ...(item.icon === undefined ? {} : { icon: item.icon }) })),
);

const who = computed(() => {
  if (user !== undefined) {
    return user;
  }
  const principal = permissions.user.value?.principal;
  return principal === undefined ? undefined : { name: principal.name ?? principal.email ?? principal.id, email: principal.email };
});

const userItems = computed<DropdownMenuItem[][]>(() => [
  ...(who.value?.email === undefined ? [] : [[{ label: who.value.email, type: "label" as const }]]),
  [{ label: "Sign out", icon: "i-lucide-log-out", onSelect: signOut }],
]);

function signOut(): void {
  emit("sign-out");
  if (runtime !== undefined) {
    void runtime.session.logout();
  }
}

// The kit's navy sidebar (ui-kit tokens --sidebar*): navy-900 (dark: navy-950), navy-100 text, white
// for the current page on a 15% white tint.
const sidebarUi = {
  root: "bg-[#102a43] text-[#d9e2ec] border-[rgb(255_255_255/0.1)] dark:bg-[#0a1929]",
  header: "border-b border-[rgb(255_255_255/0.1)]",
  footer: "border-t border-[rgb(255_255_255/0.1)]",
};
const navUi = {
  link: "text-[#d9e2ec] hover:text-white hover:before:bg-white/10 data-active:text-white data-active:before:bg-white/15 focus-visible:before:ring-[#47a3f3]",
  linkLeadingIcon: "text-[#bcccdc] group-hover:text-white group-data-active:text-white",
};
</script>

<template>
  <UDashboardGroup>
    <UDashboardSidebar collapsible :resizable="false" :ui="sidebarUi">
      <template #header="{ collapsed }">
        <span class="truncate font-semibold text-white" data-testid="fw-app-name">{{ collapsed === true ? appName.charAt(0) : appName }}</span>
      </template>
      <template #default="{ collapsed }">
        <UNavigationMenu :items="visible" :collapsed="collapsed === true" orientation="vertical" color="neutral" :ui="navUi" aria-label="Main" />
      </template>
      <template #footer="{ collapsed }">
        <div class="flex w-full items-center gap-1">
          <UDropdownMenu v-if="who !== undefined" :items="userItems" :content="{ side: 'top', align: 'start' }">
            <UButton
              v-bind="collapsed === true ? {} : { label: who.name }"
              icon="i-lucide-circle-user"
              color="neutral"
              variant="ghost"
              class="min-w-0 flex-1 text-[#d9e2ec] hover:bg-white/10 hover:text-white"
              :aria-label="`Account: ${who.name}`"
              data-testid="fw-user-menu"
            />
          </UDropdownMenu>
          <FwColorModeButton class="text-[#d9e2ec] hover:bg-white/10 hover:text-white" />
        </div>
      </template>
    </UDashboardSidebar>
    <UDashboardPanel>
      <template #header>
        <UDashboardNavbar>
          <template #leading>
            <span class="font-medium text-muted" data-testid="fw-navbar-title">{{ pageTitle }}</span>
          </template>
          <template #right><slot name="navbar" /></template>
        </UDashboardNavbar>
      </template>
      <template #body>
        <main class="mx-auto w-full max-w-6xl">
          <slot />
        </main>
        <FwAnnouncer />
      </template>
    </UDashboardPanel>
  </UDashboardGroup>
</template>
