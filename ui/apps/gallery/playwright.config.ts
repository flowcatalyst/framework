// The gallery under the strict CSP (docs/go/ui.md section 12; docs/contracts/static-assets-and-spa.md
// section 7). `pnpm gallery:e2e` builds the gallery, serves dist/ with the strict headers and runs
// these checks. Port 4391 is fixed and unused by the platform (8080) or Vite (5173, 5174).
import { defineConfig, devices } from "@playwright/test";

const port = 4391;

export default defineConfig({
  testDir: "e2e",
  fullyParallel: true,
  forbidOnly: process.env["CI"] !== undefined,
  // The behaviour reporter fails a full run that leaves a checklist row unexercised (src/behaviours.ts).
  reporter: [["list"], ["./e2e/behaviour-reporter.ts"]],
  use: {
    baseURL: `http://127.0.0.1:${String(port)}`,
    trace: "retain-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `node scripts/serve-csp.ts --port ${String(port)}`,
    url: `http://127.0.0.1:${String(port)}/`,
    reuseExistingServer: false,
    stdout: "pipe",
  },
});
