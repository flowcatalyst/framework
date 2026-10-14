// Zod 4 compiles object parsers with `new Function` when it may, and probes for that by calling
// `new Function("")` once. Under `script-src` without 'unsafe-eval' the probe is caught by Zod but
// still reported as a CSP violation. `jitless` skips both. Imported first by main.ts, so it runs
// before any schema is created (the probe runs when an object schema is built).
// @flowcatalyst/api-runtime (U2) takes this over, as it owns the generated Zod schemas.
import { config } from "zod";

config({ jitless: true });
