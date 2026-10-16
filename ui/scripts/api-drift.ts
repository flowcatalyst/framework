// The drift check lives in @flowcatalyst/api-runtime (bin `fc-api-drift`, packages/api-runtime/bin/api-drift.mjs):
// an application outside this repository runs it from its dependency, with or without git. This path
// stays for the workspace's own `drift` scripts.
//
//   openapi-ts && node ../../scripts/api-drift.ts src/api              (git)
//   node ../../scripts/api-drift.ts --run "openapi-ts" src/api         (no git needed)
import { main } from "../packages/api-runtime/bin/api-drift.mjs";

process.exit(main(process.argv.slice(2)));
