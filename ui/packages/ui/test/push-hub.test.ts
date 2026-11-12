// The push hub (docs/contracts/push.md sections 6 to 8): one stream per page, topics reference-counted,
// batched, limited and reported.
import { mount } from "@vue/test-utils";
import { afterEach, describe, expect, it, vi } from "vitest";
import { defineComponent, h, nextTick, ref } from "vue";
import { MAX_TOPICS, createPushHub, providePushHub, usePushHub, usePushTopics } from "../src/push/index.ts";
import type { PushHub, PushMessage } from "../src/push/index.ts";
import { BASE, Host, json, pushEvent, settle, sse, until, urlOf } from "./push-host.ts";

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("push hub: topics", () => {
  it("starts when the first topic arrives and stops when none are left", async () => {
    const host = new Host();
    const hub = host.hub();
    expect(hub.state.value).toBe("stopped");
    expect(hub.active).toBe(false);
    await settle();
    expect(host.subscribes).toEqual([]);

    const claim = hub.acquire(["a:one"]);
    await claim.applied;
    await until(() => hub.state.value === "open", "open");
    expect(host.subscribes).toEqual([{ topics: ["a:one"], tickets: [] }]);

    claim.release();
    await claim.applied;
    await until(() => hub.state.value === "stopped", "stopped");
    expect(hub.active).toBe(false);
    expect(host.gets[0]?.signal.aborted).toBe(true);
    expect(host.subscribes).toHaveLength(1);
  });

  it("reference-counts a topic: two claims, one entry; it goes with the last", async () => {
    const host = new Host();
    const hub = host.hub();
    const a = hub.acquire(["a:one", "a:two"]);
    const b = hub.acquire(["a:two", "a:three"]);
    await b.applied;
    await until(() => hub.state.value === "open", "open");
    expect(host.subscribes).toHaveLength(1);
    expect(host.subscribes[0]?.topics).toEqual(["a:one", "a:two", "a:three"]);

    a.release();
    await settle();
    // a:two is still wanted by b; only a:one goes
    expect(host.subscribes).toHaveLength(2);
    expect(host.subscribes[1]?.topics).toEqual(["a:two", "a:three"]);
    expect(hub.topics.value).toEqual(["a:two", "a:three"]);

    a.release(); // idempotent
    b.release();
    await until(() => hub.state.value === "stopped", "stopped");
  });

  it("batches a tick's changes into ONE subscription", async () => {
    const host = new Host();
    const hub = host.hub();
    const claims = [hub.acquire(["a:1"]), hub.acquire(["a:2"]), hub.acquire(["a:3", "a:1"])];
    await claims[2]?.applied;
    await until(() => hub.state.value === "open", "open");
    expect(host.subscribes).toHaveLength(1);
    expect(host.subscribes[0]?.topics).toEqual(["a:1", "a:2", "a:3"]);

    // swap one claim for another in the same tick: the set is unchanged, so nothing is resubscribed
    claims[1]?.release();
    const swapped = hub.acquire(["a:2"]);
    await swapped.applied;
    await settle();
    expect(host.subscribes).toHaveLength(1);

    // two changes in one tick: one resubscribe
    swapped.release();
    const added = hub.acquire(["a:4"]);
    hub.acquire(["a:5"]);
    await added.applied;
    await settle();
    expect(host.subscribes).toHaveLength(2);
    expect(host.subscribes[1]?.topics).toEqual(["a:1", "a:3", "a:4", "a:5"]);
  });

  it("keeps the last id across a change of topics", async () => {
    const host = new Host();
    const hub = host.hub();
    const seen: PushMessage[] = [];
    hub.on("a:**", (p) => seen.push(p));
    const first = hub.acquire(["a:1"]);
    await until(() => hub.state.value === "open", "open");
    host.send(pushEvent(50, "a:1", { v: 1 }));
    await until(() => seen.length === 1, "a push");
    const second = hub.acquire(["a:2"]);
    await second.applied;
    await until(() => host.gets.length === 2, "the second stream");
    expect(host.gets[1]?.headers.get("Last-Event-ID")).toBe("50");
    expect(host.gets[1]?.url.searchParams.get("lastEventId")).toBe("50");
    first.release();
    second.release();
  });

  it("opens the first stream from a snapshot cursor, the lowest when several claims bring one", async () => {
    const host = new Host();
    const hub = host.hub();
    const a = hub.acquire(["a:1"], { cursor: "9" });
    const b = hub.acquire(["a:2"], { cursor: "7" });
    expect(await a.applied).toBe(true);
    expect(await b.applied).toBe(true);
    await until(() => host.gets.length === 1, "the stream");
    expect(host.gets[0]?.headers.get("Last-Event-ID")).toBe("7");

    // the stream is already running: a cursor claim learns it was not used
    const c = hub.acquire(["a:3"], { cursor: "3" });
    expect(await c.applied).toBe(false);
    a.release();
    b.release();
    c.release();
  });
});

describe("push hub: limits", () => {
  const many = (n: number): string[] => Array.from({ length: n }, (_, i) => `a:t${String(i)}`);

  it("does not truncate more than 100 topics: it reports PUSH_LIMIT and keeps the previous subscription", async () => {
    const host = new Host();
    const hub = host.hub();
    const small = hub.acquire(["a:keep"]);
    await until(() => hub.state.value === "open", "open");
    expect(hub.error.value).toBeNull();

    const big = hub.acquire(many(MAX_TOPICS));
    expect(await big.applied).toBe(false);
    expect(hub.error.value?.code).toBe("PUSH_LIMIT");
    expect(hub.error.value?.message).toContain("100");
    expect(host.subscribes).toHaveLength(1);
    expect(hub.topics.value).toEqual(["a:keep"]);
    expect(hub.state.value).toBe("open");

    // dropping the excess lets the set through again and clears the report
    big.release();
    await settle();
    expect(hub.error.value).toBeNull();
    small.release();
  });

  it("takes exactly 100 topics", async () => {
    const host = new Host();
    const hub = host.hub();
    const claim = hub.acquire(many(MAX_TOPICS));
    await claim.applied;
    await until(() => hub.state.value === "open", "open");
    expect(host.subscribes[0]?.topics).toHaveLength(100);
    expect(hub.error.value).toBeNull();
    claim.release();
  });

  it("surfaces the server's PUSH_LIMIT (20 streams per principal)", async () => {
    const host = new Host();
    host.hangSleeps = true;
    host.subscribeScript.push(json(429, { error: "PUSH_LIMIT", message: "too many streams" }));
    const hub = host.hub();
    const claim = hub.acquire(["a:1"]);
    await until(() => hub.error.value !== null, "an error");
    expect(hub.error.value?.code).toBe("PUSH_LIMIT");
    expect(hub.error.value?.status).toBe(429);
    expect(hub.state.value).toBe("reconnecting");
    claim.release();
  });
});

describe("push hub: state, denied topics, handlers", () => {
  it("reports denied topics and the time of the last push", async () => {
    const host = new Host();
    host.denyTopics = ["a:secret"];
    const hub = host.hub();
    expect(hub.lastPushAt.value).toBeNull();
    const claim = hub.acquire(["a:ok", "a:secret"]);
    await until(() => hub.state.value === "open", "open");
    expect(hub.denied.value).toEqual([{ topic: "a:secret", code: "TOPIC_FORBIDDEN" }]);
    host.send(pushEvent(1, "a:ok", {}));
    await until(() => hub.lastPushAt.value !== null, "lastPushAt");
    claim.release();
  });

  it("clears a stream error when the stream opens", async () => {
    const host = new Host();
    host.subscribeScript.push(json(500, {}));
    const hub = host.hub();
    const claim = hub.acquire(["a:1"]);
    await until(() => hub.state.value === "open", "open after the retry");
    expect(hub.error.value).toBeNull();
    claim.release();
  });

  it("routes pushes by pattern and unsubscribes; a throwing handler is reported", async () => {
    const host = new Host();
    const hub = host.hub();
    const exact: string[] = [];
    const rest: string[] = [];
    const off = hub.on("a:1", (p) => exact.push(p.id));
    hub.on("a:**", (p) => rest.push(p.id));
    hub.on("a:1", () => {
      throw new Error("boom");
    });
    const claim = hub.acquire(["a:1", "a:2"]);
    await until(() => hub.state.value === "open", "open");
    host.send(pushEvent(1, "a:1", {}) + pushEvent(2, "a:2", {}));
    await until(() => rest.length === 2, "two pushes");
    expect(exact).toEqual(["1"]);
    expect(hub.error.value?.code).toBe("HANDLER");
    off();
    host.send(pushEvent(3, "a:1", {}));
    await until(() => rest.length === 3, "a third push");
    expect(exact).toEqual(["1"]);
    claim.release();
  });

  it("tells reset handlers and the last id moves to the reset's", async () => {
    const host = new Host();
    const hub = host.hub();
    const resets: string[] = [];
    const off = hub.onReset((d) => resets.push(`${d.reason}:${d.latest}`));
    const claim = hub.acquire(["a:1"]);
    await until(() => hub.state.value === "open", "open");
    host.send(sse("3", "reset", { reason: "retention", latest: 3 }));
    await until(() => resets.length === 1, "a reset");
    expect(resets).toEqual(["retention:3"]);
    off();
    host.send(sse("4", "reset", { reason: "retention", latest: 4 }));
    await settle();
    expect(resets).toHaveLength(1);
    claim.release();
  });

  it("resubscribes on auth-expired and carries on from the last id", async () => {
    const host = new Host();
    const hub = host.hub();
    const seen: string[] = [];
    hub.on("a:1", (p) => seen.push(p.id));
    const claim = hub.acquire(["a:1"]);
    await until(() => hub.state.value === "open", "open");
    host.send(pushEvent(20, "a:1", {}) + sse(null, "auth-expired", {}));
    await until(() => host.subscribes.length === 2 && host.gets.length === 2, "the second subscribe and stream");
    expect(host.gets[1]?.headers.get("Last-Event-ID")).toBe("20");
    host.send(pushEvent(21, "a:1", {}));
    await until(() => seen.length === 2, "a push on the new stream");
    expect(seen).toEqual(["20", "21"]);
    claim.release();
  });
});

describe("push hub: HTTP", () => {
  it("subscribes through the application's HTTP: same-origin credentials by default; the stream GET is plain", async () => {
    const calls: { url: string; init: RequestInit | undefined }[] = [];
    const host = new Host();
    vi.stubGlobal("fetch", (input: RequestInfo | URL, init?: RequestInit) => {
      calls.push({ url: urlOf(input), init });
      return host.fetch(input, init);
    });
    const hub = createPushHub({ baseUrl: BASE, sleep: host.sleep });
    const claim = hub.acquire(["a:1"]);
    await until(() => host.gets.length === 1, "the stream GET");
    expect(calls[0]?.url).toBe(`${BASE}/push/subscriptions`);
    expect(calls[0]?.init?.credentials).toBe("same-origin");
    expect(calls[1]?.url).toContain("/push/stream?st=st1");
    expect(calls[1]?.init?.credentials).toBeUndefined();
    claim.release();
  });

  it("uses the caller's fetch as given, and getTickets for the tickets", async () => {
    const host = new Host();
    const own = vi.fn(host.fetch);
    const hub = host.hub({ fetch: own, getTickets: (topics) => Promise.resolve(topics.map((t) => `tk:${t}`)) });
    const claim = hub.acquire(["a:1"]);
    await until(() => host.gets.length === 1, "the stream GET");
    expect(own).toHaveBeenCalledTimes(1);
    expect(own.mock.calls[0]?.[1]?.credentials).toBeUndefined();
    expect(host.subscribes[0]?.tickets).toEqual(["tk:a:1"]);
    claim.release();
  });
});

describe("push hub in components", () => {
  function child(topics: () => readonly string[], hubRef?: { hub: PushHub | null }) {
    return defineComponent({
      setup() {
        const hub = usePushHub();
        if (hubRef !== undefined) {
          hubRef.hub = hub;
        }
        usePushTopics(topics);
        return () => h("span");
      },
    });
  }

  it("providePushHub provides ONE stream; components share it and drop their topics on unmount", async () => {
    const host = new Host();
    const shown = ref({ a: true, b: true });
    const hubRef: { hub: PushHub | null } = { hub: null };
    const A = child(() => ["a:shared", "a:only-a"], hubRef);
    const B = child(() => ["a:shared"]);
    const Root = defineComponent({
      setup() {
        providePushHub(host.options());
        return () => h("div", [shown.value.a ? h(A) : null, shown.value.b ? h(B) : null]);
      },
    });
    const wrapper = mount(Root);
    await until(() => hubRef.hub?.state.value === "open", "open");
    expect(host.subscribes).toHaveLength(1);
    expect(host.subscribes[0]?.topics).toEqual(["a:shared", "a:only-a"]);

    shown.value = { a: false, b: true };
    await nextTick();
    await settle();
    expect(host.subscribes).toHaveLength(2);
    expect(host.subscribes[1]?.topics).toEqual(["a:shared"]);

    shown.value = { a: false, b: false };
    await nextTick();
    await until(() => hubRef.hub?.state.value === "stopped", "stopped");
    wrapper.unmount();
  });

  it("follows a changing topic source", async () => {
    const host = new Host();
    const topics = ref(["a:1"]);
    const hubRef: { hub: PushHub | null } = { hub: null };
    const Page = child(() => topics.value, hubRef);
    const Root = defineComponent({
      setup() {
        providePushHub(host.options());
        return () => h(Page);
      },
    });
    const wrapper = mount(Root);
    await until(() => hubRef.hub?.state.value === "open", "open");
    topics.value = ["a:2"];
    await until(() => host.subscribes.length === 2, "a resubscribe");
    expect(host.subscribes[1]?.topics).toEqual(["a:2"]);
    wrapper.unmount();
    await until(() => hubRef.hub?.state.value === "stopped", "stopped");
  });

  it("usePushHub without a provider says so", () => {
    const Lonely = defineComponent({
      setup() {
        usePushHub();
        return () => h("span");
      },
    });
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    expect(() => mount(Lonely)).toThrow(/providePushHub/);
    warn.mockRestore();
  });
});
