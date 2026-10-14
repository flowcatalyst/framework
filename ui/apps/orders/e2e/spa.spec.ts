// The demo's SPA serving (docs/contracts/static-assets-and-spa.md), seen from the browser's side:
// the strict policy on the shell and on its API answers' neighbours, caching, the fallback rules.
import { strictContentSecurityPolicy } from "@flowcatalyst/ui/csp";
import { expect, test } from "./fixtures.ts";

test("the shell is served with the strict policy the package's helper builds, revalidated by ETag", async ({ request }) => {
  const response = await request.get("/orders/some-client-route");
  expect(response.status()).toBe(200);
  expect(response.headers()["content-type"]).toBe("text/html; charset=utf-8");
  expect(response.headers()["content-security-policy"]).toBe(strictContentSecurityPolicy());
  expect(response.headers()["cache-control"]).toBe("no-cache");
  expect(response.headers()["x-content-type-options"]).toBe("nosniff");
  expect(response.headers()["x-frame-options"]).toBe("DENY");
  expect(response.headers()["cross-origin-opener-policy"]).toBe("same-origin");
  const etag = response.headers()["etag"];
  expect(etag).toBeTruthy();
  const again = await request.get("/", { headers: { "If-None-Match": etag ?? "" } });
  expect(again.status()).toBe(304);
});

test("hashed assets are immutable; a missing asset is a 404, never the shell", async ({ request }) => {
  const html = await (await request.get("/")).text();
  const script = /src="(\/assets\/[^"]+\.js)"/.exec(html)?.[1];
  expect(script).toBeTruthy();
  const asset = await request.get(script ?? "");
  expect(asset.status()).toBe(200);
  expect(asset.headers()["cache-control"]).toBe("public, max-age=31536000, immutable");
  expect(asset.headers()["content-type"]).toContain("text/javascript");

  const missing = await request.get("/assets/stale-chunk-abc123.js");
  expect(missing.status()).toBe(404);
  expect(missing.headers()["cache-control"]).toBe("no-store");
  const missingBody: unknown = await missing.json();
  expect(missingBody).toMatchObject({ error: "NOT_FOUND" });
});

test("an unknown API path is the JSON 404 and a POST to a client route is a 405", async ({ request }) => {
  const api = await request.get("/api/nothing");
  expect(api.status()).toBe(404);
  expect(api.headers()["content-type"]).toContain("application/json");
  expect((await request.post("/orders", { data: {} })).status()).toBe(405);
  // The API is not served to anonymous callers: 401 JSON, not a redirect and not the shell.
  const orders = await request.get("/api/orders");
  expect(orders.status()).toBe(401);
  expect(orders.headers()["content-type"]).toContain("application/json");
});
