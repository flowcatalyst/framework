// Zod 4 compiles object parsers with `new Function` when it may, and probes for that by calling
// `new Function("")` the first time an object schema is built. Under the strict CSP
// (`script-src` without 'unsafe-eval'; docs/contracts/static-assets-and-spa.md section 7) the probe
// is caught by Zod but still reported as a violation. `jitless` skips both.
//
// This module runs it. It must be evaluated before any schema exists, so an application imports
// `@flowcatalyst/api-runtime` (whose first import is this module) or `@flowcatalyst/api-runtime/zod`
// as the FIRST import of its entry (main.ts), before its router, pages or generated zod.gen.ts.
import { config } from "zod";

config({ jitless: true });

/** True once this module has run (for tests and start-up assertions). */
export const zodJitless = true;
