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
    { path: "/signed-out", component: () => import("./pages/SignedOutPage.vue"), meta: { title: "Signed out", public: true } },
    { path: "/sign-in-failed", component: () => import("./pages/SignInFailedPage.vue"), meta: { title: "Sign-in failed", public: true } },
    { path: "/not-permitted", component: () => import("./pages/NotPermittedPage.vue"), meta: { title: "Not permitted" } },
    { path: "/unavailable", component: () => import("./pages/UnavailablePage.vue"), meta: { title: "Unavailable", public: true } },
    { path: "/:pathMatch(.*)*", component: () => import("./pages/NotFoundPage.vue"), meta: { title: "Not found" } },
  ],
});
