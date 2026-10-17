// The Playwright configuration every application's end-to-end suite shares: Chromium, one worker (the
// tests share one database), the Java launcher as the web server, and the control port in the
// environment for the fixtures. The ports are fixed and chosen by the application: free of 8080,
// 15432 and the other workspaces' (docs/guide/e2e-kit.md lists the ones taken).
import { defineConfig, devices, type PlaywrightTestConfig } from "@playwright/test";
import { join } from "node:path";
import { CONTROL_PORT_ENV } from "./control.ts";

export interface E2eConfigOptions {
  /** The application's port. */
  readonly port: number;
  /** The control server's port. */
  readonly control: number;
  /** The Java side. */
  readonly backend: {
    /** The application's Maven module, relative to the repository root: `examples/orders-demo`. */
    readonly module: string;
    /** The launcher's fully qualified main class (an `E2eLauncher` main). */
    readonly mainClass: string;
  };
  /** Milliseconds per test (default 30 000). */
  readonly timeout?: number;
}

const CLI = join(import.meta.dirname, "cli.ts");

/** The command that builds and runs the application's launcher. */
export function backendCommand(options: E2eConfigOptions): string {
  const { backend, port, control } = options;
  return `node ${CLI} --module ${backend.module} --main-class ${backend.mainClass} --port ${String(port)} --control ${String(control)}`;
}

/** The configuration of an application's suite (`testDir: "e2e"`). */
export function defineE2eConfig(options: E2eConfigOptions): PlaywrightTestConfig {
  // Playwright's workers inherit this environment; the fixtures read the control port from it.
  process.env[CONTROL_PORT_ENV] = String(options.control);
  return defineConfig({
    testDir: "e2e",
    fullyParallel: false,
    workers: 1,
    forbidOnly: process.env["CI"] !== undefined,
    reporter: [["list"]],
    timeout: options.timeout ?? 30_000,
    use: {
      baseURL: `http://127.0.0.1:${String(options.port)}`,
      trace: "retain-on-failure",
    },
    projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
    webServer: {
      command: backendCommand(options),
      url: `http://127.0.0.1:${String(options.control)}/control/ready`,
      reuseExistingServer: false,
      timeout: 300_000,
      stdout: "pipe",
      stderr: "pipe",
    },
  });
}
