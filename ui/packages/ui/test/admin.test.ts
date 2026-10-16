// The admin skeleton's pure parts and the shell (docs/contracts/admin.md section 6): navigation from the
// server's sections and the session's permissions, routes that always carry a permission, breadcrumbs,
// the navigation loader's failed state.
import { hasPermission } from "@flowcatalyst/api-runtime";
import ui from "@nuxt/ui/vue-plugin";
import UApp from "@nuxt/ui/components/App.vue";
import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { adminBreadcrumbs, adminNavigation, adminRoutes, sectionsWithoutPage } from "../src/admin/navigation.ts";
import type { AdminNavigationView, AdminPage, AdminSectionView } from "../src/admin/types.ts";
import { useAdminNavigation } from "../src/composables/useAdminNavigation.ts";
import FwAdminShell from "../src/layouts/FwAdminShell.vue";

const stub = () => Promise.resolve({ render: () => h("p", "page") });
const page = (id: string, extra: Partial<AdminPage> = {}): AdminPage => ({
  id,
  title: id.toUpperCase(),
  icon: "i-lucide-box",
  permission: `acme:admin:${id}:view`,
  path: `/admin/${id}`,
  component: stub,
  ...extra,
});
const section = (id: string, extra: Partial<AdminSectionView> = {}): AdminSectionView => ({
  id,
  title: id,
  path: `/admin/${id}`,
  permission: `acme:admin:${id}:view`,
  ...extra,
});
const canOf = (...held: string[]) => (required: string) => hasPermission(held, required);

describe("adminNavigation", () => {
  const pages = [page("about", { permission: "acme:admin:console:view" }), page("users"), page("roles")];
  const sections = [section("about", { permission: "acme:admin:console:view" }), section("users"), section("roles")];

  it("lists, in the server's order, the pages whose section the server listed and the session may open", () => {
    const items = adminNavigation(pages, sections, canOf("acme:admin:*:*"));
    expect(items.map((item) => item.label)).toEqual(["ABOUT", "USERS", "ROLES"]);
    expect(items[1]).toMatchObject({ to: "/admin/users", icon: "i-lucide-box", permission: "acme:admin:users:view" });
  });

  it("leaves out a page the session lacks the permission for, even when the server listed it", () => {
    const items = adminNavigation(pages, sections, canOf("acme:admin:console:view", "acme:admin:users:view"));
    expect(items.map((item) => item.label)).toEqual(["ABOUT", "USERS"]);
  });

  it("leaves out a page the server did not list, even when the session holds its permission", () => {
    const items = adminNavigation(pages, [section("about", { permission: "acme:admin:console:view" })], canOf("acme:admin:*:*"));
    expect(items.map((item) => item.label)).toEqual(["ABOUT"]);
  });

  it("shows nothing before the navigation is loaded or after it failed", () => {
    expect(adminNavigation(pages, undefined, canOf("acme:admin:*:*"))).toEqual([]);
  });

  it("reports the sections the server lists that no page serves", () => {
    expect(sectionsWithoutPage(pages, [...sections, section("events")])).toEqual(["events"]);
  });
});

describe("adminRoutes", () => {
  it("builds a route per page carrying the permission, the title and the breadcrumb, and a landing redirect to about", () => {
    const routes = adminRoutes([page("users"), page("about", { permission: "acme:admin:console:view" })]);
    expect(routes[0]).toMatchObject({ path: "/admin", redirect: "/admin/about" });
    const users = routes.find((route) => route.path === "/admin/users");
    expect(users?.meta).toEqual({ title: "USERS", permission: "acme:admin:users:view", breadcrumb: "USERS", admin: true });
  });

  it("redirects the base to the first page when there is no about page", () => {
    expect(adminRoutes([page("users")])[0]).toMatchObject({ path: "/admin", redirect: "/admin/users" });
  });

  it("refuses a page without a four-segment permission: a route no one guards is a defect", () => {
    expect(() => adminRoutes([page("users", { permission: "" })])).toThrow(/four ':'-separated segments/);
    expect(() => adminRoutes([page("users", { permission: "acme:admin:view" })])).toThrow(/four ':'-separated segments/);
  });

  it("refuses a path outside the base, a bad id, and duplicates", () => {
    expect(() => adminRoutes([page("users", { path: "/orders" })])).toThrow(/must be \/admin or under it/);
    expect(() => adminRoutes([page("users", { path: "/administrators" })])).toThrow(/must be \/admin or under it/);
    expect(() => adminRoutes([page("Bad_Id")])).toThrow(/the id must match/);
    expect(() => adminRoutes([page("a"), page("a", { path: "/admin/other" })])).toThrow(/page id 'a' is used twice/);
    expect(() => adminRoutes([page("a"), page("b", { path: "/admin/a" })])).toThrow(/page path '\/admin\/a' is used twice/);
  });

  it("passes child routes (a record drawer) through", () => {
    const routes = adminRoutes([page("users", { children: [{ path: ":id", component: { render: () => h("p") } }] })]);
    expect(routes.find((route) => route.path === "/admin/users")?.children).toHaveLength(1);
  });
});

describe("adminBreadcrumbs", () => {
  it("is Admin, the section and the record; only the last is text", () => {
    const crumbs = adminBreadcrumbs([
      { path: "/admin/users", meta: { breadcrumb: "Users" } },
      { path: "/admin/users/:id", meta: { breadcrumb: "Ada" } },
    ]);
    expect(crumbs).toEqual([{ label: "Admin", to: "/admin" }, { label: "Users", to: "/admin/users" }, { label: "Ada" }]);
  });

  it("on the base alone there is one crumb that is a link", () => {
    expect(adminBreadcrumbs([{ path: "/admin", meta: {} }])).toEqual([{ label: "Admin", to: "/admin" }]);
  });
});

describe("useAdminNavigation", () => {
  it("loads once and exposes the sections", async () => {
    let calls = 0;
    const nav = useAdminNavigation(() => {
      calls++;
      return Promise.resolve({ sections: [section("users")] });
    });
    await flushPromises();
    expect(calls).toBe(1);
    expect(nav.sections.value?.map((s) => s.id)).toEqual(["users"]);
    expect(nav.failed.value).toBe(false);
    expect(nav.loading.value).toBe(false);
  });

  it("a failure is a state, never a throw, and reload recovers", async () => {
    let fail = true;
    const nav = useAdminNavigation(() => (fail ? Promise.reject(new Error("offline")) : Promise.resolve({ sections: [section("users")] })));
    await flushPromises();
    expect(nav.failed.value).toBe(true);
    expect(nav.sections.value).toBeUndefined();
    fail = false;
    await nav.reload();
    expect(nav.failed.value).toBe(false);
    expect(nav.sections.value).toHaveLength(1);
  });

  it("an older answer never overwrites a newer one", async () => {
    const resolvers: ((view: AdminNavigationView) => void)[] = [];
    const nav = useAdminNavigation(() => new Promise<AdminNavigationView>((resolve) => resolvers.push(resolve)));
    const second = nav.reload();
    resolvers[1]?.({ sections: [section("new")] });
    await second;
    resolvers[0]?.({ sections: [section("old")] });
    await flushPromises();
    expect(nav.sections.value?.map((s) => s.id)).toEqual(["new"]);
  });
});

describe("FwAdminShell", () => {
  async function mountShell(load: () => Promise<AdminNavigationView>, path = "/admin/users") {
    const pages = [page("about", { permission: "acme:admin:console:view" }), page("users")];
    const router = createRouter({ history: createMemoryHistory(), routes: [...adminRoutes(pages), { path: "/", component: stub }] });
    await router.push(path);
    const App = defineComponent({
      render: () => h(UApp, null, { default: () => h(FwAdminShell, { appName: "Acme", pages, load, backTo: "/", backLabel: "Back to Acme" }, { default: () => h("p", "body") }) }),
    });
    const wrapper = mount(App, { global: { plugins: [router, ui] }, attachTo: document.body });
    await flushPromises();
    return wrapper;
  }

  it("shows the Back link always, the pages without a session as nothing (no runtime: nothing is permitted), and breadcrumbs", async () => {
    const wrapper = await mountShell(() => Promise.resolve({ sections: [section("about", { permission: "acme:admin:console:view" }), section("users")] }));
    const nav = wrapper.get('nav[aria-label="Main"]');
    expect(nav.text()).toContain("Back to Acme");
    // Without an api runtime the session holds nothing: no admin page is shown, whatever the server listed.
    expect(nav.text()).not.toContain("USERS");
    expect(wrapper.get('[data-testid="fw-admin-breadcrumbs"]').text()).toContain("Admin");
    expect(wrapper.get('[data-testid="fw-admin-breadcrumbs"]').text()).toContain("USERS");
    // The current page is text marked aria-current, not a link.
    expect(wrapper.get('[data-testid="fw-admin-breadcrumbs"] [aria-current="page"]').text()).toBe("USERS");
    expect(wrapper.get('[data-testid="fw-admin-breadcrumbs"] [aria-current="page"]').element.closest("a")).toBeNull();
    wrapper.unmount();
  });

  it("a failed navigation load shows an inline error with Try again, not a blank page", async () => {
    let fail = true;
    const wrapper = await mountShell(() => (fail ? Promise.reject(new Error("503")) : Promise.resolve({ sections: [] })));
    expect(wrapper.get('[data-testid="fw-admin-nav-failed"]').text()).toContain("could not be loaded");
    fail = false;
    await wrapper.get('[data-testid="fw-admin-nav-retry"]').trigger("click");
    await flushPromises();
    expect(wrapper.find('[data-testid="fw-admin-nav-failed"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
