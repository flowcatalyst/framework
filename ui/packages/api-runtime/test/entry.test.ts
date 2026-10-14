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
