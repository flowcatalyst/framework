// configureClient against a generated client (the orders example's, from
// conformance/openapi/orders-demo.json): sessions contract section 4.5, client rules 1, 2 and 4.
import { beforeEach, describe, expect, it } from "vitest";
import { configureClient, type RequestFailure } from "../src/client.ts";
import { toServiceError } from "../src/errors.ts";
import { client } from "./orders-demo/api/client.gen.ts";
import { addOrderLine, listOrders, logout, placeOrder } from "./orders-demo/api/sdk.gen.ts";
import { fakeFetch, type FakeFetch } from "./fake-fetch.ts";

let fake: FakeFetch;
let unauthenticated: number;
let failures: RequestFailure[];

beforeEach(() => {
  fake = fakeFetch();
  unauthenticated = 0;
  failures = [];
  client.interceptors.response.clear();
  client.interceptors.error.clear();
  client.setConfig({ fetch: fake.fetch });
  configureClient(client, {
    onUnauthenticated: () => {
      unauthenticated += 1;
    },
    onFailure: (failure) => failures.push(failure),
  });
});

const page = { items: [], hasMore: false };

describe("configureClient", () => {
  it("calls the page's own origin with the cookie only: no Authorization, no Cookie header, no CSRF header", async () => {
    fake.answer({ status: 200, json: page });
    const { data } = await listOrders({ query: { status: "OPEN" } });
    expect(data).toEqual(page);
    const [request] = fake.requests;
    expect(request?.url).toBe("http://app.example.test/api/orders?status=OPEN");
    expect(request?.credentials).toBe("same-origin");
    const names = [...(request?.headers.keys() ?? [])];
    expect(names.filter((name) => /authorization|cookie|csrf|xsrf/i.test(name))).toEqual([]);
  });

  it("sends JSON bodies with Content-Type application/json", async () => {
    fake.answer({ status: 201, json: { id: "ord_1" } });
    await placeOrder({ body: { customer: "Acme" } });
    const [request] = fake.requests;
    expect(request?.method).toBe("POST");
    expect(request?.headers.get("content-type")).toBe("application/json");
    expect(JSON.parse(request?.body ?? "")).toEqual({ customer: "Acme" });
  });

  it("sends a body-less POST (logout) without a Content-Type", async () => {
    fake.answer({ status: 200, json: { loggedOut: true } });
    await logout();
    expect(fake.requests[0]?.headers.has("content-type")).toBe(false);
    expect(fake.requests[0]?.body).toBe("");
  });

  it("returns the envelope as a typed error and does not report 4xx answers the caller handles", async () => {
    const envelope = { error: "VALIDATION", message: "request is invalid", details: { sku: "PATTERN: must match" } };
    fake.answer({ status: 400, json: envelope });
    const { data, error, response } = await addOrderLine({
      path: { orderId: "ord_1" },
      body: { sku: "bad sku", quantity: 1, unitPriceCents: 100 },
    });
    expect(data).toBeUndefined();
    expect(error).toEqual(envelope);
    expect(toServiceError(error, response)).toEqual({ status: 400, ...envelope });
    expect(failures).toEqual([]);
    expect(unauthenticated).toBe(0);
  });

  it("turns a 401 into the sign-in navigation (rule 4)", async () => {
    fake.answer({ status: 401, json: { error: "UNAUTHENTICATED", message: "sign in" } });
    await listOrders();
    expect(unauthenticated).toBe(1);
    expect(failures).toEqual([]);
  });

  it("reports a 403 as refused, a 5xx as failed and a network failure as offline; an abort is not reported", async () => {
    fake.answer(
      { status: 403, json: { error: "FORBIDDEN", message: "no" } },
      { status: 502, text: "<html>Bad gateway</html>" },
      "network-error",
      "abort",
    );
    const refused = await listOrders();
    const failed = await listOrders();
    const offline = await listOrders();
    await listOrders();
    expect(failures.map((f) => [f.kind, f.status, f.request])).toEqual([
      ["refused", 403, "GET /api/orders"],
      ["failed", 502, "GET /api/orders"],
      ["offline", 0, "request"],
    ]);
    expect(toServiceError(refused.error, refused.response).error).toBe("FORBIDDEN");
    expect(toServiceError(failed.error, failed.response)).toMatchObject({ status: 502, error: "HTTP_502" });
    expect(toServiceError(offline.error, offline.response)).toMatchObject({ status: 0, error: "NETWORK" });
  });
});
