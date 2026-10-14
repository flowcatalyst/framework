import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { pages } from "./pages.ts";

const components: Readonly<Record<string, RouteRecordRaw["component"]>> = {
  "/": () => import("./pages/HomePage.vue"),
  "/actions": () => import("./pages/ActionsPage.vue"),
  "/overlays": () => import("./pages/OverlaysPage.vue"),
  "/forms": () => import("./pages/FormsPage.vue"),
  "/data": () => import("./pages/DataPage.vue"),
  "/feedback": () => import("./pages/FeedbackPage.vue"),
  "/values": () => import("./pages/ValuesPage.vue"),
  "/lists": () => import("./pages/ListsPage.vue"),
  "/shell": () => import("./pages/ShellPage.vue"),
};

const routes: RouteRecordRaw[] = [
  ...pages
    .filter((page) => page.path !== "/orders")
    .map((page) => ({ path: page.path, component: components[page.path] ?? (() => import("./pages/NotFoundPage.vue")), meta: { title: page.title } })),
  // The list page and its record drawer as a child route (useDrawerRoute): /orders/:id.
  {
    path: "/orders",
    component: () => import("./pages/orders/OrdersPage.vue"),
    meta: { title: "Orders (partials)" },
    children: [{ path: ":id", component: () => import("./pages/orders/OrderDrawer.vue"), meta: { title: "Orders (partials)" } }],
  },
  { path: "/signed-out", component: () => import("./pages/SignedOutPage.vue"), meta: { title: "Signed out" } },
  // Components known to break the strict CSP, kept so the e2e suite notices when that changes.
  { path: "/probes/drawer", component: () => import("./pages/probes/DrawerProbe.vue") },
  { path: "/probes/banner", component: () => import("./pages/probes/BannerProbe.vue") },
  { path: "/probes/splitter", component: () => import("./pages/probes/SplitterProbe.vue") },
  { path: "/:pathMatch(.*)*", component: () => import("./pages/NotFoundPage.vue") },
];

export const router = createRouter({ history: createWebHistory(), routes });
