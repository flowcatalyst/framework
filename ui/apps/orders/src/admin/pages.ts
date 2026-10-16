// The orders application's admin pages (M4d/M4e, docs/contracts/admin.md section 6): data only, so the
// Vite config (icons Nuxt UI cannot find in .ts data), the router, the shell and the Playwright suite
// read the same list. Each page matches a server section by id: `about` is the framework's own,
// `event-types`, `roles` and `config` are the framework's M4e sections (PlatformAdminModule,
// AdminConfigModule), `orders` is the demo's (OrdersAdminModule). The permissions are the server's.
import type { AdminPage } from "@flowcatalyst/ui";

import {
  ADMIN_CONFIG_VIEW,
  ADMIN_CONSOLE,
  ADMIN_EVENT_TYPES_VIEW,
  ADMIN_ORDERS_VIEW,
  ADMIN_ROLES_VIEW,
  ICON_ABOUT,
  ICON_CONFIG,
  ICON_EVENT_TYPES,
  ICON_ORDERS,
  ICON_ROLES,
} from "./icons.ts";

export { ADMIN_CONFIG_VIEW, ADMIN_CONSOLE, ADMIN_EVENT_TYPES_VIEW, ADMIN_ORDERS_VIEW, ADMIN_ROLES_VIEW };

export const adminPages: readonly AdminPage[] = [
  {
    id: "about",
    title: "About",
    icon: ICON_ABOUT,
    permission: ADMIN_CONSOLE,
    path: "/admin/about",
    component: () => import("../pages/admin/AdminAboutPage.vue").then((m) => m.default),
  },
  {
    id: "event-types",
    title: "Event types",
    icon: ICON_EVENT_TYPES,
    permission: ADMIN_EVENT_TYPES_VIEW,
    path: "/admin/event-types",
    component: () => import("../pages/admin/AdminEventTypesPage.vue").then((m) => m.default),
    // The record drawer as a child route (useDrawerRoute): /admin/event-types/:id.
    children: [
      {
        path: ":id",
        component: () => import("../pages/admin/AdminEventTypeDrawer.vue"),
        meta: { title: "Event types", permission: ADMIN_EVENT_TYPES_VIEW, admin: true },
      },
    ],
  },
  {
    id: "roles",
    title: "Roles",
    icon: ICON_ROLES,
    permission: ADMIN_ROLES_VIEW,
    path: "/admin/roles",
    component: () => import("../pages/admin/AdminRolesPage.vue").then((m) => m.default),
    children: [
      {
        path: ":id",
        component: () => import("../pages/admin/AdminRoleDrawer.vue"),
        meta: { title: "Roles", permission: ADMIN_ROLES_VIEW, admin: true },
      },
    ],
  },
  {
    id: "config",
    title: "Configuration",
    icon: ICON_CONFIG,
    permission: ADMIN_CONFIG_VIEW,
    path: "/admin/config",
    component: () => import("../pages/admin/AdminConfigPage.vue").then((m) => m.default),
  },
  {
    id: "orders",
    title: "Orders overview",
    icon: ICON_ORDERS,
    permission: ADMIN_ORDERS_VIEW,
    path: "/admin/orders",
    component: () => import("../pages/admin/AdminOrdersPage.vue").then((m) => m.default),
  },
];
