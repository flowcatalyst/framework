// The gallery's own generated client, from the Java orders example's document (conformance/openapi/
// orders-demo.json), with the shared settings: what an application's web/openapi-ts.config.ts looks
// like (docs/ui/u2.md). The gallery's orders page calls it against an in-page fake server
// (src/fake-server.ts), so the generated SDK, the configured client and the Zod schemas run in the
// browser under the strict CSP.
import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";

export default fwOpenApiConfig({ input: "../../../conformance/openapi/orders-demo.json", output: "src/api/orders-demo" });
