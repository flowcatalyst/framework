import { strict as assert } from "node:assert";
import { describe, it } from "vitest";
import {
	PushError,
	PushStream,
	SseParser,
	topicMatches,
	type PushMessage,
	type PushStreamEvent,
	type PushStreamOptions,
} from "../src/index.ts";

const BASE = "https://app.test/api";

// ── helpers ──────────────────────────────────────────────────────────

const enc = new TextEncoder();

function sse(id: string | null, type: string, data: unknown): string {
	return `${id === null ? "" : `id: ${id}\n`}event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
}

function pushEv(id: number, topic: string, data: unknown = {}): string {
	return sse(String(id), "order.updated", { topic, data });
}

const subscribedEv = (granted: string[], latest = 0) => sse(null, "subscribed", { granted, latest });

type GetScript =
	| { status: number; body?: string }
	// A body that serves `chunks` then ends (or stays open when `hold`).
	| { chunks: string[]; hold?: boolean };

interface Get {
	url: URL;
	headers: Record<string, string>;
	signal: AbortSignal;
}

class Host {
	subscribes: { topics: string[]; tickets: string[] }[] = [];
	gets: Get[] = [];
	subscribeScript: (Response | Error)[] = [];
	getScript: GetScript[] = [];
	sleeps: number[] = [];
	controllers: ReadableStreamDefaultController<Uint8Array>[] = [];

	fetch = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
		const url = new URL(String(input));
		if (url.pathname.endsWith("/push/subscriptions")) {
			const body = JSON.parse(String(init?.body)) as { topics: string[]; tickets: string[] };
			this.subscribes.push(body);
			const next = this.subscribeScript.shift();
			if (next instanceof Error) throw next;
			if (next) return next;
			return json(200, {
				streamToken: `st${this.subscribes.length}`,
				expiresAt: "2030-01-01T00:00:00Z",
				granted: body.topics,
				denied: [],
			});
		}
		const signal = init?.signal as AbortSignal;
		this.gets.push({ url, headers: { ...(init?.headers as Record<string, string>) }, signal });
		const script = this.getScript.shift() ?? { chunks: [], hold: true };
		if ("status" in script) return new Response(script.body ?? "", { status: script.status });
		const stream = new ReadableStream<Uint8Array>({
			start: (c) => {
				this.controllers.push(c);
				for (const ch of script.chunks) c.enqueue(enc.encode(ch));
				if (!script.hold) c.close();
				signal.addEventListener("abort", () => {
					try {
						c.error(new DOMException("aborted", "AbortError"));
					} catch {
						// already closed
					}
				});
			},
		});
		return new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } });
	};

	sleep = (ms: number, signal: AbortSignal): Promise<void> => {
		this.sleeps.push(ms);
		return signal.aborted ? Promise.reject(new Error("aborted")) : Promise.resolve();
	};

	stream(over: Partial<PushStreamOptions> = {}): PushStream {
		return new PushStream({
			baseUrl: BASE,
			topics: ["fulfil:order:1"],
			fetch: this.fetch,
			streamFetch: this.fetch,
			sleep: this.sleep,
			random: () => 0.5, // jitter factor 1.0
			...over,
		});
	}
}

function json(status: number, body: unknown): Response {
	return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

async function until(cond: () => boolean, what = "condition"): Promise<void> {
	for (let i = 0; i < 500; i++) {
		if (cond()) return;
		await new Promise((r) => setImmediate(r));
	}
	assert.fail(`timed out waiting for ${what}`);
}

function collect(s: PushStream): PushMessage[] {
	const got: PushMessage[] = [];
	s.onAny((p) => got.push(p));
	return got;
}

function events<T>(s: PushStream, type: "subscribed" | "reset" | "denied" | "error" | "state"): T[] {
	const got: T[] = [];
	s.addEventListener(type, (e) => got.push((e as PushStreamEvent<T>).detail));
	return got;
}

// ── parser ───────────────────────────────────────────────────────────

describe("SseParser", () => {
	it("parses id/event/data and dispatches on a blank line", () => {
		const p = new SseParser();
		assert.deepEqual(p.feed("id: 7\nevent: x\ndata: {\"a\":1}\n\n"), [{ event: "x", data: '{"a":1}', id: "7" }]);
	});

	it("handles chunk splits inside a field name, a value and a line ending", () => {
		const text = "id: 12\nevent: order.updated\ndata: hello\n\n";
		for (let cut = 1; cut < text.length; cut++) {
			const p = new SseParser();
			const out = [...p.feed(text.slice(0, cut)), ...p.feed(text.slice(cut))];
			assert.deepEqual(out, [{ event: "order.updated", data: "hello", id: "12" }], `cut at ${cut}`);
		}
	});

	it("handles CRLF, including a CR and LF split across chunks", () => {
		const p = new SseParser();
		const out = [...p.feed("id: 1\r"), ...p.feed("\nevent: a\r\ndata: x\r\n\r"), ...p.feed("\n")];
		assert.deepEqual(out, [{ event: "a", data: "x", id: "1" }]);
	});

	it("joins multi-line data with a newline", () => {
		const p = new SseParser();
		assert.deepEqual(p.feed("event: a\ndata: one\ndata: two\ndata:\ndata: four\n\n"), [
			{ event: "a", data: "one\ntwo\n\nfour", id: undefined },
		]);
	});

	it("ignores comment lines", () => {
		const p = new SseParser();
		assert.deepEqual(p.feed(": ping\n\n: another\nevent: a\n: mid\ndata: x\n\n"), [{ event: "a", data: "x", id: undefined }]);
	});

	it("reports the retry field and ignores a bad one", () => {
		const seen: number[] = [];
		const p = new SseParser((ms) => seen.push(ms));
		p.feed("retry: 1500\n\nretry: soon\n\n");
		assert.deepEqual(seen, [1500]);
	});

	it("does not leak fields into the next event", () => {
		const p = new SseParser();
		const out = p.feed("id: 1\nevent: a\ndata: x\n\ndata: y\n\n");
		assert.deepEqual(out, [
			{ event: "a", data: "x", id: "1" },
			{ event: "message", data: "y", id: undefined },
		]);
	});
});

// ── topics ───────────────────────────────────────────────────────────

describe("topicMatches", () => {
	it("matches literals, * for one segment and a final ** for the rest", () => {
		assert.ok(topicMatches("fulfil:order:1", "fulfil:order:1"));
		assert.ok(!topicMatches("fulfil:order:1", "fulfil:order:2"));
		assert.ok(topicMatches("fulfil:order:*", "fulfil:order:9"));
		assert.ok(!topicMatches("fulfil:order:*", "fulfil:order:9:x"));
		assert.ok(!topicMatches("fulfil:order:*", "fulfil:order"));
		assert.ok(topicMatches("fulfil:**", "fulfil:store:12:dock"));
		assert.ok(!topicMatches("fulfil:**", "fulfil"));
		assert.ok(!topicMatches("fulfil:**", "other:store"));
		assert.ok(topicMatches("fulfil:*:dock", "fulfil:12:dock"));
	});
});

// ── the stream ───────────────────────────────────────────────────────

describe("PushStream", () => {
	it("subscribes with topics and tickets, then listens with the stream token", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv(["fulfil:order:1"], 5)], hold: true });
		const tickets: string[][] = [];
		const s = h.stream({
			getTickets: async (t) => {
				tickets.push(t);
				return ["tk1"];
			},
		});
		const subscribed = events<{ granted: string[]; latest: string }>(s, "subscribed");
		s.start();
		await until(() => subscribed.length === 1, "subscribed");
		assert.deepEqual(h.subscribes, [{ topics: ["fulfil:order:1"], tickets: ["tk1"] }]);
		assert.deepEqual(tickets, [["fulfil:order:1"]]);
		assert.equal(h.gets[0]?.url.searchParams.get("st"), "st1");
		assert.equal(h.gets[0]?.url.searchParams.has("lastEventId"), false);
		assert.equal(h.gets[0]?.headers["Last-Event-ID"], undefined);
		assert.deepEqual(subscribed[0], { granted: ["fulfil:order:1"], latest: "5" });
		assert.equal(s.state, "open");
		s.stop();
	});

	it("delivers pushes in order and drops an id at or below the last delivered", async () => {
		const h = new Host();
		h.getScript.push({
			chunks: [subscribedEv([]), pushEv(5, "fulfil:order:1", { v: 1 }), pushEv(6, "fulfil:order:1", { v: 2 }), pushEv(6, "fulfil:order:1", { v: 2 }), pushEv(4, "fulfil:order:1")],
			hold: true,
		});
		const s = h.stream();
		const got = collect(s);
		s.start();
		await until(() => got.length >= 2, "two pushes");
		await new Promise((r) => setImmediate(r));
		assert.deepEqual(got, [
			{ id: "5", topic: "fulfil:order:1", type: "order.updated", data: { v: 1 } },
			{ id: "6", topic: "fulfil:order:1", type: "order.updated", data: { v: 2 } },
		]);
		assert.equal(s.lastEventId, "6");
		s.stop();
	});

	it("drops resume overlap after a reconnect and sends Last-Event-ID", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(10, "fulfil:order:1"), pushEv(11, "fulfil:order:1")] });
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(11, "fulfil:order:1"), pushEv(12, "fulfil:order:1")], hold: true });
		const s = h.stream();
		const got = collect(s);
		s.start();
		await until(() => got.length === 3, "three pushes");
		assert.deepEqual(got.map((p) => p.id), ["10", "11", "12"]);
		assert.equal(h.gets.length, 2);
		assert.equal(h.gets[1]?.headers["Last-Event-ID"], "11");
		assert.equal(h.gets[1]?.url.searchParams.get("lastEventId"), "11");
		assert.equal(h.gets[1]?.url.searchParams.get("st"), "st1"); // same token: no resubscribe
		assert.equal(h.subscribes.length, 1);
		s.stop();
	});

	it("starts from a supplied lastEventId", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(40, "fulfil:order:1"), pushEv(41, "fulfil:order:1")], hold: true });
		const s = h.stream({ lastEventId: "40" });
		const got = collect(s);
		s.start();
		await until(() => got.length === 1, "one push");
		assert.equal(h.gets[0]?.headers["Last-Event-ID"], "40");
		assert.deepEqual(got.map((p) => p.id), ["41"]);
		s.stop();
	});

	it("compares ids beyond 2^53 exactly", async () => {
		const h = new Host();
		h.getScript.push({
			chunks: [subscribedEv([]), pushEv(0, "a:b"), "id: 1800000000000000001\nevent: t\ndata: {\"topic\":\"a:b\",\"data\":{}}\n\n", "id: 1800000000000000002\nevent: t\ndata: {\"topic\":\"a:b\",\"data\":{}}\n\n"],
			hold: true,
		});
		const s = h.stream({ lastEventId: "1800000000000000001" });
		const got = collect(s);
		s.start();
		await until(() => got.length === 1, "one push");
		assert.equal(got[0]?.id, "1800000000000000002");
		s.stop();
	});

	it("auth-expired resubscribes with fresh tickets and resumes with the last id", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(20, "fulfil:order:1"), sse(null, "auth-expired", {})] });
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(21, "fulfil:order:1")], hold: true });
		let n = 0;
		const s = h.stream({ getTickets: async () => [`tk${++n}`] });
		const got = collect(s);
		s.start();
		await until(() => got.length === 2, "two pushes");
		assert.equal(h.subscribes.length, 2);
		assert.deepEqual(h.subscribes.map((b) => b.tickets), [["tk1"], ["tk2"]]);
		assert.equal(h.gets[1]?.url.searchParams.get("st"), "st2");
		assert.equal(h.gets[1]?.url.searchParams.get("lastEventId"), "20");
		assert.equal(h.gets[1]?.headers["Last-Event-ID"], "20");
		assert.deepEqual(h.sleeps, []); // immediate
		s.stop();
	});

	it("a 401 on the stream GET subscribes again and resumes", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(30, "fulfil:order:1")] });
		h.getScript.push({ status: 401, body: JSON.stringify({ code: "STREAM_TOKEN_EXPIRED" }) });
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(31, "fulfil:order:1")], hold: true });
		const s = h.stream();
		const got = collect(s);
		s.start();
		await until(() => got.length === 2, "two pushes");
		assert.equal(h.subscribes.length, 2);
		assert.equal(h.gets[1]?.url.searchParams.get("st"), "st1");
		assert.equal(h.gets[2]?.url.searchParams.get("st"), "st2");
		assert.equal(h.gets[2]?.url.searchParams.get("lastEventId"), "30");
		s.stop();
	});

	it("surfaces reset and takes its latest as the last id, even when lower", async () => {
		const h = new Host();
		h.getScript.push({
			chunks: [subscribedEv([]), sse("3", "reset", { reason: "unknown-id", latest: 3 })],
		});
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(4, "fulfil:order:1")], hold: true });
		const s = h.stream({ lastEventId: "900" });
		const resets = events<{ reason: string; latest: string }>(s, "reset");
		const got = collect(s);
		s.start();
		await until(() => got.length === 1, "a push after the reset");
		assert.deepEqual(resets, [{ reason: "unknown-id", latest: "3" }]);
		assert.equal(h.gets[1]?.headers["Last-Event-ID"], "3");
		assert.equal(got[0]?.id, "4");
		s.stop();
	});

	it("surfaces denied topics", async () => {
		const h = new Host();
		h.subscribeScript.push(
			json(200, {
				streamToken: "stx",
				expiresAt: "2030-01-01T00:00:00Z",
				granted: ["a:ok"],
				denied: [{ topic: "a:no", code: "TOPIC_FORBIDDEN" }],
			}),
		);
		h.getScript.push({ chunks: [subscribedEv(["a:ok"])], hold: true });
		const s = h.stream({ topics: ["a:ok", "a:no"] });
		const denied = events<{ topic: string; code: string }[]>(s, "denied");
		s.start();
		await until(() => h.gets.length === 1, "the stream GET");
		assert.deepEqual(denied, [[{ topic: "a:no", code: "TOPIC_FORBIDDEN" }]]);
		s.stop();
	});

	it("stops on 403 NO_TOPICS and reports it", async () => {
		const h = new Host();
		h.subscribeScript.push(json(403, { code: "NO_TOPICS", message: "nothing granted" }));
		const s = h.stream();
		const errs = events<PushError>(s, "error");
		s.start();
		await until(() => s.state === "stopped" && errs.length === 1, "stopped");
		assert.equal(errs[0]?.code, "NO_TOPICS");
		assert.equal(errs[0]?.status, 403);
		await new Promise((r) => setImmediate(r));
		assert.equal(h.subscribes.length, 1);
		assert.equal(h.sleeps.length, 0);
	});

	it("reads the error code from the framework's {error, message} body", async () => {
		const h = new Host();
		h.subscribeScript.push(json(403, { error: "NO_TOPICS", message: "no topic was granted" }));
		const s = h.stream();
		const errs = events<PushError>(s, "error");
		s.start();
		await until(() => s.state === "stopped" && errs.length === 1, "stopped");
		assert.equal(errs[0]?.code, "NO_TOPICS");
		assert.equal(errs[0]?.message, "no topic was granted");
		const h2 = new Host();
		h2.subscribeScript.push(json(401, { error: "UNAUTHENTICATED", message: "needs a valid ticket" }), new TypeError("stop"));
		const s2 = h2.stream();
		const errs2 = events<PushError>(s2, "error");
		s2.start();
		await until(() => errs2.length >= 1, "an error");
		assert.equal(errs2[0]?.code, "UNAUTHENTICATED");
		assert.equal(errs2[0]?.status, 401);
		s2.stop();
	});

	it("backs off and retries other subscribe failures", async () => {
		const h = new Host();
		h.subscribeScript.push(json(429, { code: "PUSH_LIMIT" }), new TypeError("offline"), json(401, {}));
		h.getScript.push({ chunks: [subscribedEv([])], hold: true });
		const s = h.stream();
		const errs = events<PushError>(s, "error");
		s.start();
		await until(() => h.gets.length === 1, "stream GET");
		assert.deepEqual(errs.map((e) => e.code), ["PUSH_LIMIT", "NETWORK", "UNAUTHORIZED"]);
		assert.equal(h.sleeps.length, 3);
		s.stop();
	});

	it("backoff grows with repeated failures, is capped, and honours the server retry", async () => {
		const h = new Host();
		for (let i = 0; i < 12; i++) h.subscribeScript.push(json(500, {}));
		h.getScript.push({ chunks: [subscribedEv([])], hold: true });
		const s = h.stream({ random: () => 0.5 });
		s.start();
		await until(() => h.gets.length === 1, "stream GET");
		assert.deepEqual(h.sleeps.slice(0, 5), [3000, 6000, 12000, 24000, 48000]);
		assert.ok(h.sleeps.slice(5).every((ms) => ms === 60000), `capped: ${h.sleeps}`);
		s.stop();
	});

	it("applies jitter between 0.5x and 1.5x", async () => {
		for (const [r, want] of [
			[0, 1500],
			[0.999, 4497],
		] as const) {
			const h = new Host();
			h.subscribeScript.push(json(500, {}));
			h.getScript.push({ chunks: [subscribedEv([])], hold: true });
			const s = h.stream({ random: () => r });
			s.start();
			await until(() => h.gets.length === 1, "stream GET");
			assert.equal(h.sleeps[0], want);
			s.stop();
		}
	});

	it("uses the server retry as the base delay and resets failures once subscribed", async () => {
		const h = new Host();
		h.getScript.push({ chunks: ["retry: 1000\n\n", subscribedEv([])] });
		h.getScript.push({ chunks: ["retry: 1000\n\n", subscribedEv([])], hold: true });
		const s = h.stream();
		s.start();
		await until(() => h.gets.length === 2, "reconnect");
		assert.deepEqual(h.sleeps, [1000]);
		s.stop();
	});

	it("stop() aborts the in-flight request and does not reconnect", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([])], hold: true });
		const s = h.stream();
		const states = events<string>(s, "state");
		s.start();
		await until(() => s.state === "open", "open");
		s.stop();
		assert.equal(h.gets[0]?.signal.aborted, true);
		assert.equal(s.state, "stopped");
		for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r));
		assert.equal(h.gets.length, 1);
		assert.equal(h.subscribes.length, 1);
		assert.deepEqual(states, ["connecting", "open", "stopped"]);
	});

	it("stop() during a backoff sleep ends the loop", async () => {
		const h = new Host();
		let release!: () => void;
		h.subscribeScript.push(json(500, {}));
		const s = h.stream({
			sleep: (ms, signal) => {
				h.sleeps.push(ms);
				return new Promise<void>((resolve, reject) => {
					release = resolve;
					signal.addEventListener("abort", () => reject(new Error("aborted")));
				});
			},
		});
		s.start();
		await until(() => h.sleeps.length === 1, "sleeping");
		s.stop();
		release();
		for (let i = 0; i < 20; i++) await new Promise((r) => setImmediate(r));
		assert.equal(h.subscribes.length, 1);
		assert.equal(s.state, "stopped");
	});

	it("routes pushes to handlers by topic pattern, and unsubscribes", async () => {
		const h = new Host();
		h.getScript.push({
			chunks: [subscribedEv([]), pushEv(1, "fulfil:order:1"), pushEv(2, "fulfil:order:2"), pushEv(3, "fulfil:store:9:dock")],
			hold: true,
		});
		const s = h.stream();
		const exact: string[] = [];
		const star: string[] = [];
		const rest: string[] = [];
		const all: string[] = [];
		s.on("fulfil:order:1", (p) => exact.push(p.id));
		s.on("fulfil:order:*", (p) => star.push(p.id));
		const off = s.on("fulfil:**", (p) => rest.push(p.id));
		s.onAny((p) => all.push(p.id));
		off();
		s.start();
		await until(() => all.length === 3, "three pushes");
		assert.deepEqual(exact, ["1"]);
		assert.deepEqual(star, ["1", "2"]);
		assert.deepEqual(rest, []);
		s.stop();
	});

	it("a throwing handler is reported and does not stop delivery", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(1, "a:b"), pushEv(2, "a:b")], hold: true });
		const s = h.stream();
		const errs = events<PushError>(s, "error");
		const got: string[] = [];
		s.on("a:b", () => {
			throw new Error("boom");
		});
		s.on("a:b", (p) => got.push(p.id));
		s.start();
		await until(() => got.length === 2, "both pushes");
		assert.equal(errs.length, 2);
		assert.equal(errs[0]?.code, "HANDLER");
		s.stop();
	});

	it("setTopics resubscribes with the new set and keeps the last id", async () => {
		const h = new Host();
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(50, "a:b")], hold: true });
		h.getScript.push({ chunks: [subscribedEv([]), pushEv(51, "a:c")], hold: true });
		const s = h.stream({ topics: ["a:b"] });
		const got = collect(s);
		s.start();
		await until(() => got.length === 1, "first push");
		s.setTopics(["a:b", "a:c"]);
		await until(() => got.length === 2, "second push");
		assert.deepEqual(h.subscribes.map((b) => b.topics), [["a:b"], ["a:b", "a:c"]]);
		assert.equal(h.gets[0]?.signal.aborted, true);
		assert.equal(h.gets[1]?.url.searchParams.get("lastEventId"), "50");
		s.stop();
	});

	it("handles SSE chunks split at arbitrary points through the stream", async () => {
		const h = new Host();
		const text = subscribedEv([]) + pushEv(1, "a:b", { n: "x" }) + pushEv(2, "a:b", { n: "y" });
		h.getScript.push({ chunks: text.match(/[\s\S]{1,7}/g) ?? [], hold: true });
		const s = h.stream();
		const got = collect(s);
		s.start();
		await until(() => got.length === 2, "both pushes");
		assert.deepEqual(got.map((p) => p.data), [{ n: "x" }, { n: "y" }]);
		s.stop();
	});

	it("falls back to EventSource when the body is not streamable", async () => {
		const h = new Host();
		const fetchNoBody = async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
			if (String(input).includes("/push/subscriptions")) return h.fetch(input, init);
			return { status: 200, ok: true, body: null } as unknown as Response;
		};
		const made: { url: string; listeners: Map<string, (e: { data?: string; lastEventId?: string }) => void> }[] = [];
		class FakeES {
			listeners = new Map<string, (e: { data?: string; lastEventId?: string }) => void>();
			url: string;
			constructor(url: string) {
				this.url = url;
				made.push(this);
			}
			addEventListener(t: string, fn: (e: { data?: string; lastEventId?: string }) => void) {
				this.listeners.set(t, fn);
			}
			close() {}
		}
		const s = h.stream({ streamFetch: fetchNoBody, eventSource: FakeES, eventTypes: ["order.updated"] });
		const got = collect(s);
		s.start();
		await until(() => made.length === 1, "EventSource");
		made[0]?.listeners.get("subscribed")?.({ data: JSON.stringify({ granted: [], latest: 0 }) });
		made[0]?.listeners.get("order.updated")?.({ data: JSON.stringify({ topic: "a:b", data: { k: 1 } }), lastEventId: "8" });
		assert.deepEqual(got, [{ id: "8", topic: "a:b", type: "order.updated", data: { k: 1 } }]);
		made[0]?.listeners.get("error")?.({});
		await until(() => made.length === 2, "reconnect");
		assert.equal(new URL(made[1]?.url ?? "").searchParams.get("lastEventId"), "8");
		s.stop();
	});
});
