// The admin area's constants that the Vite config also needs: data only, no .vue import (the config is
// bundled before the Vue plugin runs). Icons named in .ts data are listed for Nuxt UI's bundle scan.

/** Opens the admin area at all (`AdminModule.consoleView`). */
export const ADMIN_CONSOLE = "orders-demo:admin:console:view";
/** Event types, roles and config (`PlatformAdminModule`, `AdminConfigModule`). */
export const ADMIN_EVENT_TYPES_VIEW = "orders-demo:admin:event-types:view";
export const ADMIN_ROLES_VIEW = "orders-demo:admin:roles:view";
export const ADMIN_CONFIG_VIEW = "orders-demo:admin:config:view";
/** Users and invitations (`PlatformUsersAdminModule`) and the activity of events and jobs (`PlatformActivityAdminModule`). */
export const ADMIN_USERS_VIEW = "orders-demo:admin:users:view";
export const ADMIN_USERS_INVITE = "orders-demo:admin:users:invite";
export const ADMIN_USERS_RESEND = "orders-demo:admin:users:resend";
export const ADMIN_USERS_REVOKE = "orders-demo:admin:users:revoke";
export const ADMIN_ACTIVITY_VIEW = "orders-demo:admin:activity:view";
/** The demo's own section (`OrdersAdminModule.VIEW`). */
export const ADMIN_ORDERS_VIEW = "orders-demo:admin:orders:view";

export const ICON_ABOUT = "i-lucide-info";
export const ICON_EVENT_TYPES = "i-lucide-zap";
export const ICON_ROLES = "i-lucide-key-round";
export const ICON_USERS = "i-lucide-users";
export const ICON_ACTIVITY = "i-lucide-activity";
export const ICON_CONFIG = "i-lucide-sliders-horizontal";
export const ICON_ORDERS = "i-lucide-chart-no-axes-column";
/** The sidebar entry that leads into the area. */
export const ICON_ADMIN = "i-lucide-shield";

export const ADMIN_ICONS: readonly string[] = [ICON_ABOUT, ICON_EVENT_TYPES, ICON_ROLES, ICON_USERS, ICON_ACTIVITY, ICON_CONFIG, ICON_ORDERS, ICON_ADMIN];
