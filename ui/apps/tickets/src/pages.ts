// The sidebar's pages: data only, so the Vite config (icons Nuxt UI cannot find in .ts data), the
// router and the Playwright suite read the same list. The permissions are the server's (the
// service's Permissions class); the server checks every call anyway.

export interface TicketsPage {
  readonly path: string;
  /** The sidebar label. */
  readonly title: string;
  readonly icon: string;
  /** The permission that shows the entry and opens the route. */
  readonly permission: string;
}

/** Read tickets and the SLA policy. */
export const READ = "tickets-trial:support:ticket:read";
/** Open a ticket. */
export const OPEN = "tickets-trial:support:ticket:open";
/** Assign tickets and move them along their status machine. */
export const WORK = "tickets-trial:support:ticket:work";
/** Comment on a ticket. */
export const COMMENT = "tickets-trial:support:ticket:comment";
/** Change the client's SLA policy. */
export const SLA_MANAGE = "tickets-trial:support:sla:manage";

export const pages: readonly TicketsPage[] = [
  { path: "/tickets", title: "Tickets", icon: "i-lucide-ticket", permission: READ },
  { path: "/sla", title: "SLA policy", icon: "i-lucide-timer", permission: READ },
];
