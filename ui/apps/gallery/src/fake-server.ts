// An in-page stand-in for the Java orders example (conformance/openapi/orders-demo.json), so the
// gallery's orders page runs the generated SDK, the configured client and the Zod schemas end to end
// without a backend. It is a gallery fixture, not a model of the server: it answers the shapes of the
// document and the error envelope of the http contract, and it reads request bodies with JSON.parse,
// so what the page sends must be real JSON (the int64 proof). Nothing leaves the page.
import type { AuthSession } from "@flowcatalyst/api-runtime";
import { shallowReactive } from "vue";
import type { OrderLineView, OrderSummaryView, OrderView } from "./api/orders-demo/types.gen.ts";

/** Every request the fake server answered, newest last (the gallery shows the last body). */
export const requests = shallowReactive<{ method: string; path: string; body: string }[]>([]);

const session: AuthSession = {
  authenticated: true,
  principal: { id: "prn_0HZXEQ5Y8JY5Z", kind: "USER", name: "Ada Lovelace", email: "ada@example.com" },
  tier: "CLIENT",
  client: "acme",
  clients: ["clt_1:acme"],
  applications: ["orders"],
  roles: ["orders:clerk"],
  permissions: ["orders:order:read", "orders:order:manage"],
  authTime: "2026-10-14T08:00:00Z",
  expiresAt: "2026-10-14T16:00:00Z",
  idleExpiresAt: "2026-10-14T08:30:00Z",
  csrf: { mode: "fetch-metadata", jsonBodies: true },
};

const CUSTOMERS = ["Acme", "Globex", "Initech", "Umbrella", "Hooli", "Stark", "Wayne", "Wonka"];
const SKUS = ["BOLT-M8", "NUT-M8", "WASHER-8", "PLATE-200", "HINGE-75"];

function line(order: number, index: number): OrderLineView {
  const quantity = 1 + ((order + index) % 5);
  const unitPriceCents = 150 + ((order * 37 + index * 11) % 900);
  return {
    lineId: `lin_${String(order)}_${String(index)}`,
    sku: SKUS[(order + index) % SKUS.length] ?? "BOLT-M8",
    quantity,
    unitPriceCents,
    totalCents: quantity * unitPriceCents,
  };
}

function total(lines: readonly OrderLineView[]): number {
  return lines.reduce((sum, l) => sum + l.totalCents, 0);
}

const orders: OrderView[] = Array.from({ length: 23 }, (_, i) => {
  const n = 1042 - i;
  const lines = Array.from({ length: i % 4 }, (_l, j) => line(n, j));
  return {
    id: `ord_${String(n)}`,
    customer: CUSTOMERS[i % CUSTOMERS.length] ?? "Acme",
    status: i % 6 === 5 ? "CANCELLED" : "OPEN",
    version: 1,
    lines,
    totalCents: total(lines),
  };
});

function summary(order: OrderView): OrderSummaryView {
  return {
    id: order.id,
    customer: order.customer,
    status: order.status,
    version: order.version,
    lineCount: order.lines.length,
    totalCents: order.totalCents,
    ...(order.lines[0] === undefined ? {} : { firstSku: order.lines[0].sku }),
  };
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function failure(status: number, error: string, message: string, details?: Record<string, string>): Response {
  return json(status, { error, message, ...(details === undefined ? {} : { details }) });
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

/** Reads a JSON body the way the framework does: JSON only (415), then parsed (400 INVALID_JSON). */
function readBody(request: Request, text: string): Record<string, unknown> | Response {
  if (request.headers.get("content-type") !== "application/json") {
    return failure(415, "UNSUPPORTED_MEDIA_TYPE", "a request body must be JSON");
  }
  try {
    const parsed: unknown = JSON.parse(text);
    return isRecord(parsed) ? parsed : failure(400, "INVALID_JSON", "the body must be a JSON object");
  } catch {
    return failure(400, "INVALID_JSON", "the body is not JSON");
  }
}

async function answer(request: Request): Promise<Response> {
  const url = new URL(request.url);
  const text = request.method === "GET" ? "" : await request.text();
  requests.push({ method: request.method, path: url.pathname, body: text });
  const path = url.pathname;

  if (path === "/auth/session" && request.method === "GET") {
    return json(200, session);
  }
  if (path === "/auth/logout" && request.method === "POST") {
    return json(200, { loggedOut: true });
  }
  if (path === "/api/orders" && request.method === "GET") {
    const status = url.searchParams.get("status");
    const q = (url.searchParams.get("q") ?? "").toLowerCase();
    const limit = Number(url.searchParams.get("limit") ?? "50");
    const after = Number(url.searchParams.get("after") ?? "0");
    const matching = orders.filter((o) => (status === null || o.status === status) && (q === "" || `${o.id} ${o.customer}`.toLowerCase().includes(q)));
    const items = matching.slice(after, after + limit).map(summary);
    const next = after + limit < matching.length ? String(after + limit) : undefined;
    return json(200, { items, ...(next === undefined ? {} : { nextCursor: next }) });
  }
  if (path === "/api/orders" && request.method === "POST") {
    const body = readBody(request, text);
    if (body instanceof Response) {
      return body;
    }
    const customer = body["customer"];
    if (typeof customer !== "string" || customer.trim() === "") {
      return failure(400, "VALIDATION", "request is invalid", { customer: "NOT_BLANK: must not be blank" });
    }
    const order: OrderView = { id: `ord_${String(2000 + orders.length)}`, customer, status: "OPEN", version: 1, lines: [], totalCents: 0 };
    orders.unshift(order);
    return json(201, order);
  }
  const match = /^\/api\/orders\/([^/]+)(\/lines|\/cancel)?$/.exec(path);
  const id = match?.[1];
  if (id === "fail-403") {
    return failure(403, "FORBIDDEN", "the session lacks orders:order:manage");
  }
  if (id === "fail-500") {
    return new Response("<html><body>Internal error</body></html>", { status: 500, headers: { "Content-Type": "text/html" } });
  }
  if (id === "fail-network") {
    throw new TypeError("Failed to fetch");
  }
  const order = orders.find((o) => o.id === id);
  if (match === null || id === undefined) {
    return failure(404, "NOT_FOUND", "no route for this path");
  }
  if (order === undefined) {
    return failure(404, "ORDER_NOT_FOUND", "there is no such order");
  }
  if (match[2] === undefined && request.method === "GET") {
    return json(200, order);
  }
  const body = readBody(request, text);
  if (body instanceof Response) {
    return body;
  }
  if (order.status !== "OPEN") {
    return failure(409, "ORDER_NOT_OPEN", "the order is cancelled and cannot change");
  }
  if (match[2] === "/cancel") {
    order.status = "CANCELLED";
    order.version += 1;
    return json(200, order);
  }
  // /lines: the body was read with JSON.parse, so a BigInt could never have arrived here.
  const sku = body["sku"];
  const quantity = body["quantity"];
  const unitPriceCents = body["unitPriceCents"];
  if (typeof sku !== "string" || typeof quantity !== "number" || typeof unitPriceCents !== "number") {
    return failure(400, "VALIDATION", "request is invalid", { unitPriceCents: "TYPE: must be a number" });
  }
  if (order.lines.some((l) => l.sku === sku)) {
    return failure(400, "VALIDATION", "request is invalid", { sku: "DUPLICATE: the order already has this SKU" });
  }
  order.lines = [...order.lines, { lineId: `lin_${String(Date.now())}`, sku, quantity, unitPriceCents, totalCents: quantity * unitPriceCents }];
  order.totalCents = total(order.lines);
  order.version += 1;
  return json(200, order);
}

/** A `fetch` for the generated clients: same-origin requests answered in the page. */
export const fakeFetch: typeof fetch = async (input, init) => {
  const request = input instanceof Request ? input : new Request(input, init);
  // A small delay, so busy states are visible and testable.
  await new Promise((resolve) => setTimeout(resolve, 120));
  return answer(request);
};
