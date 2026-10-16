// The pure parts of the admin shell (docs/contracts/admin.md section 6): navigation from the server's
// sections and the session's permissions, routes from the pages, breadcrumbs from the matched routes.
import type { RouteLocationMatched, RouteRecordRaw } from "vue-router";
import type { FwNavItem } from "../types.ts";
import type { AdminPage, AdminSectionView } from "./types.ts";

/** The admin area's base route. */
export const ADMIN_BASE = "/admin";

const PERMISSION = /^[^:\s]+:[^:\s]+:[^:\s]+:[^:\s]+$/;
const ID = /^[a-z][a-z0-9-]*$/;

/**
 * The navigation items: a page is shown when the server listed its section (the server already
 * filtered by permission, layer 2) and the session holds the permission of both the section and the
 * page (layer 3, courtesy: the server decides). A listed section without a page, and a page the
 * server did not list, are not shown. `sections` undefined (not loaded, or failed) shows nothing.
 */
export function adminNavigation(
  pages: readonly AdminPage[],
  sections: readonly AdminSectionView[] | undefined,
  can: (permission: string) => boolean,
): FwNavItem[] {
  if (sections === undefined) {
    return [];
  }
  const items: FwNavItem[] = [];
  for (const section of sections) {
    const page = pages.find((candidate) => candidate.id === section.id);
    if (page !== undefined && can(section.permission) && can(page.permission)) {
      items.push({ label: page.title, to: page.path, icon: page.icon, permission: page.permission });
    }
  }
  return items;
}

/** The ids the server listed that no page serves (reported once in development: a section without a UI). */
export function sectionsWithoutPage(pages: readonly AdminPage[], sections: readonly AdminSectionView[]): string[] {
  return sections.filter((section) => !pages.some((page) => page.id === section.id)).map((section) => section.id);
}

/**
 * The vue-router records of the admin area: one per page, each with `meta.permission` (so
 * `installSessionGuard` refuses a session without it), `meta.title`, `meta.breadcrumb` and
 * `meta.admin`; plus `base` redirecting to the first page (the page with id `about` when there is
 * one). Refuses a page without a four-segment permission, a bad id, a path outside `base`, and a
 * duplicate id or path: a route no one guards is a defect, not a default.
 */
export function adminRoutes(pages: readonly AdminPage[], base: string = ADMIN_BASE): RouteRecordRaw[] {
  const problems: string[] = [];
  const ids = new Set<string>();
  const paths = new Set<string>();
  for (const page of pages) {
    if (!ID.test(page.id)) {
      problems.push(`page '${page.id}': the id must match [a-z][a-z0-9-]*`);
    }
    if (!PERMISSION.test(page.permission)) {
      problems.push(`page '${page.id}': the permission must have four ':'-separated segments`);
    }
    if (page.path !== base && !page.path.startsWith(`${base}/`)) {
      problems.push(`page '${page.id}': the path must be ${base} or under it`);
    }
    if (ids.has(page.id)) {
      problems.push(`page id '${page.id}' is used twice`);
    }
    if (paths.has(page.path)) {
      problems.push(`page path '${page.path}' is used twice`);
    }
    ids.add(page.id);
    paths.add(page.path);
  }
  if (problems.length > 0) {
    throw new Error(`admin pages: ${problems.join("; ")}`);
  }
  const routes: RouteRecordRaw[] = pages.map((page) => ({
    path: page.path,
    component: page.component,
    meta: { title: page.title, permission: page.permission, breadcrumb: page.title, admin: true },
    ...(page.children === undefined ? {} : { children: [...page.children] }),
  }));
  const landing = pages.find((page) => page.id === "about") ?? pages[0];
  if (landing !== undefined && landing.path !== base) {
    routes.unshift({ path: base, redirect: landing.path, meta: { admin: true } });
  }
  return routes;
}

export interface Crumb {
  readonly label: string;
  /** Absent on the last crumb: the current page is text with `aria-current`. */
  readonly to?: string;
}

/** Breadcrumbs: `Admin`, then the `meta.breadcrumb` of each matched route; the last one is not a link. */
export function adminBreadcrumbs(matched: readonly Pick<RouteLocationMatched, "path" | "meta">[], base: string = ADMIN_BASE): Crumb[] {
  const crumbs: Crumb[] = [{ label: "Admin", to: base }];
  for (const record of matched) {
    const label = record.meta.breadcrumb;
    if (label !== undefined && record.path !== base) {
      crumbs.push({ label, to: record.path });
    }
  }
  const last = crumbs[crumbs.length - 1];
  if (last !== undefined && crumbs.length > 1) {
    crumbs[crumbs.length - 1] = { label: last.label };
  }
  return crumbs;
}
