// Generates the two committed clients of this package with the shared settings
// (src/openapi-ts.ts; docs/contracts/openapi.md section 11):
// - src/generated/auth: the framework's own operations, getAuthSession and logout, from the
//   conformance vector every backend reproduces (conformance/openapi/auth.json);
// - test/orders-demo/api: the Java orders example's document (conformance/openapi/orders-demo.json,
//   byte-equal to examples/orders-demo/web/openapi.json), used by the tests (the int64 form proof).
// `pnpm generate` writes both; `pnpm drift` fails when the committed output differs.
import { defineConfig } from "@hey-api/openapi-ts";
import { fwOpenApiConfig } from "./src/openapi-ts.ts";

export default defineConfig([
  fwOpenApiConfig({ input: "../../../conformance/openapi/auth.json", output: "src/generated/auth" }),
  fwOpenApiConfig({ input: "../../../conformance/openapi/orders-demo.json", output: "test/orders-demo/api" }),
]);
