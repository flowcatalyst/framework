// The routes, topics and payloads of go/examples/push-demo (its main.go), as this page uses them.
//
//   GET  /api/demo/ticket     {"ticket": "<JWS>"}: a ticket for any pushdemo:t:{id} topic (the demo's
//                             topic rule is public, so a subscribe without credentials needs one)
//   POST /api/demo/publish    {"items": [{"seq": n, "topic": "pushdemo:t:1"}], "pad": 0}
//   POST /api/push/subscriptions, GET /api/push/stream   the push module's own routes (base /api)
//
// A push has the type `demo.tick`; its data is {"seq": n, "ts": <unix nanoseconds>, "pad": "..."}.
// The demo has no read endpoint, so this page's rows are "the latest tick per topic", keyed by the
// topic, with `seq` as the version; the demo publishes whatever `seq` the caller gives.
import type { LiveChange, PushMessage } from "@flowcatalyst/ui/push";

export const BASE_URL = "/api";

export interface Tick {
  /** The topic, which is the row's id. */
  readonly id: string;
  readonly seq: number;
  /** Unix nanoseconds, stamped by the demo just before the write. */
  readonly ts: number;
}

export const topicFor = (n: number): string => `pushdemo:t:${String(n)}`;

/** The `toChange` for the demo's pushes: a `demo.tick` puts the topic's row; seq is its version. */
export function tickChange(push: PushMessage): LiveChange<Tick> | null {
  const data = push.data;
  if (push.type !== "demo.tick" || typeof data !== "object" || data === null) {
    return null;
  }
  const seq: unknown = "seq" in data ? data.seq : undefined;
  const ts: unknown = "ts" in data ? data.ts : undefined;
  if (typeof seq !== "number" || typeof ts !== "number") {
    return null;
  }
  return { kind: "put", row: { id: push.topic, seq, ts } };
}

/** The ticket endpoint's answer, as the page's `getTickets`. */
export async function fetchTickets(): Promise<string[]> {
  const res = await fetch(`${BASE_URL}/demo/ticket`, { credentials: "same-origin" });
  if (!res.ok) {
    throw new Error(`the ticket endpoint answered ${String(res.status)}`);
  }
  const body: unknown = await res.json();
  if (typeof body !== "object" || body === null || !("ticket" in body) || typeof body.ticket !== "string") {
    throw new Error("the ticket endpoint's answer has no ticket");
  }
  return [body.ticket];
}

/** Publishes one tick per topic with the given sequence numbers. */
export async function publish(items: readonly { readonly seq: number; readonly topic: string }[]): Promise<void> {
  const res = await fetch(`${BASE_URL}/demo/publish`, {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ items, pad: 0 }),
  });
  if (!res.ok) {
    throw new Error(`the publish endpoint answered ${String(res.status)}`);
  }
}
