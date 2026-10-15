import { createRouter, createWebHistory, type RouteRecordRaw } from "vue-router";
import { FwSignedOutPage, FwSignInFailedPage, FwUnavailablePage } from "@flowcatalyst/ui";
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
  "/standalone": () => import("./pages/StandalonePage.vue"),
  "/shell": () => import("./pages/ShellPage.vue"),
  "/fonts": () => import("./pages/FontsPage.vue"),
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
  // The kit's pages outside the shell (U4): public routes, rendered without FwAppShell (App.vue).
  { path: "/standalone/signed-out", component: FwSignedOutPage, props: { appName: "Gallery", returnTo: "/standalone" }, meta: { title: "Signed out", public: true } },
  { path: "/standalone/sign-in-failed", component: FwSignInFailedPage, props: { appName: "Gallery", returnTo: "/standalone" }, meta: { title: "Sign-in failed", public: true } },
  { path: "/standalone/unavailable", component: FwUnavailablePage, props: { appName: "Gallery", retryTo: "/standalone" }, meta: { title: "Unavailable", public: true } },
  { path: "/signed-out", component: () => import("./pages/SignedOutPage.vue"), meta: { title: "Signed out" } },
  // Components known to break the strict CSP, kept so the e2e suite notices when that changes.
  { path: "/probes/drawer", component: () => import("./pages/probes/DrawerProbe.vue") },
  { path: "/probes/banner", component: () => import("./pages/probes/BannerProbe.vue") },
  { path: "/probes/splitter", component: () => import("./pages/probes/SplitterProbe.vue") },
  { path: "/:pathMatch(.*)*", component: () => import("./pages/NotFoundPage.vue") },
];

export const router = createRouter({ history: createWebHistory(), routes });

// The font comparison's `?font=` stays on while the reviewer moves between pages (src/fonts.ts).
router.beforeEach((to, from) => {
  const font = from.query["font"];
  if (typeof font === "string" && to.query["font"] === undefined) {
    return { path: to.path, query: { ...to.query, font }, hash: to.hash };
  }
  return true;
});
