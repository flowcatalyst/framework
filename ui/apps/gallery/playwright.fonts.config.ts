// The font comparison's screenshots (docs/ui/fonts.md): the `--mode fonts` build (dist-fonts/, which
// has the comparison fonts) behind the same strict-CSP server, on its own port. `pnpm gallery:fonts`
// builds it and runs e2e-fonts/. Not part of `pnpm check`; the files go to screenshots/fonts/.
import { defineConfig, devices } from "@playwright/test";

const port = 4392;

export default defineConfig({
  testDir: "e2e-fonts",
  fullyParallel: true,
  reporter: [["list"]],
  use: { baseURL: `http://127.0.0.1:${String(port)}` },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
  webServer: {
    command: `node scripts/serve-csp.ts --port ${String(port)} --dir dist-fonts`,
    url: `http://127.0.0.1:${String(port)}/`,
    reuseExistingServer: false,
    stdout: "pipe",
  },
});
