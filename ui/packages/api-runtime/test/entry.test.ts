// The package entry owns Zod's CSP setting (docs/ui/u0.md: Zod 4 probes `new Function("")` the first
// time an object schema is built, which the strict CSP reports as a violation).
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, it } from "vitest";
import { config } from "zod";
import * as runtime from "../src/index.ts";

it("importing the package sets Zod to jitless", () => {
  expect(runtime.zodJitless).toBe(true);
  expect(config().jitless).toBe(true);
});

it("the entry imports the Zod setting before anything that builds a schema", () => {
  const source = readFileSync(join(import.meta.dirname, "../src/index.ts"), "utf8");
  const imports = [...source.matchAll(/^(?:import|export)[^;]*?from "([^"]+)"|^import "([^"]+)"/gm)].map((m) => m[1] ?? m[2]);
  expect(imports[0]).toBe("./zod-config.ts");
});

it("exports the generated auth operations and the envelope under names that do not shadow Error", () => {
  expect(typeof runtime.getAuthSession).toBe("function");
  expect(typeof runtime.logout).toBe("function");
  expect("Error" in runtime).toBe(false);
});

it("importing the package gives form fields messages a user can read", async () => {
  const { z } = await import("zod");
  const message = (schema: { safeParse(v: unknown): { error?: { issues: { message: string }[] } } }, value: unknown): string | undefined =>
    schema.safeParse(value).error?.issues[0]?.message;
  expect(message(z.string().min(1), "")).toBe("This field is required.");
  expect(message(z.string().max(5), "123456")).toBe("Enter at most 5 characters.");
  expect(message(z.string().regex(/^[A-Z]+$/), "abc")).toBe("Enter a value in the expected format.");
  expect(message(z.int().min(1), 0)).toBe("Must be at least 1.");
  expect(message(z.int().max(9), 10)).toBe("Must be at most 9.");
  expect(message(z.string(), undefined)).toBe("This field is required.");
  expect(message(z.string(), 5)).toBe("Enter a valid value.");
  expect(message(z.enum(["A", "B"]), "C")).toBe("Choose one of the listed values.");
});
