// The platform config page (docs/contracts/platform-config.md section 5): a secret is never shown or
// pre-filled, reveal and manage follow the session's permissions, the revealed value is dropped when its
// dialog closes, a page without its operation says so, and one page mounted on a fake client.
import ui from "@nuxt/ui/vue-plugin";
import UApp from "@nuxt/ui/components/App.vue";
import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, h, nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it } from "vitest";
import { ADMIN_DATA_KEY, adminData, type AdminDataApi, type AdminPlatformConfigItemView } from "../src/admin/data.ts";
import AdminPlatformConfigPage from "../src/admin/pages/AdminPlatformConfigPage.vue";
import PlatformConfigDialog from "../src/admin/pages/PlatformConfigDialog.vue";
import PlatformConfigRevealDialog from "../src/admin/pages/PlatformConfigRevealDialog.vue";
import { API_RUNTIME_KEY } from "../src/composables/useSession.ts";

const PLAIN: AdminPlatformConfigItemView = { section: "ftp", property: "host", valueType: "PLAIN", value: "ftp.example.com", updatedAt: "2026-10-23T08:00:00Z" };
const SECRET: AdminPlatformConfigItemView = { section: "secrets", property: "password", clientId: "clt_0abc", valueType: "SECRET", value: "***", updatedAt: "2026-10-23T08:00:00Z" };
const PLAINTEXT = "s3cret-plaintext";

const PREFIX = "acme:admin:platform-config:";

function api(extra: AdminDataApi = {}): AdminDataApi {
  return { listAdminPlatformConfig: () => Promise.resolve({ data: { items: [PLAIN, SECRET] } }), ...extra };
}

async function mountPage(operations: AdminDataApi, held: readonly string[]): Promise<ReturnType<typeof mount>> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: "/admin/platform-config", component: defineComponent({ render: () => h("div") }) }],
  });
  await router.push("/admin/platform-config");
  await router.isReady();
  const wrapper = mount(defineComponent({ render: () => h(UApp, null, { default: () => h(AdminPlatformConfigPage) }) }), {
    attachTo: document.body,
    global: {
      plugins: [router, ui],
      provide: {
        [ADMIN_DATA_KEY]: adminData({ application: "acme", api: operations }),
        [API_RUNTIME_KEY]: { session: { can: (p: string) => held.includes(p.slice(PREFIX.length)) } },
      },
    },
  });
  await flushPromises();
  return wrapper;
}

function button(label: string): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(`[aria-label="${label}"]`);
}

async function settle(): Promise<void> {
  await flushPromises();
  await new Promise((resolve) => setTimeout(resolve, 20));
  await flushPromises();
}

afterEach(() => {
  document.body.innerHTML = "";
});

describe("the platform config page on a fake client", () => {
  it("lists a plain and a secret property; the secret shows a lock and bullets, never a value", async () => {
    const wrapper = await mountPage(api({ listAdminPlatformConfig: () => Promise.resolve({ data: { items: [PLAIN, { ...SECRET, value: PLAINTEXT }] } }) }), ["view"]);
    expect(wrapper.text()).toContain("ftp.example.com");
    expect(wrapper.text()).toContain("clt_0abc");
    const secret = wrapper.find('[data-testid="secret-value"]');
    expect(secret.exists()).toBe(true);
    expect(secret.text()).toBe("••••••••");
    // Even a server that sent plaintext in a list answer would not see it on the page.
    expect(wrapper.text()).not.toContain(PLAINTEXT);
    wrapper.unmount();
  });

  it("without manage there is no add, edit or delete; without reveal no reveal button", async () => {
    const wrapper = await mountPage(api(), ["view"]);
    expect(wrapper.find('[data-testid="add-property"]').exists()).toBe(false);
    expect(button("Edit secrets/password for clt_0abc")).toBeNull();
    expect(button("Reveal secrets/password for clt_0abc")).toBeNull();
    wrapper.unmount();
  });

  it("with manage and reveal the controls show", async () => {
    const wrapper = await mountPage(api(), ["view", "manage", "reveal"]);
    expect(wrapper.find('[data-testid="add-property"]').exists()).toBe(true);
    expect(button("Edit secrets/password for clt_0abc")).not.toBeNull();
    expect(button("Reveal secrets/password for clt_0abc")).not.toBeNull();
    // A plain property has nothing to reveal.
    expect(button("Reveal ftp/host")).toBeNull();
    wrapper.unmount();
  });

  it("reveal asks first, shows the value, and the value is gone once the dialog closes", async () => {
    let asked = 0;
    const wrapper = await mountPage(
      api({
        revealAdminPlatformConfig: (options) => {
          asked += 1;
          expect(options).toEqual({ path: { section: "secrets", property: "password" }, query: { clientId: "clt_0abc" } });
          return Promise.resolve({ data: { section: "secrets", property: "password", clientId: "clt_0abc", value: PLAINTEXT, updatedAt: "2026-10-23T08:00:00Z" } });
        },
      }),
      ["view", "reveal"],
    );
    button("Reveal secrets/password for clt_0abc")?.click();
    await settle();
    expect(asked).toBe(0); // the confirmation comes first
    expect(document.body.textContent).toContain("This is recorded");
    const confirm = Array.from(document.body.querySelectorAll("button")).find((b) => b.textContent.trim() === "Reveal");
    confirm?.click();
    await settle();
    expect(asked).toBe(1);
    const shown = document.body.querySelector<HTMLInputElement>('[data-testid="reveal-value"]');
    expect(shown?.value).toBe(PLAINTEXT);
    document.body.querySelector<HTMLElement>('[data-testid="reveal-close"]')?.click();
    await settle();
    expect(document.body.querySelector('[data-testid="reveal-value"]')).toBeNull();
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    wrapper.unmount();
  });

  it("a page whose operation was not passed shows an alert, not a blank list", async () => {
    const wrapper = await mountPage({}, ["view"]);
    expect(wrapper.find('[data-testid="platform-config-error"]').exists()).toBe(true);
    wrapper.unmount();
  });
});

describe("the add and edit dialog", () => {
  async function mountDialog(item: AdminPlatformConfigItemView | undefined, open: boolean): Promise<ReturnType<typeof mount>> {
    const wrapper = mount(
      defineComponent({ render: () => h(UApp, null, { default: () => h(PlatformConfigDialog, { open, item, submit: () => Promise.resolve(undefined) }) }) }),
      { attachTo: document.body, global: { plugins: [ui] } },
    );
    await settle();
    return wrapper;
  }

  it("editing a secret never pre-fills the value, which is a password field", async () => {
    const wrapper = await mountDialog({ ...SECRET, value: PLAINTEXT }, true);
    const value = document.body.querySelector<HTMLInputElement>('[data-testid="config-value"]');
    expect(value?.type).toBe("password");
    expect(value?.value).toBe("");
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    // The name of the property cannot change.
    expect(document.body.querySelector<HTMLInputElement>('[data-testid="config-property"]')?.disabled).toBe(true);
    wrapper.unmount();
  });

  it("editing a plain property shows its value as text", async () => {
    const wrapper = await mountDialog(PLAIN, true);
    const value = document.body.querySelector<HTMLInputElement>('[data-testid="config-value"]');
    expect(value?.type).toBe("text");
    expect(value?.value).toBe("ftp.example.com");
    wrapper.unmount();
  });

  it("a new value is required to change a secret", async () => {
    let submitted = 0;
    const wrapper = mount(
      defineComponent({
        render: () =>
          h(UApp, null, {
            default: () => h(PlatformConfigDialog, { open: true, item: SECRET, submit: () => { submitted += 1; return Promise.resolve(undefined); } }),
          }),
      }),
      { attachTo: document.body, global: { plugins: [ui] } },
    );
    await settle();
    const description = document.body.querySelector<HTMLInputElement>('[data-testid="config-description"]');
    if (description !== null) {
      description.value = "changed";
      description.dispatchEvent(new Event("input", { bubbles: true }));
    }
    await nextTick();
    document.body.querySelector<HTMLElement>('[data-testid="fw-form-submit"]')?.click();
    await settle();
    expect(submitted).toBe(0);
    expect(document.body.textContent).toContain("A value is required");
    wrapper.unmount();
  });
});

describe("the reveal dialog", () => {
  it("shows the value while there is one and nothing once it is dropped", async () => {
    const wrapper = mount(PlatformConfigRevealDialog, { props: { value: PLAINTEXT, label: "ftp/password" }, attachTo: document.body, global: { plugins: [ui] } });
    await settle();
    expect(document.body.querySelector<HTMLInputElement>('[data-testid="reveal-value"]')?.value).toBe(PLAINTEXT);
    await wrapper.setProps({ value: undefined });
    await settle();
    expect(document.body.innerHTML).not.toContain(PLAINTEXT);
    wrapper.unmount();
  });
});
