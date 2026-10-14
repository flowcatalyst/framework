// The orders SPA against the real Java demo (docs/ui/u3.md). `pnpm --filter @flowcatalyst/orders-web
// e2e` builds the SPA into the demo's web/dist and runs these tests; the web server below is the
// demo itself (Maven, then a JVM with Testcontainers PostgreSQL and a test OIDC provider), serving
// the SPA with the strict CSP. Ports 4392 (application) and 4393 (control) are fixed, free of 8080,
// 15432 and the other workspaces' (4391, 5173 to 5175). One worker: the tests share one database.
import { defineConfig, devices } from "@playwright/test";

const port = 4392;
const control = 4393;

export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: process.env["CI"] !== undefined,
  reporter: [["list"]],
  timeout: 30_000,
  use: {
    baseURL: `http://127.0.0.1:${String(port)}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `node scripts/start-backend.ts --port ${String(port)} --control ${String(control)}`,
    url: `http://127.0.0.1:${String(control)}/control/ready`,
    reuseExistingServer: false,
    timeout: 300_000,
    stdout: "pipe",
    stderr: "pipe",
  },
});
