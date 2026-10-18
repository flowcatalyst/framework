// Runs the Playwright CLI from the copy of @playwright/test that THIS kit resolves.
//
// The kit imports @playwright/test (test, defineConfig). When the kit is consumed with `link:`, that import
// resolves from the framework checkout, while an application's own `playwright` binary is its own copy. Two
// copies in one process give "did not expect test.beforeEach() to be called here". Starting the runner from
// the kit's copy makes the config, the fixtures and the runner one copy.
import { spawn } from "node:child_process";
import { createRequire } from "node:module";

/** The Playwright CLI script the kit resolves (`@playwright/test/cli`). */
export function playwrightCliPath(): string {
  return createRequire(import.meta.url).resolve("@playwright/test/cli");
}

/** The node arguments that run the kit's Playwright CLI with the caller's arguments passed through. */
export function playwrightArguments(args: readonly string[]): string[] {
  return [playwrightCliPath(), ...args];
}

/** Runs the CLI in the working directory with the current environment; resolves to its exit code. */
export function runPlaywright(args: readonly string[]): Promise<number> {
  return new Promise((resolveCode, reject) => {
    const child = spawn(process.execPath, playwrightArguments(args), { stdio: "inherit" });
    for (const signal of ["SIGTERM", "SIGINT"] as const) {
      process.on(signal, () => {
        child.kill(signal);
      });
    }
    child.on("error", reject);
    child.on("exit", (code, signal) => {
      resolveCode(code ?? (signal === null ? 1 : 128));
    });
  });
}
