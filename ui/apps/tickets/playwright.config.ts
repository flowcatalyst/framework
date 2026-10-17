// The tickets SPA against the real Java service (docs/m4f-trial-service.md). `pnpm --filter
// @flowcatalyst/tickets-web e2e` builds the SPA into the service's web/dist and runs these tests; the web
// server is the service itself (Maven, then a JVM with Testcontainers PostgreSQL, a test OIDC provider
// and a fake platform: TicketsE2e), serving the SPA with the strict CSP. The shared kit
// (docs/guide/e2e-kit.md) supplies everything else. Ports 4394 (application) and 4395 (control) are
// fixed, free of 8080, 15432 and the other workspaces' (4391 to 4393, 5173 to 5176).
import { defineE2eConfig } from "@flowcatalyst/ui/e2e";

export default defineE2eConfig({
  port: 4394,
  control: 4395,
  backend: { module: "examples/tickets-trial", mainClass: "io.flowcatalyst.framework.examples.ticketstrial.TicketsE2e" },
});
