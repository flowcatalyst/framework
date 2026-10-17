// The admin area's constants that the Vite config also needs: data only, no .vue import (the config is
// bundled before the Vue plugin runs). Icons named in .ts data are listed for Nuxt UI's bundle scan.

/** Opens the admin area at all (`AdminModule.consoleView`). */
export const ADMIN_CONSOLE = "tickets-trial:admin:console:view";
/** The framework's configuration section (`AdminConfigModule`). */
export const ADMIN_CONFIG_VIEW = "tickets-trial:admin:config:view";
/** The service's own section (`TicketsAdminModule.VIEW`). */
export const ADMIN_TICKETS_VIEW = "tickets-trial:admin:tickets:view";

export const ICON_ABOUT = "i-lucide-info";
export const ICON_CONFIG = "i-lucide-sliders-horizontal";
export const ICON_TICKETS = "i-lucide-chart-no-axes-column";
/** The sidebar entry that leads into the area. */
export const ICON_ADMIN = "i-lucide-shield";

export const ADMIN_ICONS: readonly string[] = [ICON_ABOUT, ICON_CONFIG, ICON_TICKETS, ICON_ADMIN];
