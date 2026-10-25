// The platform config page (docs/contracts/platform-config.md section 5): a secret is never shown or
// pre-filled, reveal and manage follow the session's permissions, the revealed value is dropped when its
// dialog closes, a page without its operation says so, and one page mounted on a fake client. Clients
// follow the all-clients permission (contract section 4, Validation), the platform's secret-value
// refusals land on the value field, and the property rule is the secret name rule.
import ui from "@nuxt/ui/vue-plugin";
import UApp from "@nuxt/ui/components/App.vue";
import { flushPromises, mount } from "@vue/test-utils";
import { defineComponent, h, nextTick } from "vue";
import { createMemoryHistory, createRouter } from "vue-router";
import { afterEach, describe, expect, it } from "vitest";
import { ADMIN_DATA_KEY, adminData, platformConfigFormSchema, type AdminDataApi, type AdminPlatformConfigItemView } from "../src/admin/data.ts";
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

/** Mounts the page for a session holding `held` (actions of the section) whose context client is `client`. */
async function mountPage(operations: AdminDataApi, held: readonly string[], client = "_platform"): Promise<ReturnType<typeof mount>> {
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
        [API_RUNTIME_KEY]: {
          session: { can: (p: string) => p.startsWith(PREFIX) && held.includes(p.slice(PREFIX.length)), user: { value: { client } } },
        },
      },
    },
  });
  await flushPromises();
  return wrapper;
}

function button(label: string): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(`[aria-label="${label}"]`);
}

function byTestId(id: string): HTMLElement | null {
  return document.body.querySelector<HTMLElement>(`[data-testid="${id}"]`);
}

function inputByTestId(id: string): HTMLInputElement | null {
  return document.body.querySelector<HTMLInputElement>(`[data-testid="${id}"]`);
}

function type(id: string, value: string): void {
  const input = inputByTestId(id);
  if (input === null) {
    throw new Error(`no field ${id}`);
  }
  input.value = value;
  input.dispatchEvent(new Event("input", { bubbles: true }));
}

function clickButton(text: string): void {
  Array.from(document.body.querySelectorAll("button"))
    .find((b) => b.textContent.trim() === text)
    ?.click();
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

describe("clients and the all-clients permission", () => {
  it("without all-clients the page explains the confinement, shows the own client by name and sends its identifier", async () => {
    let sent: unknown;
    const wrapper = await mountPage(
      api({
        revealAdminPlatformConfig: (options) => {
          sent = options;
          return Promise.resolve({ data: { section: "secrets", property: "password", clientId: "clt_0abc", value: PLAINTEXT, updatedAt: "2026-10-23T08:00:00Z" } });
        },
      }),
      ["view", "reveal"],
      "acme",
    );
    const note = wrapper.find('[data-testid="platform-config-confinement"]');
    expect(note.exists()).toBe(true);
    expect(note.text()).toContain("acme");
    expect(note.text()).toContain("acme:admin:platform-config:all-clients");
    expect(wrapper.text()).toContain("acme (clt_0abc)");
    button("Reveal secrets/password for acme")?.click();
    await settle();
    clickButton("Reveal");
    await settle();
    expect(sent).toEqual({ path: { section: "secrets", property: "password" }, query: { clientId: "acme" } });
    wrapper.unmount();
  });

  it("a session for no particular client is told it manages global values only", async () => {
    const wrapper = await mountPage(api(), ["view", "manage"]);
    expect(wrapper.find('[data-testid="platform-config-confinement"]').text()).toContain("global values only");
    byTestId("add-property")?.click();
    await settle();
    expect(byTestId("config-client")).toBeNull();
    expect(byTestId("config-scope")).toBeNull();
    wrapper.unmount();
  });

  it("with all-clients there is no confinement note, rows keep their stored id and the client is a free field", async () => {
    let sent: unknown;
    const wrapper = await mountPage(
      api({
        deleteAdminPlatformConfig: (options) => {
          sent = options;
          return Promise.resolve({});
        },
      }),
      ["view", "manage", "all-clients"],
      "acme",
    );
    expect(wrapper.find('[data-testid="platform-config-confinement"]').exists()).toBe(false);
    button("Delete secrets/password for clt_0abc")?.click();
    await settle();
    clickButton("Delete");
    await settle();
    expect(sent).toEqual({ path: { section: "secrets", property: "password" }, query: { clientId: "clt_0abc" } });
    byTestId("add-property")?.click();
    await settle();
    expect(byTestId("config-client")).not.toBeNull();
    expect(byTestId("config-scope")).toBeNull();
    wrapper.unmount();
  });

  it("without all-clients the add dialog offers global or the own client, and the own client is sent by identifier", async () => {
    const sent: unknown[] = [];
    const wrapper = await mountPage(
      api({
        setAdminPlatformConfig: (options) => {
          sent.push(options);
          return Promise.resolve({ data: { section: "ftp", property: "user", clientId: "clt_0abc", valueType: "PLAIN", value: "bob", updatedAt: "2026-10-25T08:00:00Z" } });
        },
      }),
      ["view", "manage"],
      "acme",
    );
    byTestId("add-property")?.click();
    await settle();
    expect(byTestId("config-client")).toBeNull();
    const own = Array.from(document.body.querySelectorAll<HTMLElement>('[role="radio"]')).find((r) => r.id.length > 0 && (document.body.querySelector(`label[for="${r.id}"]`)?.textContent.includes("acme") ?? false));
    expect(own).toBeDefined();
    own?.click();
    type("config-section", "ftp");
    type("config-property", "user");
    type("config-value", "bob");
    await settle();
    byTestId("fw-form-submit")?.click();
    await settle();
    expect(sent).toEqual([{ path: { section: "ftp", property: "user" }, query: { clientId: "acme" }, body: { value: "bob", valueType: "PLAIN" } }]);
    wrapper.unmount();
  });

  it("a 403 CLIENT_FORBIDDEN says why, with the permission that would allow it", async () => {
    const wrapper = await mountPage(
      api({
        revealAdminPlatformConfig: () =>
          Promise.resolve({
            error: { error: "CLIENT_FORBIDDEN", message: "you may not manage the configuration of that client" },
            response: new Response(null, { status: 403 }),
          }),
      }),
      ["view", "reveal"],
      "acme",
    );
    button("Reveal secrets/password for acme")?.click();
    await settle();
    clickButton("Reveal");
    await settle();
    expect(document.body.textContent).toContain("You may not act for that client");
    expect(byTestId("reveal-value")).toBeNull();
    wrapper.unmount();
  });
});

describe("secret values", () => {
  it("the platform's SECRET_VALUE_MASKED is shown on the value field and the dialog stays open", async () => {
    const wrapper = await mountPage(
      api({
        setAdminPlatformConfig: () =>
          Promise.resolve({
            error: { error: "SECRET_VALUE_MASKED", message: "a secret's value cannot be the mask" },
            response: new Response(null, { status: 400 }),
          }),
      }),
      ["view", "manage", "all-clients"],
    );
    byTestId("add-property")?.click();
    await settle();
    type("config-section", "secrets");
    type("config-property", "password");
    type("config-value", "x");
    await settle();
    byTestId("fw-form-submit")?.click();
    await settle();
    // The message is the value field's own error (aria-describedby), not the form's error region.
    const value = inputByTestId("config-value");
    expect(value?.getAttribute("aria-invalid")).toBe("true");
    const describedBy = value?.getAttribute("aria-describedby") ?? "";
    expect(document.getElementById(describedBy)?.textContent).toContain("is how the platform hides a secret");
    expect(byTestId("config-value")).not.toBeNull();
    wrapper.unmount();
  });

  it("the form refuses *** as a secret's value, so it is never sent", async () => {
    let submitted = 0;
    const wrapper = mount(
      defineComponent({
        render: () =>
          h(UApp, null, {
            default: () => h(PlatformConfigDialog, { open: true, item: SECRET, allClients: true, submit: () => { submitted += 1; return Promise.resolve(undefined); } }),
          }),
      }),
      { attachTo: document.body, global: { plugins: [ui] } },
    );
    await settle();
    type("config-value", "***");
    await nextTick();
    byTestId("fw-form-submit")?.click();
    await settle();
    expect(submitted).toBe(0);
    expect(document.body.textContent).toContain("is how the platform hides a secret");
    wrapper.unmount();
  });

  it("the schema: *** is refused for a secret, allowed as a plain value", () => {
    const base = { section: "secrets", property: "password", clientId: "", description: "" };
    expect(platformConfigFormSchema.safeParse({ ...base, valueType: "SECRET", value: "***" }).success).toBe(false);
    expect(platformConfigFormSchema.safeParse({ ...base, valueType: "SECRET", value: " *** " }).success).toBe(false);
    expect(platformConfigFormSchema.safeParse({ ...base, valueType: "PLAIN", value: "***" }).success).toBe(true);
    expect(platformConfigFormSchema.safeParse({ ...base, valueType: "SECRET", value: " " }).success).toBe(false);
  });
});

describe("the property name rule is the secret name rule (SecretRef.isValidName)", () => {
  const valid = (property: string): boolean =>
    platformConfigFormSchema.safeParse({ section: "secrets", property, clientId: "", valueType: "PLAIN", value: "v", description: "" }).success;

  it("accepts what the platform secrets driver can read", () => {
    expect(valid("password")).toBe(true);
    expect(valid("1abc")).toBe(true);
    expect(valid("ftp-2-host")).toBe(true);
    expect(valid("a".repeat(100))).toBe(true);
  });

  it("refuses what no secret reference allows", () => {
    expect(valid("")).toBe(false);
    expect(valid("a-")).toBe(false);
    expect(valid("-a")).toBe(false);
    expect(valid("a--b")).toBe(false);
    expect(valid("Abc")).toBe(false);
    expect(valid("a_b")).toBe(false);
    expect(valid("a".repeat(101))).toBe(false);
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
