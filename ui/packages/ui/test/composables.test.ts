// The list, drawer and form composables the platform SPA proved, ported to strict TypeScript.
import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h, nextTick, reactive } from "vue";
import { createMemoryHistory, createRouter, RouterView, type Router } from "vue-router";
import {
  arrayFilter,
  booleanFilter,
  choiceFilter,
  numberFilter,
  stringFilter,
  useListState,
  type ListState,
} from "../src/composables/useListState.ts";
import { useCursorPagination, type CursorPage } from "../src/composables/useCursorPagination.ts";
import { useDirtyForm } from "../src/composables/useDirtyForm.ts";
import { useLocalState } from "../src/composables/useLocalState.ts";
import { tokenize } from "../src/lib/highlight.ts";
import { localText, utcText } from "../src/lib/time.ts";

async function withList<F>(url: string, setup: (list: ListState) => F, onChange?: () => void): Promise<{ router: Router; list: ListState; f: F }> {
  let captured: { list: ListState; f: F } | undefined;
  const Page = defineComponent({
    setup() {
      const list = useListState({ pageSize: 50, sort: { field: "createdAt", order: "desc" } }, onChange);
      captured = { list, f: setup(list) };
      return () => null;
    },
  });
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: "/orders", component: Page }] });
  await router.push(url);
  await router.isReady();
  mount(defineComponent({ render: () => h(RouterView) }), { global: { plugins: [router] } });
  await nextTick();
  if (captured === undefined) {
    throw new Error("list not created");
  }
  return { router, ...captured };
}

describe("useListState", () => {
  it("reads typed filters, page, size and sort from the URL", async () => {
    const { list, f } = await withList("/orders?q=acme&status=OPEN&status=PLACED&active=false&min=5&kind=B&kind2=x&page=3&size=100&sort=customer&order=asc", (l) => ({
      q: l.filter(stringFilter("q")),
      status: l.filter(arrayFilter("status")),
      active: l.filter(booleanFilter("active")),
      min: l.filter(numberFilter("min")),
      kind: l.filter(choiceFilter("kind", ["A", "B"] as const)),
    }));
    expect([f.q.value, f.status.value, f.active.value, f.min.value, f.kind.value]).toEqual(["acme", ["OPEN", "PLACED"], false, 5, "B"]);
    expect([list.page.value, list.pageSize.value, list.sortField.value, list.sortOrder.value]).toEqual([3, 100, "customer", "asc"]);
    expect(list.activeFilterCount.value).toBe(5);
    expect(list.sorting.value).toEqual([{ id: "customer", desc: false }]);
  });

  it("ignores unreadable values (an unknown choice, a non-number, a bad page)", async () => {
    const { list, f } = await withList("/orders?kind=Z&min=abc&page=-2", (l) => ({
      kind: l.filter(choiceFilter("kind", ["A", "B"] as const)),
      min: l.filter(numberFilter("min")),
    }));
    expect([f.kind.value, f.min.value, list.page.value, list.hasActiveFilters.value]).toEqual([null, null, 1, false]);
  });

  it("a filter change resets the page, replaces the URL (keeping foreign parameters) and reloads once", async () => {
    const onChange = vi.fn();
    const { router, list, f: status } = await withList("/orders?page=4&tab=lines", (l) => l.filter(arrayFilter("status")), onChange);
    const before = router.currentRoute.value.fullPath;
    status.value = ["OPEN"];
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(list.page.value).toBe(1);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(router.currentRoute.value.query).toEqual({ tab: "lines", status: ["OPEN"] });
    expect(before).toContain("page=4");
  });

  it("debounces a search filter", async () => {
    vi.useFakeTimers();
    const onChange = vi.fn();
    const { f: q } = await withList("/orders", (l) => l.filter(stringFilter("q", { debounce: 300 })), onChange);
    q.value = "a";
    q.value = "ac";
    q.value = "acme";
    expect(onChange).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(300);
    expect(onChange).toHaveBeenCalledTimes(1);
    vi.useRealTimers();
  });

  it("clearFilters resets every filter and the page with one reload; sorting maps to TanStack's shape", async () => {
    const onChange = vi.fn();
    const { router, list, f } = await withList("/orders?q=x&status=OPEN&page=2", (l) => ({ q: l.filter(stringFilter("q")), status: l.filter(arrayFilter("status")) }), onChange);
    list.clearFilters();
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect([f.q.value, f.status.value, list.page.value]).toEqual(["", [], 1]);
    expect(onChange).toHaveBeenCalledTimes(1);
    expect(router.currentRoute.value.query).toEqual({});

    list.sorting.value = [{ id: "total", desc: false }];
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(router.currentRoute.value.query).toEqual({ sort: "total", order: "asc" });
    list.sorting.value = [];
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(router.currentRoute.value.query).toEqual({});
  });
});

describe("useCursorPagination", () => {
  const source = Array.from({ length: 7 }, (_, i) => i);
  function pages(delay: (cursor: string | undefined) => number = () => 0): (cursor: string | undefined) => Promise<CursorPage<number>> {
    return (cursor) =>
      new Promise((resolve) => {
        const start = cursor === undefined ? 0 : Number(cursor);
        const next = start + 3 < source.length ? String(start + 3) : undefined;
        setTimeout(() => {
          resolve({ items: source.slice(start, start + 3), ...(next === undefined ? {} : { nextCursor: next }) });
        }, delay(cursor));
      });
  }

  it("walks forward with nextCursor and back with the in-session stack; hasMore from nextCursor", async () => {
    const p = useCursorPagination({ fetchPage: pages() });
    await p.loadFirst();
    expect([p.items.value, p.hasMore.value, p.hasPrev.value, p.page.value]).toEqual([[0, 1, 2], true, false, 1]);
    await p.loadNext();
    await p.loadNext();
    expect([p.items.value, p.hasMore.value, p.page.value]).toEqual([[6], false, 3]);
    await p.loadNext();
    expect(p.page.value).toBe(3);
    await p.loadPrev();
    expect([p.items.value, p.page.value, p.hasPrev.value]).toEqual([[3, 4, 5], 2, true]);
    await p.refresh();
    expect(p.items.value).toEqual([3, 4, 5]);
    await p.reset();
    expect([p.items.value, p.page.value]).toEqual([[0, 1, 2], 1]);
  });

  it("a slower, older answer never overwrites a newer one; a failure keeps the items and reports", async () => {
    let call = 0;
    const p = useCursorPagination<string>({
      fetchPage: () => {
        call += 1;
        const mine = call;
        return new Promise((resolve) => {
          setTimeout(() => {
            resolve({ items: [`answer ${String(mine)}`] });
          }, mine === 1 ? 40 : 0);
        });
      },
    });
    const slow = p.loadFirst();
    await p.reset();
    await slow;
    expect(p.items.value).toEqual(["answer 2"]);
    expect(p.loading.value).toBe(false);

    const errors: unknown[] = [];
    const q = useCursorPagination({ fetchPage: () => Promise.reject(new Error("down")), onError: (e) => errors.push(e) });
    await q.loadFirst();
    expect(errors).toHaveLength(1);
    expect([q.items.value, q.loading.value]).toEqual([[], false]);
  });
});

describe("useDirtyForm", () => {
  it("compares with the snapshot: typing a value back is clean again", () => {
    const state = reactive({ name: "Ada", tags: ["a"] });
    const form = useDirtyForm(() => state);
    expect(form.dirty.value).toBe(false);
    form.markClean();
    state.name = "Ada K";
    expect(form.dirty.value).toBe(true);
    state.name = "Ada";
    expect(form.dirty.value).toBe(false);
    state.tags = ["a", "b"];
    expect(form.dirty.value).toBe(true);
    form.markClean();
    expect(form.dirty.value).toBe(false);
    form.reset();
    state.name = "x";
    expect(form.dirty.value).toBe(false);
  });
});

describe("useLocalState", () => {
  afterEach(() => {
    localStorage.clear();
  });

  it("reads an accepted value, ignores a rejected or broken one, writes on change", async () => {
    const isNumber = (value: unknown): value is number => typeof value === "number";
    localStorage.setItem("fc:width", "320");
    expect(useLocalState("width", 200, isNumber).value).toBe(320);
    localStorage.setItem("fc:width", '"wide"');
    expect(useLocalState("width", 200, isNumber).value).toBe(200);
    localStorage.setItem("fc:width", "{broken");
    const state = useLocalState("width", 200, isNumber);
    expect(state.value).toBe(200);
    state.value = 280;
    await nextTick();
    expect(localStorage.getItem("fc:width")).toBe("280");
  });
});

describe("highlighting and time", () => {
  it("tokenizes without losing a character, with the kit's kinds", () => {
    const json = '{ "sku": "BOLT", "qty": 2, "ok": true, "x": null }';
    const tokens = tokenize(json, "json");
    expect(tokens.map((t) => t.text).join("")).toBe(json);
    expect(tokens.filter((t) => t.kind !== "plain").map((t) => `${t.kind}:${t.text}`)).toEqual([
      'key:"sku"', 'str:"BOLT"', 'key:"qty"', "num:2", 'key:"ok"', "kw:true", 'key:"x"', "kw:null",
    ]);
    const ts = "// add\nconst order: Order = await getOrder(`x`);";
    const tsTokens = tokenize(ts, "typescript");
    expect(tsTokens.map((t) => t.text).join("")).toBe(ts);
    expect(tsTokens.filter((t) => t.kind !== "plain").map((t) => `${t.kind}:${t.text}`)).toEqual([
      "comment:// add", "kw:const", "type:Order", "kw:await", "str:`x`",
    ]);
    expect(tokenize("SELECT id FROM orders -- all", "sql").filter((t) => t.kind !== "plain").map((t) => t.kind)).toEqual(["kw", "kw", "comment"]);
    expect(tokenize("<b>not html</b>", "text")).toEqual([{ kind: "plain", text: "<b>not html</b>" }]);
  });

  it("formats an instant in a zone, UTC for the title, and refuses a non-instant", () => {
    expect(utcText("2026-10-06T08:30:00Z", false)).toBe("2026-10-06 08:30:00 UTC");
    expect(utcText("2026-10-06T08:30:00Z", true)).toBe("2026-10-06");
    expect(localText("2026-10-06T08:30:00Z", { dateOnly: false, locale: "en-GB", timeZone: "Europe/Amsterdam" })).toBe("6 Oct 2026, 10:30:00");
    expect(utcText("yesterday", false)).toBeUndefined();
  });
});
