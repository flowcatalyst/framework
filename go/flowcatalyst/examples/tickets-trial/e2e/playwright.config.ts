// The tickets SPA's browser suite (ui/apps/tickets/e2e, shared with the Java service) against the GO
// tickets-trial (docs/go/g76-tickets-friction.md, G7.6). The web server is the Go launcher
// (`tickets-trial e2e`, ticketsweb/e2e.go), which the kit's own Java command is replaced by; everything
// else is the kit's configuration. Run from ui/apps/tickets after `vite build` (it writes
// examples/tickets-trial/web/dist, which the launcher serves from disk):
//
//   make -C go e2e-tickets          (builds the SPA and the launcher binary, then runs this)
//
// Ports are the Java suite's (4394 application, 4395 control), free of the orders suite's 4392 and 4393:
// the two Go suites could run together, but the Java and Go suites of one application may not.
import { defineE2eConfig } from "../../../../../ui/packages/ui/src/e2e/index.ts";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const repo = resolve(here, "../../../../..");
const binary = process.env["TICKETS_E2E_BINARY"];
if (binary === undefined) {
  throw new Error("TICKETS_E2E_BINARY names the compiled tickets-trial (make -C go e2e-tickets sets it)");
}
const port = 4394;
const control = 4395;

const base = defineE2eConfig({ port, control, backend: { module: "examples/tickets-trial", mainClass: "unused-by-the-go-launcher" } });

export default {
  ...base,
  // The shared tickets suite (also run by the Java service); there are no Go-only specs.
  testDir: repo,
  testMatch: [/ui\/apps\/tickets\/e2e\/[^/]+\.spec\.ts$/],
  outputDir: join(here, "test-results"),
  webServer: {
    // exec: the shell is replaced, so the signal Playwright sends reaches the launcher itself.
    command: `cd ${join(repo, "go/flowcatalyst/examples/tickets-trial")} && exec ${binary} e2e ${String(port)} ${String(control)} --dist ${join(repo, "examples/tickets-trial/web/dist")}`,
    url: `http://127.0.0.1:${String(control)}/control/ready`,
    reuseExistingServer: false,
    timeout: 300_000,
    gracefulShutdown: { signal: "SIGTERM" as const, timeout: 20_000 },
    stdout: "pipe" as const,
    stderr: "pipe" as const,
  },
};
