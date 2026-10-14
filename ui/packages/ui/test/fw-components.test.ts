// Props, events and slots of the Fw* components (docs/go/ui.md section 12, "component"). Behaviour
// that needs a real browser (focus, overlays, CSP, axe) is in the gallery's Playwright suite.
import ui from "@nuxt/ui/vue-plugin";
import { mount } from "@vue/test-utils";
import { describe, expect, it } from "vitest";
import { defineComponent, h, nextTick } from "vue";
import { createMemoryHistory, createRouter, RouterView } from "vue-router";
import UApp from "@nuxt/ui/components/App.vue";
import FwAnnouncer from "../src/components/FwAnnouncer.vue";
import FwCodeBlock from "../src/components/FwCodeBlock.vue";
import FwCodeChips from "../src/components/FwCodeChips.vue";
import FwCursorPager from "../src/components/FwCursorPager.vue";
import FwDetailField from "../src/components/FwDetailField.vue";
import FwEmptyState from "../src/components/FwEmptyState.vue";
import FwFormSection from "../src/components/FwFormSection.vue";
import FwJsonBlock from "../src/components/FwJsonBlock.vue";
import FwLocalTime from "../src/components/FwLocalTime.vue";
import FwPaginator from "../src/components/FwPaginator.vue";
import FwPickList from "../src/components/FwPickList.vue";
import FwRowActions from "../src/components/FwRowActions.vue";
import FwSpinner from "../src/components/FwSpinner.vue";
import FwStatusBadge from "../src/components/FwStatusBadge.vue";
import FwTableToolbar from "../src/components/FwTableToolbar.vue";
import FwTag from "../src/components/FwTag.vue";
import { announce } from "../src/composables/useAnnouncer.ts";
import { useDrawerRoute } from "../src/composables/useDrawerRoute.ts";
import { flowcatalystUi } from "../src/plugin.ts";
import { usePermissions } from "../src/composables/useSession.ts";
import type { ApiRuntime } from "@flowcatalyst/api-runtime";
import { createBrowserSession } from "@flowcatalyst/api-runtime";

const global = { plugins: [ui] };

describe("small built components", () => {
  it("FwSpinner: an image named Loading, or decorative", () => {
    const named = mount(FwSpinner, { global });
    expect(named.get("[role=img]").attributes("aria-label")).toBe("Loading");
    const decorative = mount(FwSpinner, { props: { decorative: true }, global });
    expect(decorative.find("[role=img][aria-label]").exists()).toBe(false);
    expect(decorative.html()).toContain('aria-hidden="true"');
  });

  it("FwDetailField: text, slot, or an em dash", () => {
    expect(mount(FwDetailField, { props: { label: "Customer", value: "Acme" } }).get("dd").text()).toBe("Acme");
    expect(mount(FwDetailField, { props: { label: "Lines", value: 0 } }).get("dd").text()).toBe("0");
    expect(mount(FwDetailField, { props: { label: "Ref" }, slots: { default: "<code>X-1</code>" } }).get("dd code").text()).toBe("X-1");
    expect(mount(FwDetailField, { props: { label: "Ref", value: "" } }).get("dd").text()).toBe("—");
    expect(mount(FwDetailField, { props: { label: "Ref" } }).get("dt").text()).toBe("Ref");
  });

  it("FwFormSection: heading level, description, danger colour, actions slot", () => {
    const wrapper = mount(FwFormSection, { props: { title: "Danger zone", description: "Careful", danger: true, level: 2 }, slots: { actions: "<button>Act</button>", default: "<p>Body</p>" } });
    expect(wrapper.get("h2").text()).toBe("Danger zone");
    expect(wrapper.get("h2").classes()).toContain("text-error");
    expect(wrapper.text()).toContain("Careful");
    expect(wrapper.get("button").text()).toBe("Act");
    expect(mount(FwFormSection, { props: { title: "T" } }).find("h3").exists()).toBe(true);
  });

  it("FwLocalTime: <time datetime> in the given zone, UTC in the title, the empty text otherwise", () => {
    const time = mount(FwLocalTime, { props: { at: "2026-10-06T08:30:00Z", locale: "en-GB", timeZone: "UTC" } });
    expect(time.get("time").attributes()).toMatchObject({ datetime: "2026-10-06T08:30:00Z", title: "2026-10-06 08:30:00 UTC" });
    expect(time.text()).toBe("6 Oct 2026, 08:30:00");
    expect(mount(FwLocalTime, { props: { at: "nonsense" } }).text()).toBe("-");
    expect(mount(FwLocalTime, { props: { empty: "never" } }).text()).toBe("never");
  });

  it("FwJsonBlock and FwCodeChips: text only, the code's text unchanged", () => {
    const json = mount(FwJsonBlock, { props: { value: { a: "<b>x</b>", n: [1] }, label: "Payload" } });
    expect(json.get("pre").text()).toBe('{\n  "a": "<b>x</b>",\n  "n": [\n    1\n  ]\n}');
    expect(json.get("pre").attributes()).toMatchObject({ tabindex: "0", "aria-label": "Payload" });
    expect(json.find("b").exists()).toBe(false);
    const chips = mount(FwCodeChips, { props: { code: "orders:sales:order:placed:v2" } });
    expect(chips.text().replace(/\s/g, "")).toBe("orders:sales:order:placed:v2");
  });

  it("FwCodeBlock: highlighted spans whose text is the code; a named, focusable region; copy button", () => {
    const code = '{ "a": 1, "b": "<script>" }';
    const block = mount(FwCodeBlock, { props: { code, label: "Body", language: "json" }, global });
    expect(block.get("pre").text()).toBe(code);
    expect(block.findAll("pre span").length).toBeGreaterThan(3);
    expect(block.find("script").exists()).toBe(false);
    expect(block.get("pre").attributes("aria-label")).toBe("Body");
    expect(block.get("[data-testid=fw-code-block-copy]").attributes("aria-label")).toBe("Copy Body");
    expect(mount(FwCodeBlock, { props: { code, label: "Body", copyable: false }, global }).find("button").exists()).toBe(false);
  });

  it("FwAnnouncer: a polite and an assertive region from the start; announce() fills one", async () => {
    const wrapper = mount(FwAnnouncer);
    expect(wrapper.get("[role=status]").attributes("aria-live")).toBe("polite");
    expect(wrapper.get("[role=alert]").attributes("aria-live")).toBe("assertive");
    announce("Saved.");
    announce("Failed.", true);
    await nextTick();
    await nextTick();
    expect(wrapper.get("[role=status]").text()).toBe("Saved.");
    expect(wrapper.get("[role=alert]").text()).toBe("Failed.");
  });
});

describe("tags and badges", () => {
  it("FwTag maps the kit's severities onto the colour aliases", () => {
    const colour = (severity: "success" | "info" | "warn" | "danger" | "secondary" | "primary"): string =>
      mount(FwTag, { props: { label: severity, severity }, global }).get("[data-testid=fw-tag]").classes().join(" ");
    expect(colour("success")).toContain("bg-success");
    expect(colour("warn")).toContain("bg-warning");
    expect(colour("danger")).toContain("bg-error");
    expect(colour("primary")).toContain("bg-primary");
    expect(colour("secondary")).not.toContain("bg-primary");
  });

  it("FwStatusBadge: the mapped severity and a sentence-case label; unmapped is secondary", () => {
    const badge = mount(FwStatusBadge, { props: { status: "IN_REVIEW", severities: { OPEN: "info" } }, global });
    expect(badge.text()).toBe("In review");
    expect(badge.get("[data-testid=fw-tag]").attributes("data-severity")).toBe("secondary");
    expect(mount(FwStatusBadge, { props: { status: "OPEN", severities: { OPEN: "info" }, label: "Open now" }, global }).text()).toBe("Open now");
  });
});

describe("list parts", () => {
  it("FwCursorPager: aria-disabled controls emit nothing; usable ones emit", async () => {
    const pager = mount(FwCursorPager, { props: { hasPrev: false, hasMore: true, summary: "Page 1" }, global });
    expect(pager.get("nav").attributes("aria-label")).toBe("Pagination");
    expect(pager.get("[data-testid=fw-pager-newest]").attributes("aria-disabled")).toBe("true");
    await pager.get("[data-testid=fw-pager-newest]").trigger("click");
    await pager.get("[data-testid=fw-pager-older]").trigger("click");
    expect(pager.emitted()).not.toHaveProperty("newest");
    expect(pager.emitted("older")).toHaveLength(1);
    await pager.setProps({ loading: true });
    await pager.get("[data-testid=fw-pager-older]").trigger("click");
    expect(pager.emitted("older")).toHaveLength(1);
  });

  it("FwPaginator: the report from page, size and total", () => {
    const report = (page: number, pageSize: number, total: number): string =>
      mount(FwPaginator, { props: { page, pageSize, total, noun: "orders" }, global }).get("[data-testid=fw-paginator-report]").text();
    expect(report(1, 50, 1234)).toBe("Showing 1 to 50 of 1234 orders");
    expect(report(25, 50, 1234)).toBe("Showing 1201 to 1234 of 1234 orders");
    expect(report(1, 50, 0)).toBe("Showing 0 to 0 of 0 orders");
  });

  it("FwTableToolbar: Clear all only while something is active; it emits clear; the size select only for firehose lists", async () => {
    const toolbar = mount(FwTableToolbar, { props: { search: "", activeFilterCount: 0 }, global });
    expect(toolbar.find("[data-testid=fw-toolbar-clear]").exists()).toBe(false);
    expect(toolbar.find("[data-testid=fw-toolbar-size]").exists()).toBe(false);
    expect(toolbar.find("[data-testid=fw-toolbar-filters]").exists()).toBe(false);
    await toolbar.setProps({ activeFilterCount: 2 });
    await toolbar.get("[data-testid=fw-toolbar-clear]").trigger("click");
    expect(toolbar.emitted("clear")).toHaveLength(1);
    await toolbar.get("[data-testid=fw-toolbar-refresh]").trigger("click");
    expect(toolbar.emitted("refresh")).toHaveLength(1);
    const withFilters = mount(FwTableToolbar, { props: { activeFilterCount: 2 }, slots: { filters: "<p>f</p>" }, global });
    expect(withFilters.get("[data-testid=fw-toolbar-filters]").attributes("aria-label")).toBe("Filters, 2 active");
  });

  it("FwPickList: ticking keeps the items' order; removing unpicks; the count is a live region", async () => {
    const items = [
      { value: "a", label: "Alpha" },
      { value: "b", label: "Bravo", code: "b:code" },
      { value: "c", label: "Charlie" },
    ];
    const pick = mount(FwPickList, { props: { items, modelValue: ["c"], availableTitle: "Available" }, global });
    const boxes = pick.findAll("[data-testid=fw-pick-available] button[role=checkbox]");
    expect(boxes).toHaveLength(3);
    await boxes[0]?.trigger("click");
    expect(pick.emitted("update:modelValue")?.at(-1)).toEqual([["a", "c"]]);
    await pick.setProps({ modelValue: ["a", "c"] });
    expect(pick.get("[data-testid=fw-pick-count]").attributes("aria-live")).toBe("polite");
    expect(pick.get("[data-testid=fw-pick-count]").text()).toBe("(2)");
    await pick.get("button[aria-label='Remove Alpha']").trigger("click");
    expect(pick.emitted("update:modelValue")?.at(-1)).toEqual([["c"]]);
  });

  it("FwRowActions: at most two inline; names include the row; a disabled action carries its reason", () => {
    // Tooltips need UApp's provider.
    const props = {
        rowLabel: "Orders pool",
        actions: [
          { label: "Edit", icon: "i-lucide-pencil" },
          { label: "Pause", icon: "i-lucide-pause", disabledReason: "Already paused" },
          { label: "Third", icon: "i-lucide-x" },
        ],
        menu: [{ label: "Delete…", destructive: true }],
    };
    const row = mount(defineComponent({ render: () => h(UApp, null, () => h(FwRowActions, props)) }), { global });
    const buttons = row.findAll("button");
    expect(buttons.map((b) => b.attributes("aria-label"))).toEqual(["Edit Orders pool", "Pause Orders pool", "More actions for Orders pool"]);
    const paused = buttons[1];
    expect(paused?.attributes("aria-disabled")).toBe("true");
    const reason = row.find(`#${paused?.attributes("aria-describedby") ?? "none"}`);
    expect(reason.text()).toBe("Already paused");
  });

  it("FwEmptyState: Clear filters only when filtered; a status region when announced", async () => {
    expect(mount(FwEmptyState, { props: { message: "None" }, global }).find("button").exists()).toBe(false);
    const filtered = mount(FwEmptyState, { props: { message: "None match", filtered: true, announce: true }, global });
    expect(filtered.get("[data-testid=fw-empty-state]").attributes("role")).toBe("status");
    await filtered.get("button").trigger("click");
    expect(filtered.emitted("clear")).toHaveLength(1);
  });
});

describe("plugin, permissions and the drawer route", () => {
  it("provides the runtime: usePermissions follows the session (the platform's wildcard rule)", async () => {
    const session = createBrowserSession({
      read: () =>
        Promise.resolve({
          authenticated: true,
          principal: { id: "p", kind: "USER" },
          client: "acme",
          clients: [],
          applications: [],
          roles: [],
          permissions: ["orders:*:read"],
          authTime: "2026-10-14T08:00:00Z",
          expiresAt: "2026-10-14T16:00:00Z",
          idleExpiresAt: "2026-10-14T08:30:00Z",
          csrf: { mode: "fetch-metadata", jsonBodies: true },
        }),
      signOut: () => Promise.resolve({ loggedOut: true }),
      focusTarget: { addEventListener: () => undefined, removeEventListener: () => undefined },
    });
    const api: ApiRuntime = { session, configure: () => undefined, onFailure: () => () => undefined };
    let can: ((p: string) => boolean) | undefined;
    mount(defineComponent({ setup: () => {
          const permissions = usePermissions();
          can = (required) => permissions.can(required);
          return () => null;
        } }), { global: { plugins: [[flowcatalystUi, { api }]] } });
    expect(can?.("orders:order:read")).toBe(false);
    await session.start();
    expect(can?.("orders:order:read")).toBe(true);
    expect(can?.("orders:order:manage")).toBe(false);
  });

  it("useDrawerRoute: the id from the route; close keeps the list's query minus drawer parameters; replaceToDetail replaces", async () => {
    let drawer: ReturnType<typeof useDrawerRoute> | undefined;
    const List = defineComponent({ render: () => h(RouterView) });
    const Drawer = defineComponent({
      setup() {
        drawer = useDrawerRoute({ listPath: "/orders" });
        return () => null;
      },
    });
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: "/orders", component: List, children: [{ path: ":id", component: Drawer }] }],
    });
    await router.push("/orders/ord_1?status=OPEN&edit=1");
    mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router, ui] } });
    await nextTick();
    expect(drawer?.id.value).toBe("ord_1");
    await drawer?.close();
    expect(router.currentRoute.value.fullPath).toBe("/orders?status=OPEN");
    await router.push("/orders/new?status=OPEN");
    await nextTick();
    await drawer?.replaceToDetail("ord_9");
    expect(router.currentRoute.value.fullPath).toBe("/orders/ord_9?status=OPEN");
  });
});
