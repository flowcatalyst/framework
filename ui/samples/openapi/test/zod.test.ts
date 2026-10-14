// Run-time checks of the generated Zod 4 schemas (src/api/zod.gen.ts).
import { describe, expect, it } from "vitest";
import { config } from "zod";
import { zAuthLogout, zAuthSession, zIngestionBody, zSource } from "../src/api/zod.gen.ts";

config({ jitless: true });

const body = {
  code: "orders",
  name: "Orders",
  source: { kind: "http", path: "/in/orders" },
  retries: 3,
  tags: ["a"],
  labels: { team: "ops" },
  amount: "10.00",
  startsOn: "2026-10-14",
  timeout: "PT5M",
};

describe("generated zod schemas", () => {
  it("const becomes a literal", () => {
    expect(zAuthLogout.safeParse({ loggedOut: true }).success).toBe(true);
    expect(zAuthLogout.safeParse({ loggedOut: false }).success).toBe(false);
  });

  it("oneOf without a discriminator is a union decided by the const boolean", () => {
    expect(zAuthSession.parse({ authenticated: false, loginUrl: "/auth/login" })).toEqual({
      authenticated: false,
      loginUrl: "/auth/login",
    });
    expect(zAuthSession.safeParse({ authenticated: true, loginUrl: "/auth/login" }).success).toBe(false);
  });

  it("oneOf + discriminator is a discriminated union on the property", () => {
    expect(zSource.safeParse({ kind: "http", path: "/x" }).success).toBe(true);
    expect(zSource.safeParse({ kind: "http", query: "select 1", every: "PT1M" }).success).toBe(false);
    expect(zSource.safeParse({ kind: "ftp", path: "/x" }).success).toBe(false);
  });

  it("carries pattern, length and range constraints", () => {
    expect(zIngestionBody.safeParse(body).success).toBe(true);
    expect(zIngestionBody.safeParse({ ...body, code: "Orders" }).success).toBe(false);
    expect(zIngestionBody.safeParse({ ...body, name: "   " }).success).toBe(false);
    expect(zIngestionBody.safeParse({ ...body, retries: 11 }).success).toBe(false);
    expect(zIngestionBody.safeParse({ ...body, startsOn: "14/10/2026" }).success).toBe(false);
  });

  it("does NOT enforce additionalProperties: false or uniqueItems (recorded in docs/ui/u0.md)", () => {
    const parsed = zIngestionBody.safeParse({ ...body, extra: 1, tags: ["a", "a"] });
    expect(parsed.success).toBe(true);
    expect(parsed.data).not.toHaveProperty("extra");
  });
});
