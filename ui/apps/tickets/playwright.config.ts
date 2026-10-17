// The tickets SPA against the real Java service (docs/m4f-trial-service.md). `pnpm --filter
// @flowcatalyst/tickets-web e2e` builds the SPA into the service's web/dist and runs these tests;
// the web server below is the service itself (Maven, then a JVM with Testcontainers PostgreSQL, a
// test OIDC provider and a fake platform), serving the SPA with the strict CSP. Ports 4394
// (application) and 4395 (control) are fixed, free of 8080, 15432 and the other workspaces' (4391
// to 4393, 5173 to 5176). One worker: the tests share one database.
import { defineConfig, devices } from "@playwright/test";

const port = 4394;
const control = 4395;

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
