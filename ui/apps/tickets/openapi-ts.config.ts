// The tickets SPA's client, generated from the document the Java service commits
// (examples/tickets-trial/web/openapi.json, written by `java -jar ... openapi --out web/openapi.json`
// or by TicketsOpenApiTest with -Dopenapi.update=true) with the shared settings. The generated
// folder is committed; `pnpm api:drift` fails when it is stale.
import { fwOpenApiConfig } from "@flowcatalyst/api-runtime/openapi-ts";

export default fwOpenApiConfig({ input: "../../../examples/tickets-trial/web/openapi.json", output: "src/api/tickets" });
