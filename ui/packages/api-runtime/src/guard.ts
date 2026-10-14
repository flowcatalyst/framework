// The router guard of a FlowCatalyst SPA: every route needs a signed-in session unless its meta
// says `public: true`; a route with `meta.permission` also needs that permission (shown or hidden
// only: the server checks every operation). The platform SPA's createRoutePermissionGuard is the
// model, including its rule that an automatic landing never shows a denial.
import { START_LOCATION, type RouteLocationNormalized, type RouteLocationRaw, type Router } from "vue-router";
import type { BrowserSession } from "./session.ts";

declare module "vue-router" {
  interface RouteMeta {
    /** Reachable without a session (a signed-out page, a public landing page). */
    public?: boolean;
    /** The permission the session must hold to open the route (core contract section 3 matching). */
    permission?: string;
  }
}

export interface SessionGuardOptions {
  /**
   * Where a signed-in user without the route's permission goes (the not-permitted page, U4).
   * Without it the navigation is cancelled.
   */
  readonly notPermitted?: (to: RouteLocationNormalized) => RouteLocationRaw;
  /**
   * Where an automatic landing goes when it lacks the permission (the first page load, the page
   * after sign-in): quietly somewhere the user may be (a profile page) instead of a denial.
   * Without it the not-permitted page is used.
   */
  readonly landing?: (to: RouteLocationNormalized) => RouteLocationRaw;
  /** Called when the session could not be read at all (offline, 5xx); the navigation is cancelled. */
  readonly onUnavailable?: (error: unknown) => void;
}

/** Installs the guard; returns the function that removes it. */
export function installSessionGuard(router: Router, session: BrowserSession, options: SessionGuardOptions = {}): () => void {
  return router.beforeEach(async (to, from) => {
    if (to.meta.public === true) {
      return true;
    }
    let answer;
    try {
      answer = session.current.value ?? (await session.start());
    } catch (error) {
      options.onUnavailable?.(error);
      return false;
    }
    if (!answer.authenticated) {
      session.signIn(to.fullPath);
      return false;
    }
    const required = to.meta.permission;
    if (required === undefined || session.can(required)) {
      return true;
    }
    // Only a deliberate navigation shows a denial; an automatic landing goes somewhere usable.
    const automatic = from === START_LOCATION || from.matched.length === 0;
    if (automatic && options.landing !== undefined) {
      return options.landing(to);
    }
    return options.notPermitted?.(to) ?? false;
  });
}
