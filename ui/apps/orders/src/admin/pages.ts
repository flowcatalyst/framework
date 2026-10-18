// The orders application's admin pages (docs/contracts/admin.md sections 6 and 13): the framework's
// standard pages (about, event types, roles, users, activity, configuration; they live in
// @flowcatalyst/ui and read the calls passed in main.ts) and the demo's own section. Each page matches
// a server section by id; the permissions are the server's. Data only: the Vite config reads
// `ADMIN_ICONS` from ./icons.ts, the router and the shell read the list.
import { standardAdminPages, type AdminPage } from "@flowcatalyst/ui";
import { ADMIN_CONSOLE, ADMIN_ORDERS_VIEW, APPLICATION, ICON_ORDERS } from "./icons.ts";

export { ADMIN_CONSOLE, ADMIN_ORDERS_VIEW };

export const adminPages: readonly AdminPage[] = [
  ...standardAdminPages(APPLICATION),
  {
    id: "orders",
    title: "Orders overview",
    icon: ICON_ORDERS,
    permission: ADMIN_ORDERS_VIEW,
    path: "/admin/orders",
    component: () => import("../pages/admin/AdminOrdersPage.vue").then((m) => m.default),
  },
];
