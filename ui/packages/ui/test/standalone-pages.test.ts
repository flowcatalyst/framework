// The layout and pages outside the shell (docs/ui/u3.md G10, G11, G13): one h1, the sentence per
// failure code of the sessions contract (section 4.2), Try again / Sign in again going through the
// session, and the unavailable page reloading in full. Real geometry and axe: the gallery's Playwright suite.
import ui from "@nuxt/ui/vue-plugin";
import { flushPromises, mount } from "@vue/test-utils";
import { describe, expect, it, vi } from "vitest";
import { createMemoryHistory, createRouter } from "vue-router";
import { API_RUNTIME_KEY } from "../src/composables/useSession.ts";
import FwSignedOutPage from "../src/pages/FwSignedOutPage.vue";
import FwSignInFailedPage from "../src/pages/FwSignInFailedPage.vue";
import FwUnavailablePage from "../src/pages/FwUnavailablePage.vue";
import { SIGN_IN_FAILURE_MESSAGES, signInFailureMessage } from "../src/lib/signInFailure.ts";
import { h, type Component } from "vue";

// The eight codes a failure handler receives (sessions contract section 4.2 and 4.1.1).
const CODES = ["INVALID_CALLBACK", "LOGIN_NOT_STARTED_HERE", "INVALID_STATE", "LOGIN_REFUSED", "LOGIN_FAILED", "INVALID_ID_TOKEN", "PLATFORM_UNAVAILABLE", "RATE_LIMITED"];

async function mountAt(component: Component, url: string, props: Record<string, unknown>, signIn: (returnTo?: string) => void = () => undefined) {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/:rest(.*)*", component: { render: () => h("p") } }] });
  await router.push(url);
  const wrapper = mount(component, { props, global: { plugins: [router, ui], provide: { [API_RUNTIME_KEY]: { session: { signIn } } } } });
  await flushPromises();
  return wrapper;
}

describe("sign-in failure messages", () => {
  it("has a sentence for every code of the contract, none for anything else", () => {
    expect(Object.keys(SIGN_IN_FAILURE_MESSAGES).toSorted()).toEqual([...CODES].toSorted());
    for (const code of CODES) {
      expect(signInFailureMessage(code)).not.toBe("The sign-in did not complete. Try again.");
    }
    expect(signInFailureMessage("")).toBe("The sign-in did not complete. Try again.");
    expect(signInFailureMessage("<script>")).toBe("The sign-in did not complete. Try again.");
  });
});

describe("FwSignInFailedPage", () => {
  it("shows the sentence and the code, one h1, and Try again signs in to returnTo", async () => {
    const signIn = vi.fn();
    const wrapper = await mountAt(FwSignInFailedPage, "/sign-in-failed?error=LOGIN_REFUSED", { appName: "Orders", returnTo: "/orders" }, signIn);
    expect(wrapper.findAll("h1")).toHaveLength(1);
    expect(wrapper.get("h1").text()).toBe("Sign-in failed");
    expect(wrapper.text()).toContain(SIGN_IN_FAILURE_MESSAGES["LOGIN_REFUSED"]);
    expect(wrapper.get('[data-testid="fw-sign-in-failed-code"]').text()).toBe("LOGIN_REFUSED");
    expect(wrapper.get('[data-testid="fw-standalone-app"]').text()).toBe("Orders");
    await wrapper.get('[data-testid="fw-sign-in-retry"]').trigger("click");
    expect(signIn).toHaveBeenCalledWith("/orders");
  });

  it("an unknown or absent code is the general sentence and an unknown code is shown as text, never as markup", async () => {
    const none = await mountAt(FwSignInFailedPage, "/sign-in-failed", {});
    expect(none.text()).toContain("The sign-in did not complete. Try again.");
    expect(none.find('[data-testid="fw-sign-in-failed-code"]').exists()).toBe(false);
    const odd = await mountAt(FwSignInFailedPage, "/sign-in-failed?error=%3Cb%3Ex%3C%2Fb%3E", {});
    expect(odd.get('[data-testid="fw-sign-in-failed-code"]').text()).toBe("<b>x</b>");
    expect(odd.find("code b").exists()).toBe(false);
  });
});

describe("FwSignedOutPage", () => {
  it("names the application and signs in again to returnTo", async () => {
    const signIn = vi.fn();
    const wrapper = await mountAt(FwSignedOutPage, "/signed-out", { appName: "Orders", returnTo: "/orders" }, signIn);
    expect(wrapper.get("h1").text()).toBe("Signed out");
    expect(wrapper.text()).toContain("You have been signed out of Orders.");
    await wrapper.get('[data-testid="fw-sign-in-again"]').trigger("click");
    expect(signIn).toHaveBeenCalledWith("/orders");
  });
});

describe("FwUnavailablePage", () => {
  it("says the application is unavailable and Try again loads retryTo in full", async () => {
    const assign = vi.fn();
    const original = window.location;
    Object.defineProperty(window, "location", { configurable: true, value: { assign } });
    try {
      const wrapper = await mountAt(FwUnavailablePage, "/unavailable", { appName: "Orders", retryTo: "/orders" });
      expect(wrapper.get("h1").text()).toBe("Orders is unavailable");
      await wrapper.get('[data-testid="fw-unavailable-retry"]').trigger("click");
      expect(assign).toHaveBeenCalledWith("/orders");
    } finally {
      Object.defineProperty(window, "location", { configurable: true, value: original });
    }
  });
});
