// Zod and int64 (docs/ui/u2.md): hey-api writes `z.coerce.bigint()` for `format: int64` while the
// TypeScript type it writes is `number`, so a form value parsed by the schema becomes a BigInt and
// `JSON.stringify` refuses it. fwOpenApiConfig's number resolver keeps it a number. Proven on the
// orders example's `AddLineBody.unitPriceCents` (conformance/openapi/orders-demo.json); the
// generator itself is checked in generate.test.ts.
import { describe, expect, it } from "vitest";
import "../src/zod-config.ts";
import { configureClient } from "../src/client.ts";
import { client } from "./orders-demo/api/client.gen.ts";
import { addOrderLine } from "./orders-demo/api/sdk.gen.ts";
import type { AddLineBody } from "./orders-demo/api/types.gen.ts";
import { zAddLineBody } from "./orders-demo/api/zod.gen.ts";
import { fakeFetch } from "./fake-fetch.ts";

describe("int64 in the generated Zod schemas", () => {
  it("parses a form's unitPriceCents to a number that JSON.stringify serialises", () => {
    // What UForm hands to the submit handler: the schema's output.
    const form: AddLineBody = zAddLineBody.parse({ sku: "SKU-1", quantity: 2, unitPriceCents: 1999 });
    expect(typeof form.unitPriceCents).toBe("number");
    expect(JSON.stringify(form)).toBe('{"sku":"SKU-1","quantity":2,"unitPriceCents":1999}');
  });

  it("keeps the declared bounds and refuses fractions and unsafe integers", () => {
    expect(zAddLineBody.safeParse({ sku: "SKU-1", quantity: 1, unitPriceCents: -1 }).success).toBe(false);
    expect(zAddLineBody.safeParse({ sku: "SKU-1", quantity: 1, unitPriceCents: 100_000_001 }).success).toBe(false);
    expect(zAddLineBody.safeParse({ sku: "SKU-1", quantity: 1, unitPriceCents: 1.5 }).success).toBe(false);
    expect(zAddLineBody.safeParse({ sku: "SKU-1", quantity: 1, unitPriceCents: "1999" }).success).toBe(false);
  });

  it("posts the parsed form through the generated SDK as JSON", async () => {
    const fake = fakeFetch();
    fake.answer({ status: 200, json: {} });
    client.setConfig({ fetch: fake.fetch });
    configureClient(client, { onUnauthenticated: () => undefined, onFailure: () => undefined });
    const body = zAddLineBody.parse({ sku: "SKU-1", quantity: 2, unitPriceCents: 1999 });
    await addOrderLine({ path: { orderId: "ord_1" }, body });
    expect(fake.requests[0]?.body).toBe('{"sku":"SKU-1","quantity":2,"unitPriceCents":1999}');
    expect(new URL(fake.requests[0]?.url ?? "").pathname).toBe("/api/orders/ord_1/lines");
  });
});
