#!/usr/bin/env node
// Drift check for generated clients (docs/contracts/openapi.md section 11): fails when the given
// folders differ from what the generator produces, new and deleted files included.
//
//   fc-api-drift [--no-git] [--run "<generator command>"] <generated folder>...
//
// Two ways to know "what was there before":
//   - git (the default, when the folders are in a git work tree and no --run is given): run the
//     generator first; the folders must then have no `git status` entry. Plain `git diff --exit-code`
//     misses a file the generator added, `git status --porcelain` does not.
//   - a snapshot (no git needed): `--run "openapi-ts"` hashes the folders, runs the command, hashes
//     again and reports files added, removed or changed. `--no-git` makes it the only way. In CI this
//     compares the committed files with a fresh generation, which is the same question.
import { execFileSync, execSync } from "node:child_process";
import { createHash } from "node:crypto";
import { existsSync, readdirSync, readFileSync, statSync } from "node:fs";
import { join, relative } from "node:path";
import { pathToFileURL } from "node:url";

/**
 * Every file under the folders, as `path -> sha256`, paths relative to `cwd` with `/` separators.
 * A folder that does not exist is empty.
 * @param {string[]} paths
 * @param {string} cwd
 * @returns {Map<string, string>}
 */
export function snapshot(paths, cwd) {
  const out = new Map();
  const walk = (abs) => {
    if (!existsSync(abs)) return;
    if (statSync(abs).isDirectory()) {
      for (const name of readdirSync(abs).sort()) walk(join(abs, name));
    } else {
      const key = relative(cwd, abs).split("\\").join("/");
      out.set(key, createHash("sha256").update(readFileSync(abs)).digest("hex"));
    }
  };
  for (const p of paths) walk(join(cwd, p));
  return out;
}

/**
 * @param {Map<string, string>} before
 * @param {Map<string, string>} after
 * @returns {string[]} one line per difference, sorted
 */
export function diffSnapshots(before, after) {
  const lines = [];
  for (const [path, hash] of after) {
    if (!before.has(path)) lines.push(`added    ${path}`);
    else if (before.get(path) !== hash) lines.push(`changed  ${path}`);
  }
  for (const path of before.keys()) if (!after.has(path)) lines.push(`removed  ${path}`);
  return lines.sort();
}

/** Whether `cwd` is inside a git work tree and git can be run. */
export function gitAvailable(cwd) {
  try {
    execFileSync("git", ["rev-parse", "--is-inside-work-tree"], { cwd, stdio: "pipe" });
    return true;
  } catch {
    return false;
  }
}

/**
 * @param {{ paths: string[], run?: string, git?: boolean, cwd?: string }} options
 * @returns {{ ok: boolean, report: string }}
 */
export function checkDrift({ paths, run, git = true, cwd = process.cwd() }) {
  if (run !== undefined || !git || !gitAvailable(cwd)) {
    if (run === undefined) {
      return {
        ok: false,
        report:
          "no git work tree (or --no-git) and no --run: nothing to compare with. " +
          'Pass --run "<generator command>" so the folders are compared before and after it.',
      };
    }
    const before = snapshot(paths, cwd);
    execSync(run, { cwd, stdio: "inherit" });
    const lines = diffSnapshots(before, snapshot(paths, cwd));
    return lines.length === 0
      ? { ok: true, report: `no drift: ${paths.join(", ")}` }
      : { ok: false, report: `Generated client out of date (regenerate and commit):\n${lines.join("\n")}` };
  }
  const status = execFileSync("git", ["status", "--porcelain", "--untracked-files=all", "--", ...paths], {
    cwd,
    encoding: "utf8",
  });
  if (status.trim() !== "") {
    const stat = execFileSync("git", ["diff", "--stat", "--", ...paths], { cwd, encoding: "utf8" });
    return { ok: false, report: `Generated client out of date (regenerate and commit):\n${status}\n${stat}` };
  }
  return { ok: true, report: `no drift: ${paths.join(", ")}` };
}

/**
 * The command line.
 * @param {string[]} args
 * @returns {number} the exit code
 */
export function main(args) {
  let run;
  let git = true;
  const paths = [];
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (a === "--no-git") git = false;
    else if (a === "--run") run = args[++i];
    else paths.push(a);
  }
  if (paths.length === 0 || (args.includes("--run") && run === undefined)) {
    console.error('usage: fc-api-drift [--no-git] [--run "<generator command>"] <generated folder>...');
    return 2;
  }
  const { ok, report } = checkDrift({ paths, run, git });
  (ok ? console.log : console.error)(report);
  return ok ? 0 : 1;
}

if (process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exit(main(process.argv.slice(2)));
}
