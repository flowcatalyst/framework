// Zod writes an optional property as `?: T | undefined`; hey-api writes the TypeScript type as `?: T`.
// Under exactOptionalPropertyTypes the first is not assignable to the second, which is why an
// application cast a validated form value (docs/ui/u3.md, "Camel feedback 2026-10-16", item a).
// `exactOutput` is that one narrowing, written once, tested, and honest at run time.
import { describe, expect, expectTypeOf, it } from "vitest";
import "../src/zod-config.ts";
import { exactOutput } from "../src/exact-output.ts";
import type { ListOrdersData } from "./orders-demo/api/types.gen.ts";
import { zListOrdersQuery } from "./orders-demo/api/zod.gen.ts";

type Query = NonNullable<ListOrdersData["query"]>;

describe("exactOutput", () => {
  it("makes a parsed value assignable to the generated type without a cast", () => {
    const parsed = zListOrdersQuery.parse({ status: "OPEN" });
    // @ts-expect-error Zod's output `status?: ... | undefined` is not the generated `status?: ...`
    const direct: Query = parsed;
    expect(direct).toBeDefined();
    const query: Query = exactOutput(parsed);
    expect(query).toEqual({ status: "OPEN", limit: 50 });
    expectTypeOf(exactOutput(parsed)).toExtend<Query>();
  });

  it("drops undefined properties, deeply, and keeps everything else", () => {
    const value = { a: 1, b: undefined, c: { d: undefined, e: [1, { f: undefined, g: "x" }], h: null } };
    const out = exactOutput(value);
    expect(out).toStrictEqual({ a: 1, c: { e: [1, { g: "x" }], h: null } });
    expect("b" in out).toBe(false);
    expect("d" in out.c).toBe(false);
  });

  it("leaves Dates, class instances and primitives alone", () => {
    const when = new Date(0);
    expect(exactOutput({ when }).when).toBe(when);
    expect(exactOutput("x")).toBe("x");
    expect(exactOutput(null)).toBeNull();
    expect(exactOutput(3)).toBe(3);
  });

  it("serialises like the original (JSON.stringify drops undefined too)", () => {
    const value = { a: 1, b: undefined, c: { d: undefined } };
    expect(JSON.stringify(exactOutput(value))).toBe(JSON.stringify(value));
  });
});
