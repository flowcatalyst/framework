import { adminRoutes, FwSignedOutPage, FwSignInFailedPage, FwUnavailablePage } from "@flowcatalyst/ui";
import { createRouter, createWebHistory } from "vue-router";
import { adminPages } from "./admin/pages.ts";
import { READ } from "./pages.ts";

// A client route and a server route never share a first segment (static-assets contract section 5):
// the API is under /api and /auth, the pages below are not.
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: "/", redirect: "/tickets" },
    {
      path: "/tickets",
      component: () => import("./pages/TicketsPage.vue"),
      meta: { title: "Tickets", permission: READ },
      // The record drawer as a child route (useDrawerRoute): /tickets/:id.
      children: [{ path: ":id", component: () => import("./pages/TicketDrawer.vue"), meta: { title: "Tickets", permission: READ } }],
    },
    { path: "/sla", component: () => import("./pages/SlaPage.vue"), meta: { title: "SLA policy", permission: READ } },
    // The admin area (M4d): every route carries its permission, so the session guard refuses the rest.
    ...adminRoutes(adminPages),
    { path: "/signed-out", component: FwSignedOutPage, props: { appName: "Tickets", returnTo: "/tickets" }, meta: { title: "Signed out", public: true } },
    { path: "/sign-in-failed", component: FwSignInFailedPage, props: { appName: "Tickets", returnTo: "/tickets" }, meta: { title: "Sign-in failed", public: true } },
    { path: "/not-permitted", component: () => import("./pages/NotPermittedPage.vue"), meta: { title: "Not permitted" } },
    { path: "/unavailable", component: FwUnavailablePage, props: { appName: "Tickets", retryTo: "/tickets" }, meta: { title: "Unavailable", public: true } },
    { path: "/:pathMatch(.*)*", component: () => import("./pages/NotFoundPage.vue"), meta: { title: "Not found" } },
  ],
});
