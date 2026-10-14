// @vitest-environment node
// What hey-api 0.99.0 generates from the orders example's document (conformance/openapi/orders-demo.json)
// with the contract's configuration as written (docs/contracts/openapi.md section 11) and with
// fwOpenApiConfig (src/openapi-ts.ts), run the way an application runs it: from its web/ folder with
// `input: "openapi.json"`. A child process, because the generator resolves the input against the
// working directory (and in a DOM test environment it would fetch the input instead of reading it).
import { execFileSync } from "node:child_process";
import { copyFileSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";

const here = import.meta.dirname;
const ORDERS_DEMO = join(here, "../../../../conformance/openapi/orders-demo.json");
const HEY_API = pathToFileURL(join(here, "../node_modules/@hey-api/openapi-ts/dist/index.mjs")).href;
const FW_CONFIG = pathToFileURL(join(here, "../src/openapi-ts.ts")).href;

const script = `
const { createClient } = await import(${JSON.stringify(HEY_API)});
const { fwOpenApiConfig } = await import(${JSON.stringify(FW_CONFIG)});
await createClient({
  input: "openapi.json",
  output: { path: "contract" },
  logs: { level: "silent" },
  plugins: [
    { name: "@hey-api/typescript", enums: "javascript" },
    "@hey-api/client-fetch",
    { name: "@hey-api/sdk", validator: false },
    { name: "zod", compatibilityVersion: 4 },
  ],
});
await createClient({ ...fwOpenApiConfig({ input: "openapi.json", output: "fw" }), logs: { level: "silent" } });
`;

describe("generated from orders-demo.json by hey-api 0.99.0", () => {
  const dir = mkdtempSync(join(tmpdir(), "fw-generate-"));
  const read = (folder: string, file: string): string => readFileSync(join(dir, folder, file), "utf8");

  beforeAll(() => {
    copyFileSync(ORDERS_DEMO, join(dir, "openapi.json"));
    execFileSync(process.execPath, ["--input-type=module", "-e", script], { cwd: dir, stdio: "pipe" });
  }, 60_000);

  afterAll(() => {
    rmSync(dir, { recursive: true, force: true });
  });

  it("the contract's configuration writes z.coerce.bigint() for int64 (a BigInt JSON.stringify refuses)", () => {
    expect(read("contract", "zod.gen.ts")).toContain("unitPriceCents: z.coerce.bigint().gte(BigInt(0)).lte(BigInt(100000000))");
    expect(() => JSON.stringify({ unitPriceCents: BigInt(1999) })).toThrow(/BigInt/);
  });

  it("fwOpenApiConfig writes z.int() with numeric bounds, the same file as the committed fixture", () => {
    expect(read("fw", "zod.gen.ts")).toContain("unitPriceCents: z.int().gte(0).lte(100000000)");
    expect(read("fw", "zod.gen.ts")).toBe(readFileSync(join(here, "orders-demo/api/zod.gen.ts"), "utf8"));
  });

  it("the contract's configuration takes `openapi.json` for a base URL; fwOpenApiConfig writes none", () => {
    expect(read("contract", "client.gen.ts")).toContain("createConfig<ClientOptions2>({ baseUrl: 'openapi.json' })");
    expect(read("fw", "client.gen.ts")).toContain("createClient(createConfig<ClientOptions2>())");
  });

  it("both write `number` as the TypeScript type of the int64 field", () => {
    for (const folder of ["contract", "fw"]) {
      expect(read(folder, "types.gen.ts")).toMatch(/unitPriceCents: number;/);
    }
  });
});
