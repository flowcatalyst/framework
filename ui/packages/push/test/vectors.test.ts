// The reader against the contract's conformance vectors (conformance/push/, push contract section 13).
//
// What a READER can check, it checks here:
//   wire.json    every event's bytes parse (whole, and one byte at a time) into the message the vector
//                names, and PushCore turns each into the event the contract describes.
//   replay.json  the stream the host answers each case with (the vector's expected ids, or its reset)
//                is delivered exactly, in order, after the Last-Event-ID the case sends; a reset's id
//                replaces the reader's last id even when lower.
//   topics.json  `topicMatches` agrees with the vector's rules on the SHAPE of a topic (a rule's
//                `{placeholder}` read as `*`): an allowed topic always has the shape, a topic of another
//                shape is never allowed. Topic, type and data validation, rule declaration and the
//                permission, principal and client conditions are server-side only: a reader has none.
//   tickets.json VERIFYING tickets (signature, kid, iss, aud, exp, cnf) is server-side only: a reader
//                holds tickets as opaque strings. What it checks is the reader's half: the tickets and
//                topics go into the subscribe body verbatim, the denied list the host answers is
//                reported, and the error answers (NO_TOPICS, UNAUTHENTICATED, VALIDATION) surface with
//                their code and status.
import { strict as assert } from "node:assert";
import { readFileSync } from "node:fs";
import { describe, it } from "vitest";
import { PushCore, PushError, SseParser, topicMatches } from "../src/index.ts";
import type { PushMessage, PushStreamEventType } from "../src/index.ts";

type Obj = Record<string, unknown>;

function isObj(v: unknown): v is Obj {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}
function obj(v: unknown, what: string): Obj {
  if (!isObj(v)) throw new Error(`${what}: not an object`);
  return v;
}
function arr(v: unknown, what: string): unknown[] {
  if (!Array.isArray(v)) throw new Error(`${what}: not an array`);
  return v;
}
function str(v: unknown, what: string): string {
  if (typeof v !== "string") throw new Error(`${what}: not a string`);
  return v;
}
function num(v: unknown, what: string): number {
  if (typeof v !== "number") throw new Error(`${what}: not a number`);
  return v;
}
function strings(v: unknown, what: string): string[] {
  return arr(v, what).map((x, i) => str(x, `${what}[${String(i)}]`));
}

function vector(name: string): Obj {
  const text = readFileSync(new URL(`../../../../conformance/push/${name}.json`, import.meta.url), "utf8");
  return obj(JSON.parse(text), name);
}

// ── a fake host ──────────────────────────────────────────────────────

const enc = new TextEncoder();
const BASE = "https://app.test/api";

interface Seen {
  subscribes: { topics: string[]; tickets: string[] }[];
  gets: { url: URL; lastEventId: string | null }[];
  events: { type: PushStreamEventType; detail: unknown }[];
  pushes: PushMessage[];
}

interface Script {
  /** The subscribe answers, in order; the last repeats. */
  subscribe?: Response[];
  /** The stream bodies, in order; each is held open after its chunks. */
  streams?: string[][];
  /** The first N stream bodies end after their chunks (a dropped connection) and the reader reconnects at once. */
  dropFirst?: number;
  topics?: string[];
  tickets?: string[];
  lastEventId?: string;
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function until(cond: () => boolean, what: string): Promise<void> {
  for (let i = 0; i < 500; i++) {
    if (cond()) return;
    await new Promise((r) => setImmediate(r));
  }
  assert.fail(`timed out waiting for ${what}`);
}

/** Starts a PushCore against the scripted host; returns what it saw and the core. */
function start(script: Script): { core: PushCore; seen: Seen } {
  const seen: Seen = { subscribes: [], gets: [], events: [], pushes: [] };
  let subscribeCalls = 0;
  let streamCalls = 0;
  const fetchImpl = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url);
    if (url.pathname.endsWith("/push/subscriptions")) {
      const body: unknown = JSON.parse(typeof init?.body === "string" ? init.body : "");
      const b = obj(body, "subscribe body");
      seen.subscribes.push({ topics: strings(b["topics"], "topics"), tickets: strings(b["tickets"], "tickets") });
      const answers = script.subscribe ?? [
        json(200, { streamToken: "st", expiresAt: "2030-01-01T00:00:00Z", granted: seen.subscribes[0]?.topics ?? [], denied: [] }),
      ];
      const answer = answers[Math.min(subscribeCalls, answers.length - 1)];
      subscribeCalls++;
      return Promise.resolve(answer?.clone() ?? json(500, {}));
    }
    const headers = new Headers(init?.headers);
    seen.gets.push({ url, lastEventId: headers.get("Last-Event-ID") });
    const chunks = script.streams?.[streamCalls] ?? [];
    streamCalls++;
    const signal = init?.signal;
    const body = new ReadableStream<Uint8Array>({
      start: (c) => {
        for (const ch of chunks) c.enqueue(enc.encode(ch));
        if (streamCalls <= (script.dropFirst ?? 0)) c.close();
        signal?.addEventListener("abort", () => {
          try {
            c.error(new DOMException("aborted", "AbortError"));
          } catch {
            // closed
          }
        });
      },
    });
    return Promise.resolve(new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } }));
  };
  const core = new PushCore(
    {
      baseUrl: BASE,
      topics: script.topics ?? ["fulfil:order:1"],
      fetch: fetchImpl,
      streamFetch: fetchImpl,
      sleep: (_ms, signal) => {
        if (signal.aborted) return Promise.reject(new Error("aborted"));
        return script.dropFirst === undefined ? new Promise<void>(() => undefined) : Promise.resolve();
      },
      random: () => 0.5,
      getTickets: () => Promise.resolve(script.tickets ?? []),
      ...(script.lastEventId === undefined ? {} : { lastEventId: script.lastEventId }),
    },
    (type, detail) => seen.events.push({ type, detail }),
  );
  core.onAny((p) => seen.pushes.push(p));
  core.start();
  return { core, seen };
}

function eventsOf(seen: Seen, type: PushStreamEventType): unknown[] {
  return seen.events.filter((e) => e.type === type).map((e) => e.detail);
}

// ── wire.json ────────────────────────────────────────────────────────

describe("conformance: wire.json", () => {
  const wire = vector("wire");
  const startBytes = str(obj(wire["streamStart"], "streamStart")["bytes"], "streamStart.bytes");
  const heartbeat = str(obj(wire["heartbeat"], "heartbeat")["bytes"], "heartbeat.bytes");
  const events = arr(wire["events"], "events").map((e, i) => obj(e, `events[${String(i)}]`));

  it("reads the retry field and ignores the heartbeat comment", () => {
    const retries: number[] = [];
    const p = new SseParser((ms) => retries.push(ms));
    assert.deepEqual(p.feed(startBytes), []);
    assert.deepEqual(retries, [3000]);
    assert.deepEqual(p.feed(heartbeat), []);
  });

  for (const e of events) {
    const name = str(e["name"], "name");
    const input = obj(e["input"], "input");
    const bytes = str(e["bytes"], "bytes");

    it(`parses "${name}" whole and one byte at a time into one message`, () => {
      const whole = new SseParser().feed(bytes);
      assert.equal(whole.length, 1);
      const p = new SseParser();
      const bitwise = Array.from({ length: bytes.length }, (_, i) => bytes.charAt(i)).flatMap((ch) => p.feed(ch));
      assert.deepEqual(bitwise, whole);
      const m = whole[0];
      assert.ok(m);
      const data = obj(JSON.parse(m.data), "data");
      switch (name) {
        case "push":
        case "push with empty data":
          assert.equal(m.id, String(num(input["id"], "id")));
          assert.equal(m.event, str(input["type"], "type"));
          assert.equal(data["topic"], input["topic"]);
          assert.deepEqual(data["data"], JSON.parse(str(input["data"], "data")));
          break;
        case "subscribed":
        case "subscribed to an empty log":
          assert.equal(m.event, "subscribed");
          assert.equal(m.id, undefined);
          assert.deepEqual(data, input);
          break;
        case "reset, retention":
        case "reset, unknown id":
          assert.equal(m.event, "reset");
          assert.equal(m.id, String(num(input["latest"], "latest")));
          assert.deepEqual(data, input);
          break;
        case "auth-expired":
          assert.equal(m.event, "auth-expired");
          assert.deepEqual(data, {});
          break;
        default:
          assert.fail(`the wire vector has a case this test does not know: ${name}`);
      }
    });
  }

  it("PushCore turns each vector event into what the contract says", async () => {
    const subscribed = str(obj(events.find((e) => e["name"] === "subscribed"), "subscribed")["bytes"], "bytes");
    for (const e of events) {
      const name = str(e["name"], "name");
      const input = obj(e["input"], "input");
      const bytes = str(e["bytes"], "bytes");
      if (name.startsWith("subscribed")) {
        const { core, seen } = start({ streams: [[startBytes, bytes]] });
        await until(() => eventsOf(seen, "subscribed").length === 1, name);
        assert.deepEqual(eventsOf(seen, "subscribed")[0], { granted: input["granted"], latest: String(num(input["latest"], "latest")) });
        core.stop();
      } else if (name.startsWith("push")) {
        const { core, seen } = start({ streams: [[startBytes, subscribed, heartbeat, bytes]] });
        await until(() => seen.pushes.length === 1, name);
        const expectedData: unknown = JSON.parse(str(input["data"], "data"));
        assert.deepEqual(seen.pushes[0], {
          id: String(num(input["id"], "id")),
          topic: input["topic"],
          type: input["type"],
          data: expectedData,
        });
        assert.equal(core.lastEventId, String(num(input["id"], "id")));
        core.stop();
      } else if (name.startsWith("reset")) {
        const { core, seen } = start({ streams: [[startBytes, subscribed, bytes]], lastEventId: "900" });
        await until(() => eventsOf(seen, "reset").length === 1, name);
        assert.deepEqual(eventsOf(seen, "reset")[0], { reason: input["reason"], latest: String(num(input["latest"], "latest")) });
        assert.equal(core.lastEventId, String(num(input["latest"], "latest"))); // replaces ours, even when lower
        core.stop();
      } else if (name === "auth-expired") {
        const { core, seen } = start({ streams: [[startBytes, subscribed, bytes], [startBytes, subscribed]] });
        await until(() => seen.subscribes.length === 2 && seen.gets.length === 2, name);
        core.stop();
      } else {
        assert.fail(`unknown wire case: ${name}`);
      }
    }
  });
});

// ── replay.json ──────────────────────────────────────────────────────

describe("conformance: replay.json", () => {
  const replay = vector("replay");
  const log = arr(replay["log"], "log").map((e, i) => obj(e, `log[${String(i)}]`));
  const cases = arr(replay["cases"], "cases").map((e, i) => obj(e, `cases[${String(i)}]`));
  const byId = new Map(log.map((e) => [num(e["id"], "log id"), e]));
  const wire = vector("wire");
  const startBytes = str(obj(wire["streamStart"], "streamStart")["bytes"], "bytes");
  const latest = num(replay["latest"], "latest");

  const pushBytes = (id: number): string => {
    const e = byId.get(id);
    assert.ok(e, `log has no id ${String(id)}`);
    return `id: ${String(id)}\nevent: ${str(e["type"], "type")}\ndata: ${JSON.stringify({ topic: e["topic"], data: e["data"] })}\n\n`;
  };
  const subscribedBytes = `event: subscribed\ndata: ${JSON.stringify({ granted: [], latest })}\n\n`;
  const livePush = `id: ${String(latest + 1)}\nevent: live\ndata: ${JSON.stringify({ topic: "fulfil:order:1", data: { live: true } })}\n\n`;

  for (const c of cases) {
    const name = str(c["name"], "name");
    it(name, async () => {
      const expect = obj(c["expect"], "expect");
      const sent = c["lastEventId"];
      const given = typeof sent === "string" || typeof sent === "number" ? String(sent) : undefined;
      const topics = strings(c["topics"], "topics");
      if (isObj(expect["reset"])) {
        const reset = expect["reset"];
        const id = num(reset["id"], "reset id");
        const bytes = `id: ${String(id)}\nevent: reset\ndata: ${JSON.stringify({ reason: reset["reason"], latest: id })}\n\n`;
        const { core, seen } = start({ topics, streams: [[startBytes, subscribedBytes, bytes, livePush]], ...(given === undefined ? {} : { lastEventId: given }) });
        await until(() => seen.pushes.length === 1, "the live push after the reset");
        assert.equal(seen.gets[0]?.lastEventId ?? undefined, given); // sent as is
        assert.deepEqual(eventsOf(seen, "reset"), [{ reason: reset["reason"], latest: String(id) }]);
        assert.equal(core.lastEventId, String(latest + 1)); // the reset's id replaced ours, then the live push moved on
        assert.deepEqual(seen.pushes.map((p) => p.id), [String(latest + 1)]); // nothing from the past is delivered
        core.stop();
      } else {
        const ids = arr(expect["ids"], "ids").map((x) => num(x, "id"));
        const { core, seen } = start({ topics, streams: [[startBytes, subscribedBytes, ...ids.map(pushBytes), livePush]], ...(given === undefined ? {} : { lastEventId: given }) });
        await until(() => seen.pushes.length === ids.length + 1, "the replayed ids and the live push");
        assert.equal(seen.gets[0]?.lastEventId ?? undefined, given);
        assert.deepEqual(seen.pushes.slice(0, ids.length).map((p) => Number(p.id)), ids);
        assert.deepEqual(eventsOf(seen, "reset"), []);
        core.stop();
      }
    });
  }

  it("resumes from the last id after a drop, and a reset id replaces it (the retention case, end to end)", async () => {
    const { core, seen } = start({
      streams: [
        [startBytes, subscribedBytes, pushBytes(5), pushBytes(8)],
        [startBytes, subscribedBytes, `id: 12\nevent: reset\ndata: {"reason":"retention","latest":12}\n\n`],
        [startBytes, subscribedBytes],
      ],
      dropFirst: 2,
    });
    await until(() => seen.gets.length === 3, "three streams");
    assert.deepEqual(seen.gets.map((g) => g.lastEventId), [null, "8", "12"]);
    core.stop();
  });
});

// ── topics.json ──────────────────────────────────────────────────────

describe("conformance: topics.json", () => {
  const topics = vector("topics");
  const rules = arr(topics["rules"], "rules").map((e, i) => obj(e, `rules[${String(i)}]`));
  const shape = (pattern: string): string => pattern.replace(/\{[^}]*\}/g, "*");

  it("an accepted topic matches itself, and no accepted topic matches another", () => {
    const accepted = arr(topics["topics"], "topics")
      .map((e) => obj(e, "topic"))
      .filter((e) => e["accepted"] === true && typeof e["topic"] === "string")
      .map((e) => str(e["topic"], "topic"));
    assert.ok(accepted.length > 0);
    for (const t of accepted) {
      assert.ok(topicMatches(t, t), t);
      for (const other of accepted) if (other !== t) assert.ok(!topicMatches(t, other), `${t} vs ${other}`);
    }
  });

  it("a rule's placeholders read as * give the shape: allowed implies the shape matches, another shape is never allowed", () => {
    let checked = 0;
    for (const r of rules) {
      const rule = obj(r["rule"], "rule");
      const matches = topicMatches(shape(str(rule["pattern"], "pattern")), str(r["topic"], "topic"));
      if (r["allowed"] === true) assert.ok(matches, JSON.stringify(r));
      if (!matches) assert.equal(r["allowed"], false, JSON.stringify(r));
      checked++;
    }
    assert.ok(checked > 10);
  });
});

// ── tickets.json ─────────────────────────────────────────────────────

describe("conformance: tickets.json (the reader's half; verification is server-side)", () => {
  const tickets = vector("tickets");
  const cases = arr(tickets["cases"], "cases").map((e, i) => obj(e, `cases[${String(i)}]`));

  for (const c of cases) {
    const name = str(c["name"], "name");
    it(name, async () => {
      const expect = obj(c["expect"], "expect");
      const topics = strings(c["topics"], "topics");
      const given = strings(c["tickets"], "tickets");
      const denied = arr(expect["denied"] ?? [], "denied");
      const errorCode = typeof expect["error"] === "string" ? expect["error"] : null;
      let answer: Response;
      switch (errorCode) {
        case null:
          answer = json(200, { streamToken: "st", expiresAt: str(expect["expiresAt"], "expiresAt"), granted: strings(expect["granted"], "granted"), denied });
          break;
        case "NO_TOPICS":
          answer = json(403, { error: "NO_TOPICS", message: "nothing was granted", denied });
          break;
        case "UNAUTHENTICATED":
          answer = json(401, { error: "UNAUTHENTICATED", message: "needs a valid ticket" });
          break;
        case "VALIDATION":
          answer = json(400, { error: "VALIDATION", message: "malformed" });
          break;
        default:
          assert.fail(`unknown error in the vector: ${errorCode}`);
      }
      const { core, seen } = start({
        topics,
        tickets: given,
        subscribe: [answer, json(500, {})],
        streams: [[`event: subscribed\ndata: ${JSON.stringify({ granted: errorCode === null ? expect["granted"] : [], latest: 0 })}\n\n`]],
      });
      await until(() => (errorCode === null ? eventsOf(seen, "subscribed").length === 1 : eventsOf(seen, "error").length >= 1), "the outcome");
      // the tickets and topics go out verbatim
      assert.deepEqual(seen.subscribes[0], { topics, tickets: given });
      if (errorCode === null) {
        if (denied.length > 0) assert.deepEqual(eventsOf(seen, "denied")[0], denied);
        else assert.deepEqual(eventsOf(seen, "denied"), []);
        assert.deepEqual(eventsOf(seen, "subscribed")[0], { granted: expect["granted"], latest: "0" });
      } else {
        const err = eventsOf(seen, "error")[0];
        assert.ok(err instanceof PushError);
        assert.equal(err.code, errorCode);
        assert.equal(err.status, errorCode === "NO_TOPICS" ? 403 : errorCode === "UNAUTHENTICATED" ? 401 : 400);
        // NO_TOPICS ends the stream (nothing to listen to); the others are retried by the reader's backoff
        if (errorCode === "NO_TOPICS") await until(() => core.state === "stopped", "stopped");
      }
      core.stop();
    });
  }
});
