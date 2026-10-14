// The orders SPA's client, generated from the document the Java demo commits (examples/orders-demo/
// web/openapi.json, written by `java -jar ... openapi --out web/openapi.json`) with the shared
// settings. The generated folder is committed; `pnpm api:drift` fails when it is stale.
import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";

export default fwOpenApiConfig({ input: "../../../examples/orders-demo/web/openapi.json", output: "src/api/orders" });
