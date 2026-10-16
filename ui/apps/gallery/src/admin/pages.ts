// The gallery's admin pages (M4d): placeholders for what M4e fills, to show the shell. The ids match
// the fake server's sections (src/fake-server.ts); `events` is listed by the server but has no page here
// on purpose (a section without a UI is not shown).
import type { AdminPage } from "@flowcatalyst/ui";

export const CONSOLE = "gallery:admin:console:view";

export const adminPages: readonly AdminPage[] = [
  { id: "about", title: "About", icon: "i-lucide-info", permission: CONSOLE, path: "/admin/about", component: () => import("../pages/admin/GalleryAdminAbout.vue").then((m) => m.default) },
  { id: "users", title: "Users", icon: "i-lucide-users", permission: "gallery:admin:users:view", path: "/admin/users", component: () => import("../pages/admin/GalleryAdminStub.vue").then((m) => m.default) },
  { id: "roles", title: "Roles", icon: "i-lucide-key-round", permission: "gallery:admin:roles:view", path: "/admin/roles", component: () => import("../pages/admin/GalleryAdminStub.vue").then((m) => m.default) },
];
