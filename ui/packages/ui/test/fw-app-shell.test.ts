// FwAppShell's desktop sidebar toggle: the button's name and ARIA state, the persisted choice
// (localStorage, per viewer, tolerant of blocked storage), and focus staying on the button. The phone
// slideover and the rail's real geometry need a browser: the gallery's Playwright suite.
import ui from "@nuxt/ui/vue-plugin";
import { mount, flushPromises } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import UApp from "@nuxt/ui/components/App.vue";
import { defineComponent, h } from "vue";
import FwAppShell from "../src/layouts/FwAppShell.vue";

const KEY = "fc:shell-sidebar-collapsed";

async function mountShell(width?: "contained" | "full") {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/", component: { render: () => h("p", "page") } }] });
  await router.push("/");
  const App = defineComponent({
    render: () => h(UApp, null, { default: () => h(FwAppShell, { ...(width === undefined ? {} : { width }), appName: "Acme", navigation: [{ label: "Home", to: "/", icon: "i-lucide-house" }] }) }),
  });
  const wrapper = mount(App, { global: { plugins: [router, ui] }, attachTo: document.body });
  await flushPromises();
  return wrapper;
}

const toggleOf = (wrapper: Awaited<ReturnType<typeof mountShell>>) => wrapper.get('[data-testid="fw-sidebar-toggle"]');

describe("FwAppShell sidebar toggle", () => {
  beforeEach(() => {
    localStorage.clear();
  });
  afterEach(() => {
    document.body.innerHTML = "";
    vi.restoreAllMocks();
  });

  it("starts expanded: a named button with aria-expanded and aria-controls naming the sidebar", async () => {
    const wrapper = await mountShell();
    const toggle = toggleOf(wrapper);
    expect(toggle.attributes("aria-label")).toBe("Collapse sidebar");
    expect(toggle.attributes("aria-expanded")).toBe("true");
    const controlled = document.getElementById(toggle.attributes("aria-controls") ?? "");
    expect(controlled).not.toBeNull();
    expect(controlled?.getAttribute("data-collapsed")).toBe("false");
    wrapper.unmount();
  });

  it("click collapses, keeps focus on the button, flips the name and stores the choice; click again expands", async () => {
    const wrapper = await mountShell();
    const toggle = toggleOf(wrapper);
    document.querySelector<HTMLElement>('[data-testid="fw-sidebar-toggle"]')?.focus();
    await toggle.trigger("click");
    await flushPromises();
    const after = toggleOf(wrapper);
    expect(after.attributes("aria-label")).toBe("Expand sidebar");
    expect(after.attributes("aria-expanded")).toBe("false");
    expect(document.getElementById(after.attributes("aria-controls") ?? "")?.getAttribute("data-collapsed")).toBe("true");
    expect(after.element).toBe(toggle.element);
    expect(document.activeElement).toBe(toggle.element);
    expect(localStorage.getItem(KEY)).toBe("true");
    await after.trigger("click");
    await flushPromises();
    expect(toggleOf(wrapper).attributes("aria-expanded")).toBe("true");
    expect(localStorage.getItem(KEY)).toBe("false");
    wrapper.unmount();
  });

  it("a stored collapsed state is drawn on the first render", async () => {
    localStorage.setItem(KEY, "true");
    const wrapper = await mountShell();
    expect(toggleOf(wrapper).attributes("aria-expanded")).toBe("false");
    expect(document.getElementById(toggleOf(wrapper).attributes("aria-controls") ?? "")?.getAttribute("data-collapsed")).toBe("true");
    wrapper.unmount();
  });

  it("a garbled stored value is ignored", async () => {
    localStorage.setItem(KEY, '"yes"');
    const wrapper = await mountShell();
    expect(toggleOf(wrapper).attributes("aria-expanded")).toBe("true");
    wrapper.unmount();
  });

  it("blocked storage: the toggle still works for the page", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("blocked");
    });
    const wrapper = await mountShell();
    await toggleOf(wrapper).trigger("click");
    await flushPromises();
    expect(toggleOf(wrapper).attributes("aria-expanded")).toBe("false");
    wrapper.unmount();
  });

  it("does not write a cookie (Nuxt UI's own persistence is off)", async () => {
    document.cookie = "";
    const wrapper = await mountShell();
    await toggleOf(wrapper).trigger("click");
    await flushPromises();
    expect(document.cookie).not.toContain("dashboard");
    wrapper.unmount();
  });
});

describe("FwAppShell width", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("is a contained column by default and the whole panel when full", async () => {
    const contained = await mountShell();
    const main = document.querySelector("main");
    expect(main?.getAttribute("data-width")).toBe("contained");
    expect(main?.classList.contains("max-w-6xl")).toBe(true);
    contained.unmount();

    const full = await mountShell("full");
    const wide = document.querySelector("main");
    expect(wide?.getAttribute("data-width")).toBe("full");
    expect(wide?.classList.contains("max-w-6xl")).toBe(false);
    full.unmount();
  });
});
