// The admin skeleton's shared types (docs/contracts/admin.md sections 4 and 6). The wire views are
// structural copies of the Java and Go frameworks' `AdminNavigation` and `AdminAbout` components: the
// package holds no generated client, so an application passes its own client's answers in (a generated
// type is assignable to these).
import type { Component } from "vue";
import type { RouteRecordRaw } from "vue-router";

/** One section of `GET /api/admin/navigation`. */
export interface AdminSectionView {
  readonly id: string;
  readonly title: string;
  readonly path: string;
  readonly permission: string;
  readonly icon?: string | undefined;
  readonly description?: string | undefined;
}

/** The answer of `GET /api/admin/navigation`. */
export interface AdminNavigationView {
  readonly sections: readonly AdminSectionView[];
}

/** One check of `GET /api/admin/about`. */
export interface AdminCheckView {
  readonly name: string;
  readonly status: "UP" | "DOWN" | "TIMED_OUT";
  readonly critical: boolean;
}

/** The answer of `GET /api/admin/about`. */
export interface AdminAboutView {
  readonly application: string;
  readonly framework?: string | undefined;
  readonly environment: string;
  readonly startedAt: string;
  readonly ready: boolean;
  readonly checks: readonly AdminCheckView[];
}

/**
 * The page-kit contract M4e pages fill: one admin page, matching one server section by `id`. A page is
 * built from the kit's patterns (FwListPage + FwRecordDrawer + FwFormDialog) and registered in the
 * application's list of pages; the shell derives navigation, routes and breadcrumbs from the list.
 */
export interface AdminPage {
  /** The server section's id (`[a-z][a-z0-9-]*`). */
  readonly id: string;
  /** The navigation label and the breadcrumb. */
  readonly title: string;
  /** A Lucide icon (`i-lucide-` and its name); list it in the Vite plugin's `icons` (it is named in `.ts` data). */
  readonly icon: string;
  /** The permission that opens the route and shows the entry: the server section's, four segments. */
  readonly permission: string;
  /** The route: absolute, `/admin` or under it; equal to the server section's `path`. */
  readonly path: string;
  /** The page, lazily. */
  readonly component: () => Promise<Component>;
  /** Child routes of the page (a record drawer, `useDrawerRoute`). */
  readonly children?: readonly RouteRecordRaw[];
}

declare module "vue-router" {
  interface RouteMeta {
    /** The route belongs to the admin area: the application renders it in `FwAdminShell`. */
    admin?: boolean;
    /** The breadcrumb label this route adds (`adminRoutes` sets it from the page's title). */
    breadcrumb?: string;
  }
}
