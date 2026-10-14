<script setup lang="ts">
// The application shell (ui-kit app-shell.md) on Nuxt UI's dashboard layout (UDashboardGroup, -Sidebar,
// -Panel, -Navbar, UNavigationMenu): the kit's navy sidebar (collapsible, a slideover on phones),
// navigation filtered by the session's permissions (show/hide only), the signed-in user's menu with
// Sign out, the colour-mode button, and the page in a `<main>`. The navbar shows the page's title as
// text only: the page's one `<h1>` is its UPageHeader (UDashboardNavbar's `title` would add a second).
// Request failures (403, 5xx, offline) of the provided runtime become toasts, announced (FwAnnouncer).
import type { DropdownMenuItem } from "@nuxt/ui/components/DropdownMenu.vue";
import type { NavigationMenuItem } from "@nuxt/ui/components/NavigationMenu.vue";
import UButton from "@nuxt/ui/components/Button.vue";
import UDashboardGroup from "@nuxt/ui/components/DashboardGroup.vue";
import UDashboardNavbar from "@nuxt/ui/components/DashboardNavbar.vue";
import UDashboardPanel from "@nuxt/ui/components/DashboardPanel.vue";
import UDashboardSidebar from "@nuxt/ui/components/DashboardSidebar.vue";
import UDropdownMenu from "@nuxt/ui/components/DropdownMenu.vue";
import UNavigationMenu from "@nuxt/ui/components/NavigationMenu.vue";
import UTooltip from "@nuxt/ui/components/Tooltip.vue";
import { computed } from "vue";
import FwAnnouncer from "../components/FwAnnouncer.vue";
import FwColorModeButton from "../components/FwColorModeButton.vue";
import { useLocalState } from "../composables/useLocalState.ts";
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

// The desktop sidebar's collapsed state, per viewer (localStorage through useLocalState, which ignores
// blocked or garbled storage). It is read synchronously here, before the first render, so a collapsed
// sidebar is drawn collapsed at once (no jump from wide to narrow). Nuxt UI's own cookie persistence
// is off (`persistent` false on the group): one owner for the state, no cookie, nothing for the CSP.
// On a phone the sidebar is a slideover and always expanded; this state does not touch it.
const collapsed = useLocalState<boolean>("shell-sidebar-collapsed", false, (value): value is boolean => typeof value === "boolean");
// Nuxt UI prefixes this with "dashboard-sidebar-"; the toggle's aria-controls needs the final id.
const SIDEBAR_ID = "fw-sidebar";
const sidebarElementId = `dashboard-sidebar-${SIDEBAR_ID}`;

const runtime = useApiRuntime();
const permissions = usePermissions();
useRequestFailureToasts(runtime);

// Nuxt UI's navigation items as given, minus the entries (and children) the session may not open.
// `permission` stays on the item: UNavigationMenu passes only link, aria-* and data-* keys on.
function permitted(items: readonly FwNavItem[]): NavigationMenuItem[] {
  return items
    .filter((item) => item.permission === undefined || permissions.can(item.permission))
    .map((item) => (item.children === undefined ? item : { ...item, children: permitted(item.children) }));
}

const visible = computed(() => permitted(navigation));
// A collapsed rail draws icons only, so a link would have no accessible name: give each its label as
// aria-label (UNavigationMenu forwards aria-* keys; the tooltip shows the same text to the eye).
const named = computed(() =>
  visible.value.map((item) => (item.type === "label" || typeof item.label !== "string" ? item : { ...item, "aria-label": item.label })),
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

// The kit's navy sidebar (the theme's navy palette): navy-900 (dark: navy-950), navy-100 text, white
// for the current page on a 15 % white tint, the accent ring for focus.
const sidebarUi = {
  // box-content: the rail's min-w-16 (64 px) is then inside the 1 px border, so the 40 px targets fit (they were 39).
  root: "box-content bg-navy-900 text-navy-100 border-white/10 dark:bg-navy-950",
  // The phone's slideover is not the root: without this it is the page's white, under navy-100 text.
  content: "bg-navy-900 text-navy-100 dark:bg-navy-950",
  header: "border-b border-white/10 gap-2",
  footer: "border-t border-white/10",
};
const navUi = {
  link: "text-navy-100 hover:text-white hover:before:bg-white/10 data-active:text-white data-active:before:bg-white/15 focus-visible:before:ring-accent-300",
  linkLeadingIcon: "text-navy-200 group-hover:text-white group-data-active:text-white",
  label: "text-navy-300",
};
</script>

<template>
  <UDashboardGroup :persistent="false">
    <UDashboardSidebar :id="SIDEBAR_ID" v-model:collapsed="collapsed" collapsible :resizable="false" :ui="sidebarUi">
      <template #header="{ collapsed: isCollapsed, collapse }">
        <!-- The slideover (phones) renders this slot too, always expanded; its own close button is there, so ours is hidden below lg. -->
        <!-- Collapsed, the 64 px rail leaves 16 px inside the header's padding: -mx-3 gives the 40 px button its room. -->
        <div class="flex w-full min-w-0 items-center gap-2" :class="isCollapsed ? '-mx-3 justify-center' : 'justify-between'">
          <span v-if="!isCollapsed" class="truncate font-semibold text-white" data-testid="fw-app-name">{{ appName }}</span>
          <span v-else class="sr-only" data-testid="fw-app-name">{{ appName }}</span>
          <UTooltip :text="isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'" :content="{ side: 'right' }">
            <UButton
              :icon="isCollapsed ? 'i-lucide-panel-left-open' : 'i-lucide-panel-left-close'"
              color="neutral"
              variant="ghost"
              square
              class="size-10 shrink-0 max-lg:hidden text-navy-100 hover:bg-white/10 hover:text-white focus-visible:outline-accent-300"
              :aria-label="isCollapsed ? 'Expand sidebar' : 'Collapse sidebar'"
              :aria-expanded="!isCollapsed"
              :aria-controls="sidebarElementId"
              data-testid="fw-sidebar-toggle"
              @click="collapse(!isCollapsed)"
            />
          </UTooltip>
        </div>
      </template>
      <template #default="{ collapsed: isCollapsed }">
        <UNavigationMenu :items="isCollapsed === true ? named : visible" :collapsed="isCollapsed === true" :tooltip="isCollapsed === true" orientation="vertical" color="neutral" :ui="navUi" aria-label="Main" />
      </template>
      <template #footer="{ collapsed: isCollapsed }">
        <div class="flex w-full gap-1" :class="isCollapsed === true ? 'flex-col items-center' : 'items-center'">
          <UDropdownMenu v-if="who !== undefined" :items="userItems" :modal="false" :content="{ side: 'top', align: 'start' }">
            <UButton
              v-bind="isCollapsed === true ? { square: true } : { label: who.name }"
              icon="i-lucide-circle-user"
              color="neutral"
              variant="ghost"
              class="min-w-0 text-navy-100 hover:bg-white/10 hover:text-white"
              :class="isCollapsed === true ? 'size-10' : 'flex-1'"
              :aria-label="`Account: ${who.name}`"
              data-testid="fw-user-menu"
            />
          </UDropdownMenu>
          <FwColorModeButton class="text-navy-100 hover:bg-white/10 hover:text-white" :class="isCollapsed === true ? 'size-10' : ''" />
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
