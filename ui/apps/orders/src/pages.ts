// The sidebar's pages: data only, so the Vite config (icons Nuxt UI cannot find in .ts data), the
// router and the Playwright suite read the same list.

export interface OrdersPage {
  readonly path: string;
  /** The sidebar label. */
  readonly title: string;
  readonly icon: string;
  /** The permission that shows the entry and opens the route (the server checks every call anyway). */
  readonly permission: string;
}

export const READ = "orders:order:read";
export const MANAGE = "orders:order:manage";

export const pages: readonly OrdersPage[] = [{ path: "/orders", title: "Orders", icon: "i-lucide-shopping-cart", permission: READ }];
