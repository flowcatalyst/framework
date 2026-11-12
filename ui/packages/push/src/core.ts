// The push reader's logic, with no DOM: no EventTarget, no CustomEvent, and no TextDecoder unless the
// runtime has one. Events go out through the `emit` callback given to PushCore.
//
// This file is the source of the push reader core. @flowcatalyst/field-js and
// @flowcatalyst/field-react-native, in the platform repository, carry synced copies of it: edit it
// here, then copy it over theirs unchanged. It imports nothing so it can be copied as is.

/** Codes of a failed push subscribe (docs/contracts/push.md section 6.1), plus the library's own. */
export type PushErrorCode =
	| "NO_TOPICS"
	| "PUSH_LIMIT"
	| "VALIDATION"
	| "UNAUTHORIZED"
	| "STREAM_TOKEN_INVALID"
	| "STREAM_TOKEN_EXPIRED"
	| "NETWORK"
	| "BAD_RESPONSE"
	| "HANDLER"
	| (string & {});

/** An error from the push reader. */
export class PushError extends Error {
	readonly code: PushErrorCode;
	/** HTTP status when the host answered. */
	readonly status?: number;
	readonly cause?: unknown;

	constructor(code: PushErrorCode, message?: string, extra: { status?: number; cause?: unknown } = {}) {
		super(message ?? code);
		this.name = "PushError";
		this.code = code;
		if (extra.status !== undefined) this.status = extra.status;
		if (extra.cause !== undefined) this.cause = extra.cause;
	}
}

// Reader for the push stream (docs/contracts/push.md sections 6 and 7):
// subscribe with the caller's credentials, listen over SSE, keep the last id,
// reconnect and resume.

/** One push, as delivered to handlers. `id` is the log id (64-bit, so a string). */
export interface PushMessage {
	id: string;
	topic: string;
	type: string;
	data: unknown;
}

export type PushStreamState = "connecting" | "open" | "reconnecting" | "stopped";

export type PushStreamEventType = "subscribed" | "reset" | "denied" | "error" | "state";

export interface SubscribedDetail {
	granted: string[];
	latest: string;
}

export interface ResetDetail {
	reason: "retention" | "unknown-id" | (string & {});
	latest: string;
}

export interface DeniedTopic {
	topic: string;
	code: string;
}

export interface PushStreamOptions {
	/** The host's base URL; `/push/subscriptions` and `/push/stream` hang off it. */
	baseUrl: string;
	topics: string[];
	/** Used for the subscribe call: `auth.fetch.bind(auth)` in a field app, `globalThis.fetch` on a web page. */
	fetch: typeof fetch;
	/** Used for the stream GET (the token is the capability). Default: `globalThis.fetch`. */
	streamFetch?: typeof fetch;
	/** Mints tickets for the topics, from the application's own endpoint. */
	getTickets?: (topics: string[]) => Promise<string[]>;
	/** Resume from this id, e.g. one persisted before a restart. */
	lastEventId?: string;
	/** Needed only for the EventSource fallback: EventSource cannot listen to unnamed event types. */
	eventTypes?: string[];
	/** Constructor used when response bodies are not streamable. Default: `globalThis.EventSource`. */
	eventSource?: new (url: string) => EventSourceLike;
	/** Jitter source, in [0, 1). */
	random?: () => number;
	/** Waits `ms`; rejects when `signal` aborts. */
	sleep?: (ms: number, signal: AbortSignal) => Promise<void>;
}

/** The part of EventSource the fallback uses. */
export interface EventSourceLike {
	addEventListener(type: string, listener: (e: { data?: string; lastEventId?: string }) => void): void;
	close(): void;
}

export const DEFAULT_RETRY_MS = 3000;
export const MAX_BACKOFF_MS = 60_000;

type Handler = (push: PushMessage) => void;

// ── SSE parser ───────────────────────────────────────────────────────

export interface SseMessage {
	event: string;
	data: string;
	id: string | undefined;
}

/** Incremental SSE parser: feed it text in any chunking. */
export class SseParser {
	private buf = "";
	private started = false;
	private data: string[] = [];
	private event = "";
	private id: string | undefined;
	private readonly onRetry: (ms: number) => void;

	constructor(onRetry: (ms: number) => void = () => {}) {
		this.onRetry = onRetry;
	}

	feed(text: string): SseMessage[] {
		this.buf += text;
		if (!this.started && this.buf.length > 0) {
			this.started = true;
			if (this.buf.charCodeAt(0) === 0xfeff) this.buf = this.buf.slice(1);
		}
		const out: SseMessage[] = [];
		let pos = 0;
		for (;;) {
			let end = -1;
			let skip = 1;
			for (let i = pos; i < this.buf.length; i++) {
				const c = this.buf[i];
				if (c === "\n") {
					end = i;
					break;
				}
				if (c === "\r") {
					// A trailing CR may be the first half of CRLF: wait for more.
					if (i === this.buf.length - 1) break;
					end = i;
					if (this.buf[i + 1] === "\n") skip = 2;
					break;
				}
			}
			if (end < 0) break;
			const m = this.line(this.buf.slice(pos, end));
			if (m) out.push(m);
			pos = end + skip;
		}
		this.buf = this.buf.slice(pos);
		return out;
	}

	private line(line: string): SseMessage | null {
		if (line === "") {
			const had = this.data.length > 0;
			const m = had ? { event: this.event || "message", data: this.data.join("\n"), id: this.id } : null;
			this.data = [];
			this.event = "";
			this.id = undefined;
			return m;
		}
		if (line.startsWith(":")) return null;
		const colon = line.indexOf(":");
		const field = colon < 0 ? line : line.slice(0, colon);
		let value = colon < 0 ? "" : line.slice(colon + 1);
		if (value.startsWith(" ")) value = value.slice(1);
		switch (field) {
			case "data":
				this.data.push(value);
				break;
			case "event":
				this.event = value;
				break;
			case "id":
				if (!value.includes("\0")) this.id = value;
				break;
			case "retry":
				if (/^\d+$/.test(value)) this.onRetry(Number(value));
				break;
		}
		return null;
	}
}

// ── UTF-8 ────────────────────────────────────────────────────────────

/** Decodes a byte stream to text; a character split across chunks is held until it is complete. */
export interface Utf8Decoder {
	decode(chunk: Uint8Array): string;
}

/**
 * Streaming UTF-8 decoder for runtimes with no TextDecoder. Invalid bytes become U+FFFD, as TextDecoder
 * does (a lead byte with the continuation bytes seen so far is one U+FFFD); a sequence cut off by the end
 * of a chunk waits for the next one.
 */
export class Utf8StreamDecoder implements Utf8Decoder {
	private pending: Uint8Array = new Uint8Array(0);

	decode(chunk: Uint8Array): string {
		let b = chunk;
		if (this.pending.length > 0) {
			b = new Uint8Array(this.pending.length + chunk.length);
			b.set(this.pending, 0);
			b.set(chunk, this.pending.length);
			this.pending = new Uint8Array(0);
		}
		const n = b.length;
		let out = "";
		let i = 0;
		while (i < n) {
			const c = b[i] ?? 0;
			if (c < 0x80) {
				out += String.fromCharCode(c);
				i++;
				continue;
			}
			let need = 0;
			let cp = 0;
			let lo = 0x80;
			let hi = 0xbf;
			if (c >= 0xc2 && c <= 0xdf) {
				need = 1;
				cp = c & 0x1f;
			} else if (c >= 0xe0 && c <= 0xef) {
				need = 2;
				cp = c & 0x0f;
				if (c === 0xe0) lo = 0xa0;
				if (c === 0xed) hi = 0x9f;
			} else if (c >= 0xf0 && c <= 0xf4) {
				need = 3;
				cp = c & 0x07;
				if (c === 0xf0) lo = 0x90;
				if (c === 0xf4) hi = 0x8f;
			} else {
				out += "\ufffd";
				i++;
				continue;
			}
			let j = i + 1;
			let got = 0;
			let ok = true;
			for (; got < need && j < n; got++, j++) {
				const x = b[j] ?? 0;
				if (x < lo || x > hi) {
					ok = false;
					break;
				}
				lo = 0x80;
				hi = 0xbf;
				cp = (cp << 6) | (x & 0x3f);
			}
			if (!ok) {
				// The offending byte is looked at again as the start of whatever follows.
				out += "\ufffd";
				i = j;
				continue;
			}
			if (got < need) {
				this.pending = b.slice(i);
				break;
			}
			out += String.fromCodePoint(cp);
			i = j;
		}
		return out;
	}
}

/** TextDecoder when the runtime has one, else the built-in decoder. */
export function createUtf8Decoder(): Utf8Decoder {
	const TD = (globalThis as { TextDecoder?: new () => { decode(b?: Uint8Array, o?: { stream?: boolean }): string } }).TextDecoder;
	if (!TD) return new Utf8StreamDecoder();
	const td = new TD();
	return { decode: (chunk) => td.decode(chunk, { stream: true }) };
}

// ── topic patterns ───────────────────────────────────────────────────

/**
 * Whether `pattern` matches `topic`. Segments split on `:`; `*` matches one
 * segment, a final `**` matches one or more. Anything else is literal.
 */
export function topicMatches(pattern: string, topic: string): boolean {
	const p = pattern.split(":");
	const t = topic.split(":");
	for (let i = 0; i < p.length; i++) {
		const seg = p[i];
		if (seg === "**" && i === p.length - 1) return t.length > i;
		if (i >= t.length) return false;
		if (seg !== "*" && seg !== t[i]) return false;
	}
	return p.length === t.length;
}

function defaultSleep(ms: number, signal: AbortSignal): Promise<void> {
	return new Promise((resolve, reject) => {
		if (signal.aborted) return reject(new Error("aborted"));
		const t = setTimeout(() => {
			signal.removeEventListener("abort", onAbort);
			resolve();
		}, ms);
		const onAbort = () => {
			clearTimeout(t);
			reject(new Error("aborted"));
		};
		signal.addEventListener("abort", onAbort, { once: true });
	});
}

function idGreater(a: string, b: string): boolean {
	try {
		return BigInt(a) > BigInt(b);
	} catch {
		return false;
	}
}

/** Why one listen ended. */
type ListenEnd = "ended" | "auth-expired" | "token-invalid" | "failed";

/** Where PushCore sends its events: `subscribed`, `reset`, `denied`, `error` and `state`. */
export type PushEmit = (type: PushStreamEventType, detail: unknown) => void;

export class PushCore {
	private readonly baseUrl: string;
	private topicList: string[];
	private readonly fetchImpl: typeof fetch;
	private readonly streamFetch: typeof fetch;
	private readonly getTickets: ((topics: string[]) => Promise<string[]>) | undefined;
	private readonly eventTypes: string[];
	private readonly eventSourceCtor: (new (url: string) => EventSourceLike) | undefined;
	private readonly random: () => number;
	private readonly sleep: (ms: number, signal: AbortSignal) => Promise<void>;
	private readonly handlers = new Set<{ pattern: string | null; fn: Handler }>();
	private last: string;
	private token: string | null = null;
	private retryMs = DEFAULT_RETRY_MS;
	private failures = 0;
	private expiryStreak = 0;
	private useEventSource = false;
	private gen = 0;
	private ctl: AbortController | null = null;
	private current: PushStreamState = "stopped";

	private readonly sink: PushEmit;

	constructor(opts: PushStreamOptions, emit: PushEmit) {
		this.sink = emit;
		this.baseUrl = opts.baseUrl.replace(/\/+$/, "");
		this.topicList = [...opts.topics];
		this.fetchImpl = opts.fetch;
		this.streamFetch = opts.streamFetch ?? ((...a) => globalThis.fetch(...a));
		this.getTickets = opts.getTickets;
		this.eventTypes = opts.eventTypes ?? [];
		this.eventSourceCtor =
			opts.eventSource ?? (globalThis as { EventSource?: new (url: string) => EventSourceLike }).EventSource;
		this.random = opts.random ?? Math.random;
		this.sleep = opts.sleep ?? defaultSleep;
		this.last = opts.lastEventId ?? "";
	}

	/** The id of the last push delivered (or reset seen); persist it and pass it back after a restart. */
	get lastEventId(): string | undefined {
		return this.last === "" ? undefined : this.last;
	}

	get state(): PushStreamState {
		return this.current;
	}

	get topics(): readonly string[] {
		return this.topicList;
	}

	/** Listens for pushes on a topic or pattern (`*` one segment, final `**` the rest). Returns an unsubscribe function. */
	on(pattern: string, fn: Handler): () => void {
		const h = { pattern, fn };
		this.handlers.add(h);
		return () => this.handlers.delete(h);
	}

	/** Listens for every push. Returns an unsubscribe function. */
	onAny(fn: Handler): () => void {
		const h = { pattern: null, fn };
		this.handlers.add(h);
		return () => this.handlers.delete(h);
	}

	start(): void {
		if (this.current !== "stopped") return;
		this.launch();
	}

	stop(): void {
		this.gen++;
		this.ctl?.abort();
		this.ctl = null;
		this.token = null;
		this.setState("stopped");
	}

	/** Subscribes again with a new topic set; the last id is kept. */
	setTopics(topics: string[]): void {
		this.topicList = [...topics];
		if (this.current !== "stopped") this.launch();
	}

	private launch(): void {
		this.gen++;
		this.ctl?.abort();
		this.token = null;
		this.failures = 0;
		this.expiryStreak = 0;
		const gen = this.gen;
		const ctl = new AbortController();
		this.ctl = ctl;
		this.setState("connecting");
		void this.run(gen, ctl);
	}

	private emit<T>(type: PushStreamEventType, detail: T): void {
		this.sink(type, detail);
	}

	private setState(s: PushStreamState): void {
		if (s === this.current) return;
		this.current = s;
		this.emit("state", s);
	}

	private fail(err: unknown): void {
		this.emit("error", err instanceof PushError ? err : new PushError("NETWORK", String(err), { cause: err }));
	}

	// ── the loop ─────────────────────────────────────────────────────

	private async run(gen: number, ctl: AbortController): Promise<void> {
		const live = () => this.gen === gen && !ctl.signal.aborted;
		while (live()) {
			let wait: number | null = null; // null: reconnect after the server's retry
			try {
				if (this.token === null) {
					this.setState("connecting");
					await this.subscribe(ctl.signal);
					if (!live()) return;
				}
				this.setState("connecting");
				const end = await this.listen(ctl, live);
				if (!live()) return;
				if (end === "auth-expired") {
					this.token = null;
					this.expiryStreak++;
					if (this.expiryStreak > 1) {
						this.failures = this.expiryStreak - 1;
					} else {
						wait = 0;
					}
				} else if (end === "token-invalid") {
					this.token = null;
					this.failures++;
					if (this.failures <= 1) wait = 0;
				} else if (end === "failed") {
					this.failures++;
				}
			} catch (err) {
				if (!live()) return;
				this.failures++;
				this.fail(err);
				if (err instanceof PushError && err.code === "NO_TOPICS") {
					this.stop();
					return;
				}
			}
			if (!live()) return;
			const ms = wait ?? this.backoff();
			this.setState("reconnecting");
			if (ms > 0) {
				try {
					await this.sleep(ms, ctl.signal);
				} catch {
					return;
				}
			}
		}
	}

	private backoff(): number {
		const exp = this.retryMs * 2 ** Math.max(0, Math.min(this.failures - 1, 16));
		return Math.min(MAX_BACKOFF_MS, Math.round(exp * (0.5 + this.random())));
	}

	// ── subscribe ────────────────────────────────────────────────────

	private async subscribe(signal: AbortSignal): Promise<void> {
		const topics = [...this.topicList];
		const tickets = this.getTickets ? await this.getTickets(topics) : [];
		let res: Response;
		try {
			res = await this.fetchImpl(`${this.baseUrl}/push/subscriptions`, {
				method: "POST",
				headers: { "Content-Type": "application/json", Accept: "application/json" },
				body: JSON.stringify({ topics, tickets }),
				signal,
			});
		} catch (err) {
			throw new PushError("NETWORK", "subscribe failed", { cause: err });
		}
		if (!res.ok) throw await errorFrom(res);
		let body: { streamToken?: string; granted?: string[]; denied?: DeniedTopic[] };
		try {
			body = (await res.json()) as typeof body;
		} catch (err) {
			throw new PushError("BAD_RESPONSE", "subscribe answer is not JSON", { status: res.status, cause: err });
		}
		if (typeof body.streamToken !== "string" || body.streamToken === "") {
			throw new PushError("BAD_RESPONSE", "subscribe answer has no streamToken", { status: res.status });
		}
		this.token = body.streamToken;
		if (Array.isArray(body.denied) && body.denied.length > 0) this.emit("denied", body.denied);
	}

	// ── listen ───────────────────────────────────────────────────────

	private streamUrl(): string {
		let url = `${this.baseUrl}/push/stream?st=${encodeURIComponent(this.token ?? "")}`;
		if (this.last !== "") url += `&lastEventId=${encodeURIComponent(this.last)}`;
		return url;
	}

	private async listen(ctl: AbortController, live: () => boolean): Promise<ListenEnd> {
		if (this.useEventSource) return this.listenEventSource(ctl, live);
		const headers: Record<string, string> = { Accept: "text/event-stream" };
		if (this.last !== "") headers["Last-Event-ID"] = this.last;
		let res: Response;
		try {
			res = await this.streamFetch(this.streamUrl(), { method: "GET", headers, signal: ctl.signal });
		} catch (err) {
			if (!live()) return "ended";
			this.fail(new PushError("NETWORK", "stream request failed", { cause: err }));
			return "failed";
		}
		if (res.status === 401) {
			await res.body?.cancel().catch(() => {});
			return "token-invalid";
		}
		if (!res.ok) {
			const err = await errorFrom(res);
			this.fail(err);
			return "failed";
		}
		const reader = res.body?.getReader?.();
		if (!reader) {
			// Not streamable here: use EventSource from now on.
			await res.body?.cancel?.().catch(() => {});
			if (this.eventSourceCtor) {
				this.useEventSource = true;
				return this.listenEventSource(ctl, live);
			}
			this.fail(new PushError("NETWORK", "response bodies are not streamable and there is no EventSource"));
			return "failed";
		}
		const dec = createUtf8Decoder();
		const parser = new SseParser((ms) => {
			this.retryMs = ms;
		});
		let opened = false;
		let end: ListenEnd = "ended";
		try {
			read: for (;;) {
				const { done, value } = await reader.read();
				if (done) break;
				for (const m of parser.feed(dec.decode(value))) {
					if (!live()) return "ended";
					const r = this.handle(m);
					if (r === "subscribed") opened = true;
					if (r === "auth-expired") {
						end = "auth-expired";
						break read;
					}
				}
			}
		} catch (err) {
			if (!live()) return "ended";
			this.fail(new PushError("NETWORK", "stream read failed", { cause: err }));
			end = "failed";
		} finally {
			reader.cancel().catch(() => {});
		}
		if (end === "ended" && !opened) return "failed";
		return end;
	}

	private listenEventSource(ctl: AbortController, live: () => boolean): Promise<ListenEnd> {
		const Ctor = this.eventSourceCtor;
		if (!Ctor) return Promise.resolve("failed");
		return new Promise<ListenEnd>((resolve) => {
			const es = new Ctor(this.streamUrl());
			let opened = false;
			const finish = (r: ListenEnd) => {
				ctl.signal.removeEventListener("abort", onAbort);
				es.close();
				resolve(r);
			};
			const onAbort = () => finish("ended");
			ctl.signal.addEventListener("abort", onAbort, { once: true });
			const types = ["subscribed", "reset", "auth-expired", ...this.eventTypes];
			for (const type of types) {
				es.addEventListener(type, (e) => {
					if (!live()) return finish("ended");
					const r = this.handle({ event: type, data: e.data ?? "", id: e.lastEventId || undefined });
					if (r === "subscribed") opened = true;
					if (r === "auth-expired") finish("auth-expired");
				});
			}
			// EventSource cannot tell us the status: an error before `subscribed` means the token is bad.
			es.addEventListener("error", () => finish(opened ? "ended" : "token-invalid"));
		});
	}

	// ── one SSE message ──────────────────────────────────────────────

	private handle(m: SseMessage): "subscribed" | "auth-expired" | null {
		switch (m.event) {
			case "subscribed": {
				const d = this.json<{ granted?: string[]; latest?: number | string }>(m);
				if (!d) return null;
				this.failures = 0;
				this.setState("open");
				this.emit("subscribed", { granted: d.granted ?? [], latest: String(d.latest ?? "") } satisfies SubscribedDetail);
				return "subscribed";
			}
			case "reset": {
				const d = this.json<{ reason?: string; latest?: number | string }>(m);
				if (!d) return null;
				const latest = String(d.latest ?? m.id ?? "");
				// The reset id replaces ours, even when lower (unknown-id).
				if (latest !== "") this.last = latest;
				this.emit("reset", { reason: d.reason ?? "", latest } satisfies ResetDetail);
				return null;
			}
			case "auth-expired":
				return "auth-expired";
			default:
				this.push(m);
				return null;
		}
	}

	private push(m: SseMessage): void {
		if (m.id === undefined || m.id === "") return;
		// Resume overlap: an id we already delivered is dropped.
		if (this.last !== "" && !idGreater(m.id, this.last)) return;
		const d = this.json<{ topic?: string; data?: unknown }>(m);
		if (!d || typeof d.topic !== "string") return;
		this.last = m.id;
		this.expiryStreak = 0;
		const msg: PushMessage = { id: m.id, topic: d.topic, type: m.event, data: d.data };
		for (const h of [...this.handlers]) {
			if (h.pattern !== null && !topicMatches(h.pattern, msg.topic)) continue;
			try {
				h.fn(msg);
			} catch (err) {
				this.emit("error", new PushError("HANDLER", "a push handler threw", { cause: err }));
			}
		}
	}

	private json<T>(m: SseMessage): T | null {
		try {
			return JSON.parse(m.data) as T;
		} catch (err) {
			this.emit("error", new PushError("BAD_RESPONSE", `bad ${m.event} event data`, { cause: err }));
			return null;
		}
	}
}

async function errorFrom(res: Response): Promise<PushError> {
	let code: string | undefined;
	let message: string | undefined;
	try {
		// The framework answers {"error": "<CODE>", "message": "..."}; {"code": ...} and {"error": {"code": ...}} are read too.
		const b = (await res.json()) as { code?: unknown; message?: unknown; error?: string | { code?: unknown; message?: unknown } };
		const e = typeof b.error === "object" && b.error !== null ? b.error : undefined;
		const c = b.code ?? (typeof b.error === "string" ? b.error : e?.code);
		const msg = b.message ?? e?.message;
		if (typeof c === "string") code = c;
		if (typeof msg === "string") message = msg;
	} catch {
		// no JSON body
	}
	code ??= res.status === 401 ? "UNAUTHORIZED" : res.status === 403 ? "NO_TOPICS" : res.status === 429 ? "PUSH_LIMIT" : res.status === 400 ? "VALIDATION" : `HTTP_${res.status}`;
	return new PushError(code, message ?? `${code} (HTTP ${res.status})`, { status: res.status });
}
