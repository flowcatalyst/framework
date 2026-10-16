import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { checkDrift, diffSnapshots, snapshot } from "../bin/api-drift.mjs";

// The drift check with a plain-file comparison (no git) and with git. The "generator" is a node one-liner.

let dir: string;
beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "fc-drift-"));
  mkdirSync(join(dir, "gen"));
  writeFileSync(join(dir, "gen", "a.ts"), "A");
});
afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

function generator(script: string): string {
  const file = join(dir, "generate.mjs");
  writeFileSync(file, `import { writeFileSync, rmSync } from "node:fs";\n${script}`);
  return `node ${JSON.stringify(file)}`;
}

describe("snapshot mode (no git)", () => {
  it("passes when the generator reproduces the folder", () => {
    const run = generator(`writeFileSync(${JSON.stringify(join(dir, "gen", "a.ts"))}, "A");`);
    const r = checkDrift({ paths: ["gen"], run, git: false, cwd: dir });
    expect(r.ok).toBe(true);
  });

  it("names a changed, an added and a removed file", () => {
    const run = generator(
      `writeFileSync(${JSON.stringify(join(dir, "gen", "a.ts"))}, "B");` +
        `writeFileSync(${JSON.stringify(join(dir, "gen", "new.ts"))}, "N");`,
    );
    const r = checkDrift({ paths: ["gen"], run, git: false, cwd: dir });
    expect(r.ok).toBe(false);
    expect(r.report).toContain("changed  gen/a.ts");
    expect(r.report).toContain("added    gen/new.ts");
    const removal = generator(`rmSync(${JSON.stringify(join(dir, "gen", "a.ts"))});`);
    expect(checkDrift({ paths: ["gen"], run: removal, git: false, cwd: dir }).report).toContain("removed  gen/a.ts");
  });

  it("refuses to guess without git and without --run", () => {
    const r = checkDrift({ paths: ["gen"], git: false, cwd: dir });
    expect(r.ok).toBe(false);
    expect(r.report).toContain("--run");
  });

  it("treats a missing folder as empty", () => {
    expect(snapshot(["nope"], dir).size).toBe(0);
    expect(diffSnapshots(new Map(), new Map([["x", "1"]]))).toEqual(["added    x"]);
  });
});

describe("git mode", () => {
  const git = (...args: string[]) => execFileSync("git", args, { cwd: dir, stdio: "pipe" });
  beforeEach(() => {
    git("init", "-q");
    git("-c", "user.email=t@t", "-c", "user.name=t", "add", ".");
    git("-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "x");
  });

  it("passes on a clean folder and reports a new untracked file", () => {
    expect(checkDrift({ paths: ["gen"], cwd: dir }).ok).toBe(true);
    writeFileSync(join(dir, "gen", "new.ts"), "N");
    const r = checkDrift({ paths: ["gen"], cwd: dir });
    expect(r.ok).toBe(false);
    expect(r.report).toContain("gen/new.ts");
  });

  it("falls back to --run outside a git work tree", () => {
    const outside = mkdtempSync(join(tmpdir(), "fc-drift-nogit-"));
    try {
      mkdirSync(join(outside, "gen"));
      expect(checkDrift({ paths: ["gen"], cwd: outside }).report).toContain("--run");
      expect(checkDrift({ paths: ["gen"], run: "node -e 0", cwd: outside }).ok).toBe(true);
    } finally {
      rmSync(outside, { recursive: true, force: true });
    }
  });
});
