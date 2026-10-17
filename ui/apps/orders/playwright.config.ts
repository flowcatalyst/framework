// The orders SPA against the real Java demo (docs/ui/u3.md). `pnpm --filter @flowcatalyst/orders-web
// e2e` builds the SPA into the demo's web/dist and runs these tests; the web server is the demo itself
// (Maven, then a JVM with Testcontainers PostgreSQL and a test OIDC provider: OrdersE2e), serving the
// SPA with the strict CSP. The shared kit (docs/guide/e2e-kit.md) supplies everything else. Ports 4392
// (application) and 4393 (control) are fixed, free of 8080, 15432 and the other workspaces' (4391,
// 5173 to 5175).
import { defineE2eConfig } from "@flowcatalyst/ui/e2e";

export default defineE2eConfig({
  port: 4392,
  control: 4393,
  backend: { module: "examples/orders-demo", mainClass: "io.flowcatalyst.framework.examples.ordersdemo.OrdersE2e" },
});
