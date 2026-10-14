// The browser session and its guard: sessions contract section 4.5, rules 3, 4 and 6.
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter, type Router } from "vue-router";
import { defineComponent } from "vue";
import { installSessionGuard } from "../src/guard.ts";
import { createApiRuntime } from "../src/runtime.ts";
import { createBrowserSession, signInUrl, type BrowserSession } from "../src/session.ts";
import type { AuthSession } from "../src/generated/auth/types.gen.ts";
import { fakeFetch } from "./fake-fetch.ts";

const signedIn: AuthSession = {
  authenticated: true,
  principal: { id: "prn_1", kind: "USER", name: "Ada Lovelace", email: "ada@example.com" },
  client: "acme",
  clients: ["*"],
  applications: ["*"],
  roles: ["orders:clerk"],
  permissions: ["orders:order:read"],
  authTime: "2026-10-14T08:00:00Z",
  expiresAt: "2026-10-14T16:00:00Z",
  idleExpiresAt: "2026-10-14T08:30:00Z",
  csrf: { mode: "fetch-metadata", jsonBodies: true },
};
const anonymous: AuthSession = { authenticated: false, loginUrl: "/auth/login" };

class Focus {
  readonly listeners = new Set<() => void>();
  addEventListener(_type: "focus", listener: () => void): void {
    this.listeners.add(listener);
  }
  removeEventListener(_type: "focus", listener: () => void): void {
    this.listeners.delete(listener);
  }
  fire(): void {
    for (const listener of this.listeners) {
      listener();
    }
  }
}

interface Harness {
  session: BrowserSession;
  reads: () => number;
  answers: AuthSession[];
  navigations: string[];
  focus: Focus;
}

function harness(...answers: AuthSession[]): Harness {
  let reads = 0;
  const navigations: string[] = [];
  const focus = new Focus();
  const queue = [...answers];
  const session = createBrowserSession({
    read: () => {
      reads += 1;
      const next = queue.length > 1 ? queue.shift() : queue[0];
      return next === undefined ? Promise.reject(new Error("no answer")) : Promise.resolve(next);
    },
    signOut: () => Promise.resolve({ loggedOut: true }),
    navigate: (url) => navigations.push(url),
    location: () => ({ pathname: "/orders/ord_1", search: "?tab=lines", hash: "#l2" }),
    focusTarget: focus,
    signedOutUrl: "/signed-out",
  });
  return { session, reads: () => reads, answers, navigations, focus };
}

afterEach(() => {
  vi.useRealTimers();
});

describe("browser session", () => {
  it("builds the sign-in URL with returnTo percent-encoded", () => {
    expect(signInUrl("/auth/login", "/orders/ord_1?tab=lines#l2")).toBe("/auth/login?returnTo=%2Forders%2Ford_1%3Ftab%3Dlines%23l2");
  });

  it("reads once at start (concurrent callers share the read) and exposes the user and permissions", async () => {
    const h = harness(signedIn);
    const [a, b] = await Promise.all([h.session.start(), h.session.start()]);
    expect(a).toBe(b);
    expect(h.reads()).toBe(1);
    expect(h.session.user.value?.principal.name).toBe("Ada Lovelace");
    expect(h.session.can("orders:order:read")).toBe(true);
    expect(h.session.can("orders:order:manage")).toBe(false);
  });

  it("re-reads on focus, never on a timer", async () => {
    vi.useFakeTimers();
    const h = harness(signedIn);
    await h.session.start();
    await vi.advanceTimersByTimeAsync(8 * 60 * 60 * 1000);
    expect(h.reads()).toBe(1);
    h.focus.fire();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.reads()).toBe(2);
    h.session.stop();
    h.focus.fire();
    await vi.advanceTimersByTimeAsync(0);
    expect(h.reads()).toBe(2);
  });

  it("navigates to sign-in when the session ended while the page was open (focus re-read)", async () => {
    const h = harness(signedIn, anonymous);
    await h.session.start();
    h.focus.fire();
    await vi.waitFor(() => {
      expect(h.navigations).toEqual(["/auth/login?returnTo=%2Forders%2Ford_1%3Ftab%3Dlines%23l2"]);
    });
  });

  it("logout navigates to the end-session URL when present, else to the signed-out page", async () => {
    const h = harness(signedIn);
    await h.session.start();
    await h.session.logout();
    expect(h.navigations).toEqual(["/signed-out"]);
    expect(h.session.current.value).toBeUndefined();

    const navigations: string[] = [];
    const s = createBrowserSession({
      read: () => Promise.resolve(signedIn),
      signOut: () => Promise.resolve({ loggedOut: true, endSessionUrl: "https://platform.example/auth/oidc/session/end?client_id=x" }),
      navigate: (url) => navigations.push(url),
      focusTarget: new Focus(),
    });
    await s.logout();
    expect(navigations).toEqual(["https://platform.example/auth/oidc/session/end?client_id=x"]);
  });

  it("logout that fails does not navigate", async () => {
    const navigations: string[] = [];
    const s = createBrowserSession({
      read: () => Promise.resolve(signedIn),
      signOut: () => Promise.reject(new Error("403")),
      navigate: (url) => navigations.push(url),
      focusTarget: new Focus(),
    });
    await expect(s.logout()).rejects.toThrow("403");
    expect(navigations).toEqual([]);
  });
});

describe("session guard", () => {
  const Page = defineComponent({ render: () => null });

  function router(): Router {
    return createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: "/", component: Page },
        { path: "/orders", component: Page, meta: { permission: "orders:order:read" } },
        { path: "/admin", component: Page, meta: { permission: "orders:admin:manage" } },
        { path: "/signed-out", component: Page, meta: { public: true } },
        { path: "/not-permitted", component: Page },
        { path: "/profile", component: Page },
      ],
    });
  }

  let h: Harness;
  let r: Router;
  beforeEach(() => {
    h = harness(signedIn);
    r = router();
    installSessionGuard(r, h.session, { notPermitted: () => "/not-permitted", landing: () => "/profile" });
  });

  it("lets a public route through without reading the session", async () => {
    await r.push("/signed-out");
    expect(r.currentRoute.value.path).toBe("/signed-out");
    expect(h.reads()).toBe(0);
  });

  it("reads the session before the first guarded route and allows a held permission", async () => {
    await r.push("/orders");
    expect(r.currentRoute.value.path).toBe("/orders");
    expect(h.reads()).toBe(1);
  });

  it("sends an automatic landing without the permission to the landing, a deliberate one to not-permitted", async () => {
    await r.push("/admin");
    expect(r.currentRoute.value.path).toBe("/profile");
    await r.push("/admin");
    expect(r.currentRoute.value.path).toBe("/not-permitted");
  });

  it("sends a signed-out user to sign-in with the target as returnTo", async () => {
    const a = harness(anonymous);
    const ra = router();
    installSessionGuard(ra, a.session);
    await ra.push("/orders?status=OPEN");
    expect(a.navigations).toEqual(["/auth/login?returnTo=%2Forders%3Fstatus%3DOPEN"]);
  });

  it("cancels the navigation and reports when the session cannot be read", async () => {
    const reported: unknown[] = [];
    const s = createBrowserSession({
      read: () => Promise.reject(new Error("offline")),
      signOut: () => Promise.resolve({ loggedOut: true }),
      navigate: () => undefined,
      focusTarget: new Focus(),
    });
    const rs = router();
    installSessionGuard(rs, s, { onUnavailable: (error) => reported.push(error) });
    await rs.push("/orders");
    expect(rs.currentRoute.value.matched).toEqual([]);
    expect(reported).toHaveLength(1);
  });
});

describe("createApiRuntime", () => {
  it("reads the session through the generated auth client and logs out with a body-less POST", async () => {
    const fake = fakeFetch();
    const navigations: string[] = [];
    fake.answer({ status: 200, json: signedIn }, { status: 200, json: { loggedOut: true } });
    const runtime = createApiRuntime({ fetch: fake.fetch, navigate: (url) => navigations.push(url), focusTarget: new Focus() });
    expect(await runtime.session.start()).toEqual(signedIn);
    await runtime.session.logout();
    expect(fake.requests.map((r) => `${r.method} ${new URL(r.url).pathname}`)).toEqual(["GET /auth/session", "POST /auth/logout"]);
    expect(navigations).toEqual(["/signed-out"]);
  });

  it("rejects a session read that fails with the normalised error", async () => {
    const fake = fakeFetch();
    fake.answer({ status: 503, json: { error: "UNAVAILABLE", message: "down" } });
    const runtime = createApiRuntime({ fetch: fake.fetch, navigate: () => undefined, focusTarget: new Focus() });
    const failures: string[] = [];
    runtime.onFailure((f) => failures.push(f.kind));
    await expect(runtime.session.start()).rejects.toMatchObject({ status: 503, error: "UNAVAILABLE" });
    expect(failures).toEqual(["failed"]);
  });
});
