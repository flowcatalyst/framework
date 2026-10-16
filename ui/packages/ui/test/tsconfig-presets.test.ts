import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { afterAll, beforeAll, describe, expect, it } from "vitest";
import { z } from "zod";

// An application outside this repository extends the presets by package name, not by a relative path to
// this checkout. The test builds such a consumer (node_modules/@flowcatalyst/ui is a symlink, as `link:`
// makes it) and asks tsc what its tsconfig resolves to.

const pkg = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const tsc = join(pkg, "node_modules/.bin/tsc");
let app: string;

beforeAll(() => {
  app = mkdtempSync(join(tmpdir(), "fc-tsconfig-"));
  mkdirSync(join(app, "node_modules/@flowcatalyst"), { recursive: true });
  symlinkSync(pkg, join(app, "node_modules/@flowcatalyst/ui"), "dir");
  symlinkSync(join(pkg, "../api-runtime"), join(app, "node_modules/@flowcatalyst/api-runtime"), "dir");
  mkdirSync(join(app, "src"));
  writeFileSync(join(app, "src/a.ts"), "export const a = 1;\n");
});
afterAll(() => {
  rmSync(app, { recursive: true, force: true });
});

const ShownConfig = z.object({ compilerOptions: z.record(z.string(), z.unknown()) });

function show(project: string): Record<string, unknown> {
  return ShownConfig.parse(JSON.parse(execFileSync(tsc, ["--showConfig", "-p", project], { encoding: "utf8" }))).compilerOptions;
}

function resolved(config: object): Record<string, unknown> {
  writeFileSync(join(app, "tsconfig.json"), JSON.stringify(config));
  return show(app);
}

describe("tsconfig presets, extended by package name", () => {
  it("@flowcatalyst/ui/tsconfig.base.json gives the strict settings", () => {
    const o = resolved({ extends: "@flowcatalyst/ui/tsconfig.base.json", include: ["src/**/*.ts"] });
    expect(o["strict"]).toBe(true);
    expect(o["exactOptionalPropertyTypes"]).toBe(true);
    expect(o["verbatimModuleSyntax"]).toBe(true);
    expect(o["noUncheckedIndexedAccess"]).toBe(true);
  });

  it("@flowcatalyst/ui/tsconfig.generated-client.json is the strict base without exactOptionalPropertyTypes", () => {
    const o = resolved({
      extends: "@flowcatalyst/ui/tsconfig.generated-client.json",
      compilerOptions: { outDir: "./out" },
      include: ["src/**/*.ts"],
    });
    expect(o["exactOptionalPropertyTypes"]).toBe(false);
    expect(o["strict"]).toBe(true);
    expect(o["emitDeclarationOnly"]).toBe(true);
    expect(o["declaration"]).toBe(true);
    expect(o["noEmit"]).toBe(false);
  });

  it("an application can reference the runtime's generated-client project through node_modules", () => {
    // the reference is a path, and a path is the one place a package name cannot be used: this proves the
    // referenced project resolves its own extends when reached through a link
    writeFileSync(
      join(app, "tsconfig.json"),
      JSON.stringify({
        extends: "@flowcatalyst/ui/tsconfig.base.json",
        compilerOptions: { tsBuildInfoFile: "./node_modules/.tmp/app.tsbuildinfo", types: [] },
        include: ["src/**/*.ts"],
        references: [{ path: "./node_modules/@flowcatalyst/api-runtime/tsconfig.generated.json" }],
      }),
    );
    expect(() => execFileSync(tsc, ["--build", app], { encoding: "utf8", stdio: "pipe" })).not.toThrow();
  });

  it("the workspace's own ui/tsconfig.base.json is the same settings", () => {
    const own = show(join(pkg, "tsconfig.app.json"));
    expect(own["exactOptionalPropertyTypes"]).toBe(true);
    expect(own["strict"]).toBe(true);
  });
});
