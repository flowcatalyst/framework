// The shared admin data pages (docs/contracts/admin.md section 13): the standard page list, replacing a
// page, the port (a missing call is a named wiring error) and one page mounted on a fake client.
import ui from "@nuxt/ui/vue-plugin";
import UApp from "@nuxt/ui/components/App.vue";
import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, h } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { describe, expect, it } from "vitest";
import { adminRoutes } from "../src/admin/navigation.ts";
import type { AdminPage } from "../src/admin/types.ts";
import { ADMIN_DATA_KEY, adminData, adminPermissions, requireCall, type AdminDataApi } from "../src/admin/data.ts";
import { replaceAdminPage, standardAdminPages, STANDARD_ADMIN_PAGE_IDS } from "../src/admin/standard-pages.ts";
import AdminEventTypesPage from "../src/admin/pages/AdminEventTypesPage.vue";

describe("standardAdminPages", () => {
  it("lists the six standard pages in navigation order with the server's permission names", () => {
    const pages = standardAdminPages("acme");
    expect(pages.map((p) => p.id)).toEqual([...STANDARD_ADMIN_PAGE_IDS]);
    expect(pages.map((p) => p.permission)).toEqual([
      "acme:admin:console:view",
      "acme:admin:event-types:view",
      "acme:admin:roles:view",
      "acme:admin:users:view",
      "acme:admin:activity:view",
      "acme:admin:config:view",
    ]);
    // Every page passes the shell's own route rules (a permission, a path under /admin, unique ids).
    expect(() => adminRoutes(pages)).not.toThrow();
  });

  it("every drawer route carries its page's permission", () => {
    for (const page of standardAdminPages("acme")) {
      for (const child of page.children ?? []) {
        expect(child.meta?.permission, `${page.id} ${child.path}`).toBe(page.permission);
        expect(child.meta?.admin).toBe(true);
      }
    }
  });

  it("an application mounts a subset, and replaces a page by id", () => {
    const subset = standardAdminPages("acme", ["about", "config"]);
    expect(subset.map((p) => p.id)).toEqual(["about", "config"]);
    const config = subset[1];
    if (config === undefined) {
      throw new Error("no config page");
    }
    const own: AdminPage = { ...config, title: "Settings" };
    const replaced = replaceAdminPage(subset, own);
    expect(replaced.map((p) => p.title)).toEqual(["About", "Settings"]);
    expect(subset[1]?.title).toBe("Configuration"); // the list is not changed
    const added = replaceAdminPage(subset, { ...own, id: "extra", path: "/admin/extra" });
    expect(added.map((p) => p.id)).toEqual(["about", "config", "extra"]);
  });

  it("the permission names follow the admin contract", () => {
    expect(adminPermissions("orders-demo")).toEqual({
      console: "orders-demo:admin:console:view",
      eventTypesView: "orders-demo:admin:event-types:view",
      rolesView: "orders-demo:admin:roles:view",
      usersView: "orders-demo:admin:users:view",
      usersInvite: "orders-demo:admin:users:invite",
      usersResend: "orders-demo:admin:users:resend",
      usersRevoke: "orders-demo:admin:users:revoke",
      activityView: "orders-demo:admin:activity:view",
      configView: "orders-demo:admin:config:view",
    });
  });
});

describe("the port", () => {
  it("a call the application did not pass is a named wiring error", () => {
    const data = adminData({ application: "acme", api: {} });
    expect(() => requireCall(data, "listAdminRoles")).toThrow(/listAdminRoles/);
  });
});

async function mountPage(api: AdminDataApi): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/admin/event-types", component: defineComponent({ render: () => h("div") }) }],
  });
  await router.push("/admin/event-types");
  await router.isReady();
  const wrapper = mount(
    defineComponent({ render: () => h(UApp, null, { default: () => h(AdminEventTypesPage) }) }),
    {
      attachTo: document.body,
      global: { plugins: [router, ui], provide: { [ADMIN_DATA_KEY]: adminData({ application: "acme", api, pageSize: 5 }) } },
    },
  );
  await flushPromises();
  return wrapper;
}

describe("a shared page on a fake client", () => {
  it("event types: asks with the configured page size, lists what came back, and carries the status filter", async () => {
    const asked: unknown[] = [];
    const wrapper = await mountPage({
      listAdminEventTypes: (options) => {
        asked.push(options);
        return Promise.resolve({
          data: {
            items: [
              { id: "evt_1", code: "acme:sales:order:placed", name: "Order placed", subdomain: "sales", aggregate: "order", event: "placed", status: "CURRENT", versionCount: 2 },
            ],
          },
        });
      },
    });
    expect(asked).toEqual([{ query: { limit: 5 } }]);
    expect(wrapper.text()).toContain("Order placed");
    wrapper.unmount();
  });

  it("a missing call shows a message on the page, not a blank list", async () => {
    const wrapper = await mountPage({});
    expect(wrapper.find('[data-testid="platform-error"]').exists()).toBe(true);
    wrapper.unmount();
  });
});
