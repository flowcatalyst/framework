// The hey-api configuration of docs/contracts/openapi.md section 11, unchanged, run against a
// hand-written document shaped by that contract (the Java and Go emitters do not exist yet).
import { defineConfig } from "@hey-api/openapi-ts";

export default defineConfig({
  input: "openapi.json",
  output: { path: "src/api" },
  plugins: [
    { name: "@hey-api/typescript", enums: "javascript" },
    "@hey-api/client-fetch",
    { name: "@hey-api/sdk", validator: false },
    { name: "zod", compatibilityVersion: 4 },
  ],
});
