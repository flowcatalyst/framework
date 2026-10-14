import ui from "@nuxt/ui/vue-plugin";
import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it } from "vitest";
import { nextTick } from "vue";
import FwColorModeButton from "../src/components/FwColorModeButton.vue";
import FwPageHeader from "../src/components/FwPageHeader.vue";
import { COLOR_MODE_STORAGE_KEY } from "../src/csp.ts";

describe("FwPageHeader", () => {
  it("renders one h1, the subtitle and the actions slot", () => {
    const wrapper = mount(FwPageHeader, {
      props: { title: "Orders", subtitle: "Every open order" },
      slots: { actions: "<button type='button'>New order</button>" },
    });
    expect(wrapper.findAll("h1").map((h) => h.text())).toEqual(["Orders"]);
    expect(wrapper.find("p").text()).toBe("Every open order");
    expect(wrapper.find("button").text()).toBe("New order");
  });

  it("leaves the subtitle out when absent", () => {
    expect(mount(FwPageHeader, { props: { title: "Orders" } }).find("p").exists()).toBe(false);
  });
});

describe("FwColorModeButton", () => {
  afterEach(() => {
    localStorage.clear();
    document.documentElement.className = "";
  });

  it("cycles light -> dark -> system, stores the choice and sets class=dark on <html>", async () => {
    localStorage.setItem(COLOR_MODE_STORAGE_KEY, "light");
    const wrapper = mount(FwColorModeButton, { attachTo: document.body, global: { plugins: [ui] } });
    const button = wrapper.get("[data-testid=color-mode]");
    expect(button.attributes("aria-label")).toContain("Colour mode: light");

    await button.trigger("click");
    await nextTick();
    expect(localStorage.getItem(COLOR_MODE_STORAGE_KEY)).toBe("dark");
    expect(document.documentElement.classList.contains("dark")).toBe(true);

    await button.trigger("click");
    await nextTick();
    expect(localStorage.getItem(COLOR_MODE_STORAGE_KEY)).toBe("auto");
    wrapper.unmount();
  });

  it("never inserts a <style> element when switching (strict CSP)", async () => {
    const wrapper = mount(FwColorModeButton, { attachTo: document.body, global: { plugins: [ui] } });
    const before = document.head.querySelectorAll("style").length;
    const observed: string[] = [];
    const observer = new MutationObserver((records) => {
      for (const record of records) {
        for (const node of record.addedNodes) {
          observed.push(node.nodeName);
        }
      }
    });
    observer.observe(document.head, { childList: true });
    await wrapper.get("[data-testid=color-mode]").trigger("click");
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    observer.disconnect();
    expect(observed).not.toContain("STYLE");
    expect(document.head.querySelectorAll("style").length).toBe(before);
    wrapper.unmount();
  });
});
