// The shared generation settings (@flowcatalyst/api-runtime/openapi-ts: the contract's plugin list of
// docs/contracts/openapi.md section 11 plus `baseUrl: false` and the int64 resolver, docs/ui/u2.md),
// run against the hand-written U0 document.
import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";

export default fwOpenApiConfig({ input: "openapi.json", output: "src/api" });
