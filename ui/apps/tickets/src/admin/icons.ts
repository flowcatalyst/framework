// The admin area's constants that the Vite config also needs: data only, no .vue import (the config is
// bundled before the Vue plugin runs). The standard pages' permissions and icons come from
// @flowcatalyst/ui; only the service's own section is here.

/** The application code of the server's admin modules. */
export const APPLICATION = "tickets-trial";
/** Opens the admin area at all (`AdminModule.consoleView`). */
export const ADMIN_CONSOLE = "tickets-trial:admin:console:view";
/** The service's own section (`TicketsAdminModule.VIEW`). */
export const ADMIN_TICKETS_VIEW = "tickets-trial:admin:tickets:view";

export const ICON_TICKETS = "i-lucide-chart-no-axes-column";
/** The sidebar entry that leads into the area. */
export const ICON_ADMIN = "i-lucide-shield";

export const ADMIN_ICONS: readonly string[] = [ICON_TICKETS, ICON_ADMIN];
