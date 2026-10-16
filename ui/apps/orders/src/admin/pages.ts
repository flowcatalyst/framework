// The orders application's admin pages (M4d, docs/contracts/admin.md section 6): data only, so the Vite
// config (icons Nuxt UI cannot find in .ts data), the router, the shell and the Playwright suite read
// the same list. Each page matches a server section by id: `about` is the framework's own, `orders` is
// the demo's (OrdersAdminModule). The permissions are the server's.
import type { AdminPage } from "@flowcatalyst/ui";

import { ADMIN_CONSOLE, ADMIN_ORDERS_VIEW, ICON_ABOUT, ICON_ORDERS } from "./icons.ts";

export { ADMIN_CONSOLE, ADMIN_ORDERS_VIEW };

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
    id: "orders",
    title: "Orders overview",
    icon: ICON_ORDERS,
    permission: ADMIN_ORDERS_VIEW,
    path: "/admin/orders",
    component: () => import("../pages/admin/AdminOrdersPage.vue").then((m) => m.default),
  },
];

