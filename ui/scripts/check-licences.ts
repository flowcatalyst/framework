// docs/go/ui.md section 14, step 6: every package in the lockfile (production and development) has
// a permissive licence, or a reviewed exception listed here with its reason.
//
//   node scripts/check-licences.ts
import { execFileSync } from "node:child_process";

const PERMISSIVE = new Set(["MIT", "ISC", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "0BSD", "BlueOak-1.0.0", "CC0-1.0", "Python-2.0"]);

/** Reviewed 2026-10-14 (U0). Key: package name; value: [licence as pnpm reports it, reason]. */
const REVIEWED: ReadonlyMap<string, readonly [string, string]> = new Map([
  ["axe-core", ["MPL-2.0", "test tool (Playwright accessibility checks), unmodified, never shipped"]],
  ["@axe-core/playwright", ["MPL-2.0", "test tool, unmodified, never shipped"]],
  ["lightningcss", ["MPL-2.0", "Vite's CSS minifier, build tool, unmodified, never shipped"]],
  ["lightningcss-darwin-arm64", ["MPL-2.0", "lightningcss's platform binary"]],
  ["lightningcss-darwin-x64", ["MPL-2.0", "lightningcss's platform binary"]],
  ["lightningcss-linux-x64-gnu", ["MPL-2.0", "lightningcss's platform binary"]],
  ["lightningcss-linux-arm64-gnu", ["MPL-2.0", "lightningcss's platform binary"]],
  ["@fontsource-variable/inter", ["OFL-1.1", "the Inter font, shipped as woff2; OFL allows bundling (notice goes with the app)"]],
  ["vaul-vue", ["Unknown", "0.4.1's npm package has no licence field or file; the repository (unovue/vaul-vue) is MIT"]],
]);

type Listing = Record<string, { name: string; versions: string[] }[]>;

const output = execFileSync("pnpm", ["licenses", "list", "--json"], { encoding: "utf8" });
const parsed: unknown = JSON.parse(output);
if (typeof parsed !== "object" || parsed === null) {
  throw new Error("unexpected output from pnpm licenses list");
}
const listing: Listing = Object.fromEntries(Object.entries(parsed));

const problems: string[] = [];
let count = 0;
for (const [licence, packages] of Object.entries(listing)) {
  for (const pkg of packages) {
    count += 1;
    if (PERMISSIVE.has(licence)) {
      continue;
    }
    const reviewed = REVIEWED.get(pkg.name);
    if (reviewed?.[0] !== licence) {
      problems.push(`${pkg.name}@${pkg.versions.join(",")}: ${licence}`);
    }
  }
}

if (problems.length > 0) {
  console.error(`Not permissive and not reviewed (add a reason to REVIEWED only after review):\n  ${problems.join("\n  ")}`);
  process.exit(1);
}
console.log(`licences OK: ${String(count)} packages`);
