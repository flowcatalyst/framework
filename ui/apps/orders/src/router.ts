import { FwSignedOutPage, FwSignInFailedPage, FwUnavailablePage } from "@flowcatalyst/ui";
import { createRouter, createWebHistory } from "vue-router";
import { READ } from "./pages.ts";

// A client route and a server route never share a first segment (static-assets contract section 5):
// the API is under /api and /auth, the pages below are not.
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/orders" },
    {
      path: "/orders",
      component: () => import("./pages/OrdersPage.vue"),
      meta: { title: "Orders", permission: READ },
      // The record drawer as a child route (useDrawerRoute): /orders/:id.
      children: [{ path: ":id", component: () => import("./pages/OrderDrawer.vue"), meta: { title: "Orders", permission: READ } }],
    },
    { path: "/signed-out", component: FwSignedOutPage, props: { appName: "Orders", returnTo: "/orders" }, meta: { title: "Signed out", public: true } },
    { path: "/sign-in-failed", component: FwSignInFailedPage, props: { appName: "Orders", returnTo: "/orders" }, meta: { title: "Sign-in failed", public: true } },
    { path: "/not-permitted", component: () => import("./pages/NotPermittedPage.vue"), meta: { title: "Not permitted" } },
    { path: "/unavailable", component: FwUnavailablePage, props: { appName: "Orders", retryTo: "/orders" }, meta: { title: "Unavailable", public: true } },
    { path: "/:pathMatch(.*)*", component: () => import("./pages/NotFoundPage.vue"), meta: { title: "Not found" } },
  ],
});
