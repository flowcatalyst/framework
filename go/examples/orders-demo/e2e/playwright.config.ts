// The orders SPA's browser suite (ui/apps/orders/e2e, shared with the Java demo) against the GO
// orders-demo (docs/go/milestones.md, G5c). The web server is the Go launcher (`orders-demo e2e`,
// e2e.go), which the kit's own Java command is replaced by; everything else is the kit's
// configuration. Run from ui/apps/orders after `vite build` (it writes web/dist, which the launcher
// serves from disk):
//
//   make -C go e2e-orders          (builds the launcher binary, then runs this)
//
// Ports are the Java suite's (4392 application, 4393 control): the two suites do not run at once.
import { defineE2eConfig } from "../../../../ui/packages/ui/src/e2e/index.ts";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../../../..");
const binary = process.env["ORDERS_E2E_BINARY"];
if (binary === undefined) {
  throw new Error("ORDERS_E2E_BINARY names the compiled orders-demo (make -C go e2e-orders sets it)");
}
const port = 4392;
const control = 4393;

const base = defineE2eConfig({ port, control, backend: { module: "examples/orders-demo", mainClass: "unused-by-the-go-launcher" } });

export default {
  ...base,
  // The shared orders suite (also run by the Java demo) and this directory's Go-only specs.
  testDir: repo,
  testMatch: [/ui\/apps\/orders\/e2e\/[^/]+\.spec\.ts$/, /go\/examples\/orders-demo\/e2e\/[^/]+\.spec\.ts$/],
  outputDir: join(here, "test-results"),
  webServer: {
    // exec: the shell is replaced, so the signal Playwright sends reaches the launcher itself.
    command: `cd ${join(repo, "go/examples/orders-demo")} && exec ${binary} e2e ${String(port)} ${String(control)} --dist ${join(repo, "examples/orders-demo/web/dist")}`,
    url: `http://127.0.0.1:${String(control)}/control/ready`,
    reuseExistingServer: false,
    timeout: 300_000,
    gracefulShutdown: { signal: "SIGTERM" as const, timeout: 20_000 },
    stdout: "pipe" as const,
    stderr: "pipe" as const,
  },
};
