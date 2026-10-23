// The framework's admin data pages as `AdminPage` values (docs/contracts/admin.md section 13): the
// application mounts the ones its server has and replaces any it wants to build itself. Data only
// (components load lazily), so a Vite config could read it too.
import type { AdminPage } from "./types.ts";
import { adminPermissions } from "./data.ts";

/** The ids of the standard pages; each is the id of the server section that serves it. */
export const STANDARD_ADMIN_PAGE_IDS = ["about", "event-types", "roles", "users", "activity", "config", "platform-config"] as const;
export type StandardAdminPageId = (typeof STANDARD_ADMIN_PAGE_IDS)[number];

export const ADMIN_ICON_ABOUT = "i-lucide-info";
export const ADMIN_ICON_EVENT_TYPES = "i-lucide-zap";
export const ADMIN_ICON_ROLES = "i-lucide-key-round";
export const ADMIN_ICON_USERS = "i-lucide-users";
export const ADMIN_ICON_ACTIVITY = "i-lucide-activity";
export const ADMIN_ICON_CONFIG = "i-lucide-sliders-horizontal";
export const ADMIN_ICON_PLATFORM_CONFIG = "i-lucide-key-round";

/**
 * The standard pages for an application (its `AdminModule` application code), in navigation order.
 * `ids` picks a subset (an application whose server has only the about and config sections passes
 * `["about", "config"]`); the default is all seven. The pages read the calls and the application code from
 * the plugin option `admin` (`AdminDataOptions`), so install it.
 */
export function standardAdminPages(application: string, ids: readonly StandardAdminPageId[] = STANDARD_ADMIN_PAGE_IDS): AdminPage[] {
  const permissions = adminPermissions(application);
  const all: Record<StandardAdminPageId, AdminPage> = {
    about: {
      id: "about",
      title: "About",
      icon: ADMIN_ICON_ABOUT,
      permission: permissions.console,
      path: "/admin/about",
      component: () => import("./pages/AdminAboutPage.vue").then((m) => m.default),
    },
    "event-types": {
      id: "event-types",
      title: "Event types",
      icon: ADMIN_ICON_EVENT_TYPES,
      permission: permissions.eventTypesView,
      path: "/admin/event-types",
      component: () => import("./pages/AdminEventTypesPage.vue").then((m) => m.default),
      // The record drawer as a child route (useDrawerRoute): /admin/event-types/:id.
      children: [
        {
          path: ":id",
          component: () => import("./pages/AdminEventTypeDrawer.vue"),
          meta: { title: "Event types", permission: permissions.eventTypesView, admin: true },
        },
      ],
    },
    roles: {
      id: "roles",
      title: "Roles",
      icon: ADMIN_ICON_ROLES,
      permission: permissions.rolesView,
      path: "/admin/roles",
      component: () => import("./pages/AdminRolesPage.vue").then((m) => m.default),
      children: [
        {
          path: ":id",
          component: () => import("./pages/AdminRoleDrawer.vue"),
          meta: { title: "Roles", permission: permissions.rolesView, admin: true },
        },
      ],
    },
    users: {
      id: "users",
      title: "Users",
      icon: ADMIN_ICON_USERS,
      permission: permissions.usersView,
      path: "/admin/users",
      component: () => import("./pages/AdminUsersPage.vue").then((m) => m.default),
      children: [
        {
          path: ":id",
          component: () => import("./pages/AdminUserDrawer.vue"),
          meta: { title: "Users", permission: permissions.usersView, admin: true },
        },
      ],
    },
    activity: {
      id: "activity",
      title: "Activity",
      icon: ADMIN_ICON_ACTIVITY,
      permission: permissions.activityView,
      path: "/admin/activity",
      component: () => import("./pages/AdminActivityPage.vue").then((m) => m.default),
      // Two drawers over one list: an event and a dispatch job (/admin/activity/events/:id, /jobs/:id).
      children: [
        {
          path: "events/:id",
          component: () => import("./pages/AdminEventDrawer.vue"),
          meta: { title: "Activity", permission: permissions.activityView, admin: true },
        },
        {
          path: "jobs/:id",
          component: () => import("./pages/AdminJobDrawer.vue"),
          meta: { title: "Activity", permission: permissions.activityView, admin: true },
        },
      ],
    },
    config: {
      id: "config",
      title: "Configuration",
      icon: ADMIN_ICON_CONFIG,
      permission: permissions.configView,
      path: "/admin/config",
      component: () => import("./pages/AdminConfigPage.vue").then((m) => m.default),
    },
    "platform-config": {
      id: "platform-config",
      title: "Platform config",
      icon: ADMIN_ICON_PLATFORM_CONFIG,
      permission: permissions.platformConfigView,
      path: "/admin/platform-config",
      component: () => import("./pages/AdminPlatformConfigPage.vue").then((m) => m.default),
    },
  };
  return ids.map((id) => all[id]);
}

/**
 * Puts `page` in the list in place of the page with the same id (an application's own users page, its
 * own about page); a page whose id is not in the list is added at the end. The list is not changed.
 */
export function replaceAdminPage(pages: readonly AdminPage[], page: AdminPage): AdminPage[] {
  return pages.some((candidate) => candidate.id === page.id)
    ? pages.map((candidate) => (candidate.id === page.id ? page : candidate))
    : [...pages, page];
}
