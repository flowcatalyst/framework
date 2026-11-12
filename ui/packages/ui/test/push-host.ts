// A fake push host for the tests: a streaming `fetch` that answers the subscribe and the stream GET
// the way docs/contracts/push.md sections 6 and 7 describe, with no network. Each stream opens with
// `subscribed` and stays open; `send` writes to the newest open stream.
import { z } from "zod";
import { createPushHub, type PushHub, type PushHubOptions } from "../src/push/hub.ts";

const SubscribeBody = z.object({ topics: z.array(z.string()), tickets: z.array(z.string()) });

export const BASE = "https://app.test/api";

/** The URL text of a fetch input. */
export function urlOf(input: RequestInfo | URL): string {
  if (typeof input === "string") {
    return input;
  }
  return input instanceof URL ? input.href : input.url;
}

const enc = new TextEncoder();

export function sse(id: string | null, type: string, data: unknown): string {
  return `${id === null ? "" : `id: ${id}\n`}event: ${type}\ndata: ${JSON.stringify(data)}\n\n`;
}

export function pushEvent(id: number, topic: string, data: unknown, type = "row.upserted"): string {
  return sse(String(id), type, { topic, data });
}

export function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

export interface Get {
  readonly url: URL;
  readonly headers: Headers;
  readonly signal: AbortSignal;
  readonly controller: ReadableStreamDefaultController<Uint8Array>;
}

export type GetScript = { readonly status: number; readonly body?: string } | { readonly chunks: readonly string[]; readonly hold?: boolean };

export class Host {
  readonly subscribes: z.infer<typeof SubscribeBody>[] = [];
  readonly subscribeInits: (RequestInit | undefined)[] = [];
  readonly gets: Get[] = [];
  readonly subscribeScript: (Response | Error)[] = [];
  readonly getScript: GetScript[] = [];
  readonly sleeps: number[] = [];
  /** Topics the host refuses, with their codes, reported in `denied`. */
  denyTopics: readonly string[] = [];
  /** When set, a sleep (a reconnect delay) never ends. */
  hangSleeps = false;
  /** When set, new streams open WITHOUT the `subscribed` event until `confirm()` sends it. */
  withholdSubscribed = false;

  readonly fetch = (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(urlOf(input));
    if (url.pathname.endsWith("/push/subscriptions")) {
      const body = SubscribeBody.parse(JSON.parse(typeof init?.body === "string" ? init.body : ""));
      this.subscribes.push(body);
      this.subscribeInits.push(init);
      const next = this.subscribeScript.shift();
      if (next instanceof Error) {
        return Promise.reject(next);
      }
      if (next !== undefined) {
        return Promise.resolve(next);
      }
      const denied = body.topics.filter((t) => this.denyTopics.includes(t));
      return Promise.resolve(
        json(200, {
          streamToken: `st${String(this.subscribes.length)}`,
          expiresAt: "2030-01-01T00:00:00Z",
          granted: body.topics.filter((t) => !this.denyTopics.includes(t)),
          denied: denied.map((topic) => ({ topic, code: "TOPIC_FORBIDDEN" })),
        }),
      );
    }
    const signal = init?.signal;
    if (signal === undefined || signal === null) {
      return Promise.reject(new Error("stream GET without a signal"));
    }
    const script = this.getScript.shift();
    if (script !== undefined && "status" in script) {
      return Promise.resolve(new Response(script.body ?? "", { status: script.status }));
    }
    const chunks = script === undefined ? (this.withholdSubscribed ? [] : [sse(null, "subscribed", { granted: [], latest: 0 })]) : script.chunks;
    const hold = script === undefined ? true : script.hold === true;
    const stream = new ReadableStream<Uint8Array>({
      start: (controller) => {
        this.gets.push({ url, headers: new Headers(init?.headers), signal, controller });
        for (const c of chunks) {
          controller.enqueue(enc.encode(c));
        }
        if (!hold) {
          controller.close();
        }
        signal.addEventListener("abort", () => {
          try {
            controller.error(new DOMException("aborted", "AbortError"));
          } catch {
            // already closed
          }
        });
      },
    });
    return Promise.resolve(new Response(stream, { status: 200, headers: { "Content-Type": "text/event-stream" } }));
  };

  readonly sleep = (ms: number, signal: AbortSignal): Promise<void> => {
    this.sleeps.push(ms);
    if (signal.aborted) {
      return Promise.reject(new Error("aborted"));
    }
    if (this.hangSleeps) {
      return new Promise<void>((_, reject) => {
        signal.addEventListener("abort", () => {
          reject(new Error("aborted"));
        });
      });
    }
    return Promise.resolve();
  };

  options(over: Partial<PushHubOptions> = {}): PushHubOptions {
    return { baseUrl: BASE, fetch: this.fetch, streamFetch: this.fetch, sleep: this.sleep, random: () => 0.5, ...over };
  }

  hub(over: Partial<PushHubOptions> = {}): PushHub {
    return createPushHub(this.options(over));
  }

  /** The newest stream that has not been aborted. */
  private open(): Get {
    for (let i = this.gets.length - 1; i >= 0; i--) {
      const g = this.gets[i];
      if (g !== undefined && !g.signal.aborted) {
        return g;
      }
    }
    throw new Error("no open stream");
  }

  send(text: string): void {
    this.open().controller.enqueue(enc.encode(text));
  }

  /** Sends `subscribed` on the newest open stream. */
  confirm(): void {
    this.send(sse(null, "subscribed", { granted: [], latest: 0 }));
  }

  /** Ends the newest open stream from the server's side. */
  end(): void {
    this.open().controller.close();
  }
}

/** Waits (macrotasks) until `cond` holds. */
export async function until(cond: () => boolean, what = "condition"): Promise<void> {
  for (let i = 0; i < 500; i++) {
    if (cond()) {
      return;
    }
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
  throw new Error(`timed out waiting for ${what}`);
}

/** Lets pending microtasks and a few macrotasks run. */
export async function settle(rounds = 5): Promise<void> {
  for (let i = 0; i < rounds; i++) {
    await new Promise((resolve) => setTimeout(resolve, 0));
  }
}

export interface Deferred<T> {
  readonly promise: Promise<T>;
  resolve(value: T): void;
  reject(reason: unknown): void;
}

export function deferred<T>(): Deferred<T> {
  let resolve: (value: T) => void = () => undefined;
  let reject: (reason: unknown) => void = () => undefined;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}
