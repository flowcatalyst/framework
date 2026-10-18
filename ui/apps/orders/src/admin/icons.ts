// The admin area's constants that the Vite config also needs: data only, no .vue import (the config is
// bundled before the Vue plugin runs). The standard pages' permissions and icons come from
// @flowcatalyst/ui (`adminPermissions`, the package's own icon list); only the demo's own section is here.

/** The application code of the server's admin modules. */
export const APPLICATION = "orders-demo";
/** Opens the admin area at all (`AdminModule.consoleView`). */
export const ADMIN_CONSOLE = "orders-demo:admin:console:view";
/** The demo's own section (`OrdersAdminModule.VIEW`). */
export const ADMIN_ORDERS_VIEW = "orders-demo:admin:orders:view";

export const ICON_ORDERS = "i-lucide-chart-no-axes-column";
/** The sidebar entry that leads into the area. */
export const ICON_ADMIN = "i-lucide-shield";

export const ADMIN_ICONS: readonly string[] = [ICON_ORDERS, ICON_ADMIN];
