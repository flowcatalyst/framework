// The service's admin pages (docs/contracts/admin.md sections 6 and 13): the framework's about and
// configuration pages (standard pages of @flowcatalyst/ui, reading the calls passed in main.ts) and the
// service's own section (TicketsAdminModule). Each page matches a server section by id; the permissions
// are the server's. The server lists only these sections, so only these standard pages are mounted.
import { standardAdminPages, type AdminPage } from "@flowcatalyst/ui";
import { ADMIN_CONSOLE, ADMIN_TICKETS_VIEW, APPLICATION, ICON_TICKETS } from "./icons.ts";

export { ADMIN_CONSOLE, ADMIN_TICKETS_VIEW };

export const adminPages: readonly AdminPage[] = [
  ...standardAdminPages(APPLICATION, ["about", "config"]),
  {
    id: "tickets",
    title: "Queue overview",
    icon: ICON_TICKETS,
    permission: ADMIN_TICKETS_VIEW,
    path: "/admin/tickets",
    component: () => import("../pages/admin/AdminTicketsPage.vue").then((m) => m.default),
  },
];
