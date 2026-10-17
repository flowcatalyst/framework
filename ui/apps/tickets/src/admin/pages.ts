// The service's admin pages (docs/contracts/admin.md section 6): data only, so the Vite config, the
// router, the shell and the Playwright suite read the same list. Each page matches a server section by
// id: `about` is the framework's own, `config` is AdminConfigModule's, `tickets` is the service's
// (TicketsAdminModule). The permissions are the server's.
import type { AdminPage } from "@flowcatalyst/ui";
import { ADMIN_CONFIG_VIEW, ADMIN_CONSOLE, ADMIN_TICKETS_VIEW, ICON_ABOUT, ICON_CONFIG, ICON_TICKETS } from "./icons.ts";

export { ADMIN_CONFIG_VIEW, ADMIN_CONSOLE, ADMIN_TICKETS_VIEW };

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
    id: "config",
    title: "Configuration",
    icon: ICON_CONFIG,
    permission: ADMIN_CONFIG_VIEW,
    path: "/admin/config",
    component: () => import("../pages/admin/AdminConfigPage.vue").then((m) => m.default),
  },
  {
    id: "tickets",
    title: "Queue overview",
    icon: ICON_TICKETS,
    permission: ADMIN_TICKETS_VIEW,
    path: "/admin/tickets",
    component: () => import("../pages/admin/AdminTicketsPage.vue").then((m) => m.default),
  },
];
