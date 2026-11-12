// useLiveList: load with a cursor, listen, apply by version, reset (docs/contracts/push.md 9.1).
import { effectScope, nextTick, ref } from "vue";
import type { EffectScope } from "vue";
import { afterEach, describe, expect, it } from "vitest";
import { REMOVED_MEMORY_LIMIT, rowChange, useLiveList } from "../src/push/index.ts";
import type { LiveList, LiveLoad, PushHub } from "../src/push/index.ts";
import { Host, deferred, pushEvent, settle, sse, until } from "./push-host.ts";

interface Row {
  readonly id: string;
  readonly v: number;
  readonly name: string;
}

const row = (id: string, v: number, name = `${id}@${String(v)}`): Row => ({ id, v, name });
const upsert = (n: number, topic: string, r: Row): string => pushEvent(n, topic, { id: r.id, version: r.v, row: r }, "row.upserted");
const removal = (n: number, topic: string, id: string, v: number): string => pushEvent(n, topic, { id, version: v }, "row.removed");
const ids = (list: LiveList<Row>): string[] => list.rows.value.map((r) => r.id);

const scopes: EffectScope[] = [];
afterEach(() => {
  for (const s of scopes.splice(0)) {
    s.stop();
  }
});

function make(
  hub: PushHub,
  load: (signal: AbortSignal) => Promise<LiveLoad<Row>>,
  topics: string[] | { value: string[] } = ["t:orders"],
): LiveList<Row> {
  const scope = effectScope();
  scopes.push(scope);
  const made = scope.run(() =>
    useLiveList<Row>({
      topics: Array.isArray(topics) ? topics : () => topics.value,
      load,
      id: (r) => r.id,
      version: (r) => r.v,
      toChange: (p) => rowChange<Row>(p),
      hub,
    }),
  );
  if (made === undefined) {
    throw new Error("scope inactive");
  }
  return made;
}

const snapshot = (rows: Row[], pushCursor?: string) => (): Promise<LiveLoad<Row>> =>
  Promise.resolve(pushCursor === undefined ? { rows } : { rows, pushCursor });

describe("useLiveList: loading and following", () => {
  it("loads first, then opens the stream from the load's cursor; status loading then live", async () => {
    const host = new Host();
    const hub = host.hub();
    const gate = deferred<LiveLoad<Row>>();
    const list = make(hub, () => gate.promise);
    expect(list.status.value).toBe("loading");
    await settle();
    expect(host.subscribes).toEqual([]); // nothing listens before the snapshot
    gate.resolve({ rows: [row("b", 1), row("a", 1)], pushCursor: "10" });
    await until(() => list.status.value === "live", "live");
    expect(ids(list)).toEqual(["b", "a"]); // load order
    expect(host.gets[0]?.headers.get("Last-Event-ID")).toBe("10");
    expect(list.lastAppliedAt.value).not.toBeNull();
    expect(list.error.value).toBeNull();
  });

  it("replaces a held row only for a higher version; a repeat changes nothing; an unknown id is added", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1), row("b", 5)], "1"));
    await until(() => list.status.value === "live", "live");

    host.send(upsert(2, "t:orders", row("a", 2, "A2")));
    await until(() => list.rows.value[0]?.name === "A2", "a replaced");
    expect(ids(list)).toEqual(["a", "b"]); // position kept

    const before = list.rows.value;
    const stamp = list.lastAppliedAt.value;
    host.send(upsert(3, "t:orders", row("a", 2, "again")) + upsert(4, "t:orders", row("b", 4, "older")));
    await settle();
    expect(list.rows.value).toBe(before); // nothing changed: the same array
    expect(list.lastAppliedAt.value).toBe(stamp);

    host.send(upsert(5, "t:orders", row("c", 1)));
    await until(() => list.rows.value.length === 3, "c added");
    expect(ids(list)).toEqual(["a", "b", "c"]); // appended
  });

  it("removes a row and remembers it: an older or equal put is ignored, a higher one adds it back", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1), row("b", 1)], "1"));
    await until(() => list.status.value === "live", "live");

    host.send(removal(2, "t:orders", "a", 3));
    await until(() => ids(list).join() === "b", "a removed");

    host.send(upsert(3, "t:orders", row("a", 2)) + upsert(4, "t:orders", row("a", 3)));
    await settle();
    expect(ids(list)).toEqual(["b"]);

    host.send(upsert(5, "t:orders", row("a", 4)));
    await until(() => ids(list).join() === "b,a", "a back, appended");
  });

  it("ignores a stale removal (the held row is newer) but still remembers an unknown id's removal", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 5)], "1"));
    await until(() => list.status.value === "live", "live");
    host.send(removal(2, "t:orders", "a", 4) + removal(3, "t:orders", "z", 9) + upsert(4, "t:orders", row("z", 8)));
    await settle();
    expect(ids(list)).toEqual(["a"]);
  });

  it("ignores pushes that toChange does not claim", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1)], "1"));
    await until(() => list.status.value === "live", "live");
    host.send(pushEvent(2, "t:orders", { id: "q", version: 9, row: row("q", 9) }, "other.type") + pushEvent(3, "t:orders", "not an object"));
    await settle();
    expect(ids(list)).toEqual(["a"]);
  });

  it("compares versions numerically when both parse as numbers, else as strings", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = useStringVersions(hub);
    await until(() => list.status.value === "live", "live");
    host.send(pushEvent(2, "t:s", { id: "a", version: "10", row: { id: "a", v: "10" } }));
    await until(() => list.rows.value[0]?.v === "10", "10 > 9 numerically");
    host.send(pushEvent(3, "t:s", { id: "a", version: "9", row: { id: "a", v: "9" } }));
    await settle();
    expect(list.rows.value[0]?.v).toBe("10");
  });

  it("remembers at most 10 000 removed ids, the oldest dropped first", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([], "1"));
    await until(() => list.status.value === "live", "live");
    for (let i = 0; i <= REMOVED_MEMORY_LIMIT; i++) {
      list.apply({ kind: "remove", id: `r${String(i)}`, version: 1 });
    }
    // r0 was dropped from the memory: the same version is accepted again; r1 is still remembered
    expect(list.apply({ kind: "put", row: row("r0", 1) })).toBe(true);
    expect(list.apply({ kind: "put", row: row("r1", 1) })).toBe(false);
    expect(ids(list)).toEqual(["r0"]);
  });
});

function useStringVersions(hub: PushHub) {
  interface SRow {
    readonly id: string;
    readonly v: string;
  }
  const scope = effectScope();
  scopes.push(scope);
  const made = scope.run(() =>
    useLiveList<SRow>({
      topics: ["t:s"],
      load: () => Promise.resolve({ rows: [{ id: "a", v: "9" }], pushCursor: "1" }),
      id: (r) => r.id,
      version: (r) => r.v,
      toChange: (p) => rowChange<SRow>(p),
      hub,
    }),
  );
  if (made === undefined) {
    throw new Error("scope inactive");
  }
  return made;
}

describe("useLiveList: the caller's own changes", () => {
  it("apply() follows the same version rule", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1)], "1"));
    await until(() => list.status.value === "live", "live");
    expect(list.apply({ kind: "put", row: row("a", 2, "mine") })).toBe(true);
    expect(list.rows.value[0]?.name).toBe("mine");
    expect(list.apply({ kind: "put", row: row("a", 2, "dup") })).toBe(false);
    expect(list.apply({ kind: "put", row: row("a", 1, "old") })).toBe(false);
    expect(list.apply({ kind: "remove", id: "a", version: 3 })).toBe(true);
    expect(ids(list)).toEqual([]);
    // the stream may still deliver an older push for the row: it does not come back
    host.send(upsert(2, "t:orders", row("a", 2, "late")));
    await settle();
    expect(ids(list)).toEqual([]);
  });
});

describe("useLiveList: ordering between snapshot and stream", () => {
  it("when the stream already runs, it subscribes FIRST, then loads; pushes during the load apply after it", async () => {
    const host = new Host();
    const hub = host.hub();
    const order: string[] = [];
    const first = make(hub, () => {
      order.push("load1");
      return Promise.resolve({ rows: [row("a", 1)], pushCursor: "5" });
    });
    await until(() => first.status.value === "live", "first live");
    host.send(upsert(6, "t:orders", row("a", 1)));
    await settle();

    const gate = deferred<LiveLoad<Row>>();
    const second = make(
      hub,
      () => {
        order.push(`load2 after ${String(host.subscribes.length)} subscribes`);
        return gate.promise;
      },
      ["t:other"],
    );
    await until(() => order.length === 2, "the second load started");
    // the topics were added first: a second subscription exists, resuming from the last id
    expect(order[1]).toBe("load2 after 2 subscribes");
    expect(host.subscribes[1]?.topics).toEqual(["t:orders", "t:other"]);
    expect(host.gets[1]?.headers.get("Last-Event-ID")).toBe("6");
    expect(second.status.value).toBe("loading");

    // a push for the second list arrives while its load is in flight
    host.send(upsert(7, "t:other", row("x", 3, "pushed")) + removal(8, "t:other", "y", 4));
    await settle();
    expect(ids(second)).toEqual([]); // buffered, not shown
    gate.resolve({ rows: [row("x", 2, "snapshot"), row("y", 1)] });
    await until(() => second.status.value === "live", "second live");
    expect(second.rows.value).toEqual([row("x", 3, "pushed")]); // x newer than the snapshot's; y removed
  });

  it("does not load until the stream confirms the new subscription", async () => {
    const host = new Host();
    const hub = host.hub();
    const warm = hub.acquire(["t:warm"]);
    await until(() => hub.state.value === "open", "open");
    host.withholdSubscribed = true;
    let loads = 0;
    const list = make(hub, () => {
      loads += 1;
      return Promise.resolve({ rows: [row("a", 1)] });
    });
    await until(() => host.gets.length === 2, "the resubscribed stream");
    await settle();
    expect(loads).toBe(0); // the stream is open but has not said `subscribed`
    host.confirm();
    await until(() => list.status.value === "live", "live");
    expect(loads).toBe(1);
    warm.release();
  });

  it("a load racing a push: the snapshot's older rows never overwrite a newer pushed one", async () => {
    const host = new Host();
    const hub = host.hub();
    const base = hub.acquire(["t:warm"]);
    await until(() => hub.state.value === "open", "open");
    const gate = deferred<LiveLoad<Row>>();
    const list = make(hub, () => gate.promise);
    await until(() => host.subscribes.length === 2, "subscribed first");
    await until(() => host.gets.length === 2, "second stream");
    host.send(upsert(1, "t:orders", row("a", 5, "pushed")));
    await settle();
    gate.resolve({ rows: [row("a", 3, "stale"), row("b", 1)] });
    await until(() => list.status.value === "live", "live");
    expect(list.rows.value.map((r) => r.name)).toEqual(["pushed", "b@1"]);
    base.release();
  });

  it("a change applied by the caller during a load survives it", async () => {
    const host = new Host();
    const hub = host.hub();
    const warm = hub.acquire(["t:warm"]);
    await until(() => hub.state.value === "open", "open");
    const gate = deferred<LiveLoad<Row>>();
    const list = make(hub, () => gate.promise);
    await until(() => host.gets.length === 2, "second stream");
    list.apply({ kind: "remove", id: "a", version: 2 });
    gate.resolve({ rows: [row("a", 1), row("b", 1)] }); // read before the caller's command
    await until(() => list.status.value === "live", "live");
    expect(ids(list)).toEqual(["b"]);
    warm.release();
  });

  it("two lists starting together open ONE stream from the lowest cursor", async () => {
    const host = new Host();
    const hub = host.hub();
    const a = make(hub, snapshot([row("a", 1)], "9"), ["t:a"]);
    const b = make(hub, snapshot([row("b", 1)], "7"), ["t:b"]);
    await until(() => a.status.value === "live" && b.status.value === "live", "both live");
    expect(host.subscribes).toHaveLength(1);
    expect(host.subscribes[0]?.topics).toEqual(["t:a", "t:b"]);
    expect(host.gets[0]?.headers.get("Last-Event-ID")).toBe("7");
  });
});

describe("useLiveList: reset, expiry, denied topics, topic changes", () => {
  it("reset loads again from scratch and clears the removed-id memory", async () => {
    const host = new Host();
    const hub = host.hub();
    let calls = 0;
    const list = make(hub, () => {
      calls += 1;
      return Promise.resolve(calls === 1 ? { rows: [row("a", 5), row("b", 1)], pushCursor: "1" } : { rows: [row("a", 3), row("c", 1)] });
    });
    await until(() => list.status.value === "live", "live");
    host.send(removal(2, "t:orders", "a", 6));
    await until(() => ids(list).join() === "b", "a removed");

    host.send(sse("9", "reset", { reason: "retention", latest: 9 }));
    await until(() => calls === 2 && list.status.value === "live", "reloaded");
    expect(list.rows.value).toEqual([row("a", 3), row("c", 1)]); // the snapshot is authoritative: a is back at v3
    // the stream carries on from the reset's id
    host.send(upsert(10, "t:orders", row("c", 2)));
    await until(() => list.rows.value[1]?.v === 2, "a push after the reset");
  });

  it("status is loading while a reset reload runs, and a failed load keeps the rows and reports the error", async () => {
    const host = new Host();
    const hub = host.hub();
    let calls = 0;
    const gate = deferred<LiveLoad<Row>>();
    const list = make(hub, () => {
      calls += 1;
      if (calls === 1) {
        return Promise.resolve({ rows: [row("a", 1)], pushCursor: "1" });
      }
      return gate.promise;
    });
    await until(() => list.status.value === "live", "live");
    host.send(sse("4", "reset", { reason: "unknown-id", latest: 4 }));
    await until(() => list.status.value === "loading", "loading");
    gate.reject(new Error("db down"));
    await until(() => list.error.value !== null, "an error");
    expect(ids(list)).toEqual(["a"]);
    expect(list.status.value).toBe("live");
  });

  it("auth-expired: the stream resubscribes, resumes, and pushes keep applying", async () => {
    const host = new Host();
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1)], "1"));
    await until(() => list.status.value === "live", "live");
    host.send(upsert(20, "t:orders", row("a", 2)) + sse(null, "auth-expired", {}));
    await until(() => host.subscribes.length === 2 && host.gets.length === 2, "resubscribed");
    expect(host.gets[1]?.headers.get("Last-Event-ID")).toBe("20");
    host.send(upsert(21, "t:orders", row("a", 3)));
    await until(() => list.rows.value[0]?.v === 3, "a push on the new token");
  });

  it("a denied topic is reported by the hub; the list goes on with the rest", async () => {
    const host = new Host();
    host.denyTopics = ["t:secret"];
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1)], "1"), ["t:orders", "t:secret"]);
    await until(() => list.status.value === "live", "live");
    expect(hub.denied.value).toEqual([{ topic: "t:secret", code: "TOPIC_FORBIDDEN" }]);
    host.send(upsert(2, "t:orders", row("a", 2)));
    await until(() => list.rows.value[0]?.v === 2, "a push on the granted topic");
  });

  it("a change of topics while live subscribes with the new set (last id kept), THEN loads again", async () => {
    const host = new Host();
    const hub = host.hub();
    const topics = ref(["t:orders"]);
    const order: string[] = [];
    const list = make(
      hub,
      () => {
        order.push(`load (${String(host.subscribes.length)} subscribes)`);
        return Promise.resolve({ rows: [row("a", 1)], pushCursor: "1" });
      },
      topics,
    );
    await until(() => list.status.value === "live", "live");
    host.send(upsert(30, "t:orders", row("a", 2)));
    await until(() => list.rows.value[0]?.v === 2, "pushed");

    topics.value = ["t:mine"];
    await nextTick();
    await until(() => order.length === 2 && list.status.value === "live", "reloaded");
    expect(order).toEqual(["load (0 subscribes)", "load (2 subscribes)"]);
    expect(host.subscribes[1]?.topics).toEqual(["t:mine"]);
    expect(host.gets[1]?.headers.get("Last-Event-ID")).toBe("30");
    expect(list.rows.value).toEqual([row("a", 1)]);
    // the old topic is no longer followed
    host.send(upsert(31, "t:orders", row("a", 9)));
    host.send(upsert(32, "t:mine", row("a", 2)));
    await until(() => list.rows.value[0]?.v === 2, "the new topic's push");
  });

  it("status: reconnecting while the stream is down, stopped after stop()", async () => {
    const host = new Host();
    host.hangSleeps = true;
    const hub = host.hub();
    const list = make(hub, snapshot([row("a", 1)], "1"));
    await until(() => list.status.value === "live", "live");
    host.end();
    await until(() => list.status.value === "reconnecting", "reconnecting");
    list.stop();
    expect(list.status.value).toBe("stopped");
    await until(() => hub.state.value === "stopped", "the hub let go");
  });

  it("a load that fails at the start reports the error and reload() tries again", async () => {
    const host = new Host();
    const hub = host.hub();
    let calls = 0;
    const list = make(hub, () => {
      calls += 1;
      return calls === 1 ? Promise.reject(new Error("nope")) : Promise.resolve({ rows: [row("a", 1)], pushCursor: "1" });
    });
    await until(() => list.error.value !== null, "an error");
    expect(list.status.value).toBe("stopped");
    await list.reload();
    await until(() => list.status.value === "live", "live");
    expect(list.error.value).toBeNull();
    expect(ids(list)).toEqual(["a"]);
  });
});

describe("rowChange", () => {
  const msg = (type: string, data: unknown) => ({ id: "1", topic: "t:x", type, data });
  it("reads row.upserted and row.removed, and nothing else", () => {
    expect(rowChange<Row>(msg("row.upserted", { id: "a", version: 2, row: row("a", 2) }))).toEqual({ kind: "put", row: row("a", 2) });
    expect(rowChange<Row>(msg("row.removed", { id: "a", version: 3 }))).toEqual({ kind: "remove", id: "a", version: 3 });
    expect(rowChange<Row>(msg("row.removed", { id: "a" }))).toBeNull();
    expect(rowChange<Row>(msg("row.upserted", {}))).toBeNull();
    expect(rowChange<Row>(msg("row.upserted", "x"))).toBeNull();
    expect(rowChange<Row>(msg("order.updated", { id: "a", version: 1 }))).toBeNull();
  });
});
