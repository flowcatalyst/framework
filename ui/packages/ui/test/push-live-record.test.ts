// useLiveRecord: a push is a signal with the record's version; read again when it is higher.
import { effectScope } from "vue";
import type { EffectScope } from "vue";
import { afterEach, describe, expect, it } from "vitest";
import { useLiveRecord } from "../src/push/index.ts";
import type { LiveRecord, PushHub } from "../src/push/index.ts";
import { Host, deferred, pushEvent, settle, sse, until } from "./push-host.ts";
import type { Deferred } from "./push-host.ts";

interface Rec {
  readonly v: number;
  readonly name: string;
}

const scopes: EffectScope[] = [];
afterEach(() => {
  for (const s of scopes.splice(0)) {
    s.stop();
  }
});

function make(hub: PushHub, load: (signal: AbortSignal) => Promise<Rec>): LiveRecord<Rec> {
  const scope = effectScope();
  scopes.push(scope);
  const made = scope.run(() =>
    useLiveRecord<Rec>({
      topics: ["t:order:1"],
      load,
      version: (r) => r.v,
      signal: (p) => {
        if (p.type !== "order.changed" || typeof p.data !== "object" || p.data === null || !("version" in p.data)) {
          return null;
        }
        const v = p.data.version;
        return typeof v === "number" ? v : null;
      },
      hub,
    }),
  );
  if (made === undefined) {
    throw new Error("scope inactive");
  }
  return made;
}

const changed = (n: number, v: number): string => pushEvent(n, "t:order:1", { version: v }, "order.changed");

describe("useLiveRecord", () => {
  it("subscribes FIRST, then reads (the read returns no cursor)", async () => {
    const host = new Host();
    const hub = host.hub();
    const order: string[] = [];
    const rec = make(hub, () => {
      order.push(`read after ${String(host.subscribes.length)} subscribe, ${String(host.gets.length)} stream`);
      return Promise.resolve({ v: 1, name: "one" });
    });
    expect(rec.record.value).toBeNull();
    expect(rec.status.value).toBe("loading");
    await until(() => rec.status.value === "live", "live");
    expect(order).toEqual(["read after 1 subscribe, 1 stream"]);
    expect(rec.record.value).toEqual({ v: 1, name: "one" });
    expect(rec.lastAppliedAt.value).not.toBeNull();
  });

  it("reads again for a higher version, not for an equal, lower or unrelated signal", async () => {
    const host = new Host();
    const hub = host.hub();
    let current = 1;
    let reads = 0;
    const rec = make(hub, () => {
      reads += 1;
      return Promise.resolve({ v: current, name: `v${String(current)}` });
    });
    await until(() => rec.status.value === "live", "live");
    host.send(changed(1, 1) + changed(2, 0) + pushEvent(3, "t:order:1", { version: 9 }, "unrelated.type"));
    await settle();
    expect(reads).toBe(1);

    current = 2;
    host.send(changed(4, 2));
    await until(() => rec.record.value?.v === 2, "a re-read");
    expect(reads).toBe(2);
  });

  it("coalesces a burst: one read in flight, one queued", async () => {
    const host = new Host();
    const hub = host.hub();
    let current = 1;
    const gates: Deferred<Rec>[] = [];
    let reads = 0;
    const rec = make(hub, () => {
      reads += 1;
      if (reads === 1) {
        return Promise.resolve({ v: 1, name: "first" });
      }
      const gate = deferred<Rec>();
      gates.push(gate);
      return gate.promise.then(() => ({ v: current, name: `v${String(current)}` }));
    });
    await until(() => rec.status.value === "live", "live");

    host.send(changed(1, 2)); // starts read 2 (held)
    await until(() => gates.length === 1, "read 2 started");
    host.send(changed(2, 3) + changed(3, 4) + changed(4, 5) + changed(5, 6) + changed(6, 7));
    await settle();
    expect(reads).toBe(2); // nothing else started while one is in flight

    current = 2; // read 2 saw only the first signal's version
    gates[0]?.resolve({ v: 0, name: "ignored" });
    await until(() => gates.length === 2, "the one queued read");
    expect(rec.record.value?.v).toBe(2);
    current = 7;
    gates[1]?.resolve({ v: 0, name: "ignored" });
    await until(() => rec.record.value?.v === 7, "the latest");
    await settle();
    expect(reads).toBe(3);
  });

  it("does not read again when the in-flight read already holds the signalled version", async () => {
    const host = new Host();
    const hub = host.hub();
    const gates: Deferred<Rec>[] = [];
    let reads = 0;
    const rec = make(hub, () => {
      reads += 1;
      if (reads === 1) {
        return Promise.resolve({ v: 1, name: "first" });
      }
      const gate = deferred<Rec>();
      gates.push(gate);
      return gate.promise;
    });
    await until(() => rec.status.value === "live", "live");
    host.send(changed(1, 2));
    await until(() => gates.length === 1, "read 2 started");
    host.send(changed(2, 3));
    await settle();
    gates[0]?.resolve({ v: 3, name: "has both" });
    await until(() => rec.record.value?.v === 3, "read 2 done");
    await settle();
    expect(reads).toBe(2);
  });

  it("reads again on reset", async () => {
    const host = new Host();
    const hub = host.hub();
    let reads = 0;
    const rec = make(hub, () => {
      reads += 1;
      return Promise.resolve({ v: 1, name: `read ${String(reads)}` });
    });
    await until(() => rec.status.value === "live", "live");
    host.send(sse("8", "reset", { reason: "retention", latest: 8 }));
    await until(() => reads === 2 && rec.record.value?.name === "read 2", "read again");
  });

  it("a failed read is reported, keeps the record, and waits for the next signal instead of looping", async () => {
    const host = new Host();
    const hub = host.hub();
    let reads = 0;
    const rec = make(hub, () => {
      reads += 1;
      if (reads === 2) {
        return Promise.reject(new Error("db down"));
      }
      return Promise.resolve({ v: reads, name: `r${String(reads)}` });
    });
    await until(() => rec.status.value === "live", "live");
    host.send(changed(1, 2));
    await until(() => rec.error.value !== null, "an error");
    await settle();
    expect(reads).toBe(2);
    expect(rec.record.value?.v).toBe(1);
    host.send(changed(2, 3));
    await until(() => rec.record.value?.v === 3, "recovered");
    expect(rec.error.value).toBeNull();
  });

  it("stop() ends the subscription", async () => {
    const host = new Host();
    const hub = host.hub();
    const rec = make(hub, () => Promise.resolve({ v: 1, name: "x" }));
    await until(() => rec.status.value === "live", "live");
    rec.stop();
    expect(rec.status.value).toBe("stopped");
    await until(() => hub.state.value === "stopped", "released");
  });
});
