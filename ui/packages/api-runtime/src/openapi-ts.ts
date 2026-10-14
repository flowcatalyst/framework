// The @hey-api/openapi-ts configuration every FlowCatalyst SPA generates its client with
// (docs/contracts/openapi.md section 11; Node only: imported by an application's
// openapi-ts.config.ts, never by browser code).
//
//   // web/openapi-ts.config.ts
//   import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";
//   export default fwOpenApiConfig({ input: "openapi.json", output: "src/api" });
//
// It is the contract's plugin list with two settings added, both found at U2 (docs/ui/u2.md):
//
// 1. `@hey-api/client-fetch` gets `baseUrl: false`. A document without `servers` (the contract
//    forbids them: the SPA calls its own origin) otherwise makes hey-api 0.99.0 write the *input
//    path* as the base URL (`baseUrl: 'openapi.json'`), so every request would go to
//    `openapi.json/api/...` relative to the page. `configureClient()` also sets `baseUrl: ""` at
//    run time.
// 2. The `zod` plugin gets a number resolver for `format: int64` (and `uint64`). hey-api writes
//    `z.coerce.bigint()` for them while the TypeScript type it writes is `number`; a form value
//    parsed by that schema becomes a BigInt, which `JSON.stringify` cannot serialise ("Do not know
//    how to serialize a BigInt"). The resolver writes `z.int()` instead (Zod 4: a safe integer,
//    so values beyond 2^53 are refused in the browser, as the contract's type table says: "number,
//    exact only below 2^53: ids are strings"), with `minimum`/`maximum` as plain numbers.
import { $, type UserConfig } from "@hey-api/openapi-ts";

type ZodPluginConfig = Extract<NonNullable<UserConfig["plugins"]>[number], { name: "zod" }>;
type ZodResolvers = NonNullable<ZodPluginConfig["~resolvers"]>;
type NumberResolver = NonNullable<ZodResolvers["number"]>;
type NumberContext = Parameters<NumberResolver>[0];

const BIGINT_FORMATS = new Set(["int64", "uint64"]);

/**
 * Zod number resolver: `int64`/`uint64` become `z.int()` with numeric bounds instead of
 * `z.coerce.bigint()`. Every other number keeps hey-api's default (returning `undefined` runs it).
 */
export const int64AsNumber: NumberResolver = (ctx) => {
  if (!BIGINT_FORMATS.has(ctx.schema.format ?? "")) {
    return undefined;
  }
  const asNumber: NumberContext = {
    ...ctx,
    utils: {
      ...ctx.utils,
      shouldCoerceToBigInt: () => false,
      maybeBigInt: (value) => $.fromValue(value),
      // Without a declared bound, z.int() already limits the value to the safe-integer range.
      getIntegerLimit: (format) => (BIGINT_FORMATS.has(format ?? "") ? undefined : ctx.utils.getIntegerLimit(format)),
    },
  };
  const constant = asNumber.nodes.const(asNumber);
  if (constant !== undefined) {
    return constant;
  }
  asNumber.chain.current = asNumber.nodes.base(asNumber);
  const min = asNumber.nodes.min(asNumber);
  if (min !== undefined) {
    asNumber.chain.current = min;
  }
  const max = asNumber.nodes.max(asNumber);
  if (max !== undefined) {
    asNumber.chain.current = max;
  }
  return asNumber.chain.current;
};

export interface FwOpenApiOptions {
  /** The committed OpenAPI document (written by the backend's `openapi` command). */
  readonly input: string;
  /** The folder to generate into; committed, and checked for drift in CI. */
  readonly output: string;
}

/** The pinned generation settings (contract section 11 plus the two settings above). */
export function fwOpenApiConfig(options: FwOpenApiOptions): UserConfig {
  return {
    input: options.input,
    output: { path: options.output },
    logs: { level: "warn" },
    plugins: [
      // Literal unions, no TypeScript enums (erasableSyntaxOnly).
      { name: "@hey-api/typescript", enums: "javascript" },
      { name: "@hey-api/client-fetch", baseUrl: false },
      // One typed function per operation; no run-time validation by default.
      { name: "@hey-api/sdk", validator: false },
      // Schemas for UForm (Standard Schema), with int64 kept a number.
      { name: "zod", compatibilityVersion: 4, "~resolvers": { number: int64AsNumber } },
    ],
  };
}
