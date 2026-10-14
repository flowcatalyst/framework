import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";

export interface GalleryPage {
  readonly path: string;
  readonly title: string;
}

/** The gallery's pages, in sidebar order. Every page is loaded lazily, so a probe's code never runs on another page. */
export const pages: readonly GalleryPage[] = [
  { path: "/", title: "Overview" },
  { path: "/overlays", title: "Overlays" },
  { path: "/forms", title: "Forms" },
  { path: "/data", title: "Data display" },
];

const routes: RouteRecordRaw[] = [
  { path: "/", component: () => import("./pages/HomePage.vue"), meta: { title: "Overview" } },
  { path: "/overlays", component: () => import("./pages/OverlaysPage.vue"), meta: { title: "Overlays" } },
  { path: "/forms", component: () => import("./pages/FormsPage.vue"), meta: { title: "Forms" } },
  { path: "/data", component: () => import("./pages/DataPage.vue"), meta: { title: "Data display" } },
  // Components known to break the strict CSP, kept so the e2e suite notices when that changes.
  { path: "/probes/drawer", component: () => import("./pages/probes/DrawerProbe.vue") },
  { path: "/probes/banner", component: () => import("./pages/probes/BannerProbe.vue") },
  { path: "/probes/splitter", component: () => import("./pages/probes/SplitterProbe.vue") },
  { path: "/:pathMatch(.*)*", component: () => import("./pages/NotFoundPage.vue") },
];

export const router = createRouter({ history: createWebHistory(), routes });
