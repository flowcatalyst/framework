// docs/go/ui.md section 14, step 6: every package in the lockfile (production and development) has
// a permissive licence, or a reviewed exception listed here with its reason. Then the third-party
// notices of the shipped bundle (ui/THIRD-PARTY-NOTICES): generated from the gallery's build, which is
// what an application on @flowcatalyst/ui ships, and checked to be up to date.
//
//   node scripts/check-licences.ts            check the lockfile and that THIRD-PARTY-NOTICES is current
//   node scripts/check-licences.ts --write    also (re)write THIRD-PARTY-NOTICES
//   node scripts/check-licences.ts --write --dist <app>/dist --out <file>   an application's own bundle
//
// The shipped packages are read from the bundle's source maps (every module under node_modules that
// ended up in a chunk), plus what reaches the bundle without a JavaScript module: the Inter font files,
// the Lucide icon data Nuxt UI inlines, and the CSS Tailwind generates. Needs a build with source maps
// (`pnpm build` first; the gallery builds with `sourcemap: true`).
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, realpathSync, writeFileSync } from "node:fs";
import { join, resolve } from "node:path";

const ROOT = resolve(import.meta.dirname, "..");
const PERMISSIVE = new Set(["MIT", "ISC", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause", "0BSD", "BlueOak-1.0.0", "CC0-1.0", "Python-2.0"]);

/** Reviewed 2026-10-14 (U0). Key: package name; value: [licence as pnpm reports it, reason]. */
const REVIEWED: ReadonlyMap<string, readonly [string, string]> = new Map([
  ["axe-core", ["MPL-2.0", "test tool (Playwright accessibility checks), unmodified, never shipped"]],
  ["@axe-core/playwright", ["MPL-2.0", "test tool, unmodified, never shipped"]],
  ["lightningcss", ["MPL-2.0", "Vite's CSS minifier (with its platform binary), build tool, unmodified, never shipped"]],
  ["lightningcss-darwin-arm64", ["MPL-2.0", "lightningcss's platform binary"]],
  ["lightningcss-darwin-x64", ["MPL-2.0", "lightningcss's platform binary"]],
  ["lightningcss-linux-x64-gnu", ["MPL-2.0", "lightningcss's platform binary"]],
  ["lightningcss-linux-arm64-gnu", ["MPL-2.0", "lightningcss's platform binary"]],
  ["@fontsource-variable/inter", ["OFL-1.1", "the Inter font, shipped as woff2; OFL allows bundling (its notice goes with the app: THIRD-PARTY-NOTICES)"]],
  ["vaul-vue", ["Unknown", "0.4.1's npm package has no licence field or file; the repository (unovue/vaul-vue) is MIT (THIRD-PARTY-NOTICES carries its text)"]],
]);

/**
 * Licence texts for shipped packages whose npm package carries none, copied from their repositories
 * on 2026-10-14 (scripts/licences/). The draft upstream request is docs/ui/upstream-vaul-vue-issue.md.
 */
const UPSTREAM_TEXT: ReadonlyMap<string, { readonly file: string; readonly source: string; readonly licence: string }> = new Map([
  ["vaul-vue", { file: "vaul-vue.LICENSE", source: "https://github.com/unovue/vaul-vue/blob/main/LICENSE", licence: "MIT (the repository's; the npm package declares none)" }],
  ["@iconify-json/lucide", { file: "lucide.LICENSE", source: "https://github.com/lucide-icons/lucide/blob/main/LICENSE", licence: "the icons derived from Feather: MIT" }],
]);

type Listing = Record<string, { name: string; versions: string[] }[]>;

function pnpmLicences(): Listing {
  const output = execFileSync("pnpm", ["licenses", "list", "--json"], { encoding: "utf8", cwd: ROOT });
  const parsed: unknown = JSON.parse(output);
  if (typeof parsed !== "object" || parsed === null) {
    throw new Error("unexpected output from pnpm licenses list");
  }
  return Object.fromEntries(Object.entries(parsed));
}

function arg(name: string): string | undefined {
  const index = process.argv.indexOf(name);
  return index === -1 ? undefined : process.argv[index + 1];
}

interface Shipped {
  readonly name: string;
  readonly version: string;
  readonly licence: string;
  readonly repository: string;
  readonly text: string;
  /** How the package reaches the bundle, when not as a JavaScript module. */
  readonly via?: string;
}

function readJson(file: string): Record<string, unknown> {
  const parsed: unknown = JSON.parse(readFileSync(file, "utf8"));
  if (typeof parsed !== "object" || parsed === null) {
    throw new Error(`${file}: not a JSON object`);
  }
  return Object.fromEntries(Object.entries(parsed));
}

/** The package directory of a file under node_modules (the last node_modules segment). */
function packageDir(file: string): string | undefined {
  const marker = "/node_modules/";
  const at = file.lastIndexOf(marker);
  if (at === -1) {
    return undefined;
  }
  const rest = file.slice(at + marker.length).split("/");
  const name = rest[0]?.startsWith("@") === true ? `${rest[0]}/${rest[1] ?? ""}` : (rest[0] ?? "");
  return file.slice(0, at + marker.length) + name;
}

function describe(dir: string, via?: string): Shipped {
  const manifest = readJson(join(dir, "package.json"));
  const name = String(manifest["name"]);
  const repository = manifest["repository"];
  const repo =
    typeof repository === "string" ? repository : typeof repository === "object" && repository !== null && "url" in repository ? String(repository.url) : "";
  const upstream = UPSTREAM_TEXT.get(name);
  const licenceFile = readdirSync(dir).find((file) => /^(licen[cs]e|copying)(\.(md|txt))?$/i.test(file));
  let text = licenceFile === undefined ? "" : readFileSync(join(dir, licenceFile), "utf8").trim();
  let licence = typeof manifest["license"] === "string" ? manifest["license"] : "(none declared)";
  if (upstream !== undefined) {
    const copied = readFileSync(join(ROOT, "scripts/licences", upstream.file), "utf8").trim();
    text = `${text === "" ? "" : `${text}\n\n`}From ${upstream.source}:\n\n${copied}`;
    licence = name === "vaul-vue" ? upstream.licence : `${licence} (${upstream.licence})`;
  }
  if (text === "") {
    text = `(The package carries no licence file; its package.json declares ${licence}.)`;
  }
  return { name, version: String(manifest["version"]), licence, repository: repo, text, ...(via === undefined ? {} : { via }) };
}

/** A package of the workspace, found by name (for what reaches the bundle without a module). */
function installed(name: string): string {
  const store = join(ROOT, "node_modules/.pnpm");
  const prefix = `${name.replace("/", "+")}@`;
  const entry = readdirSync(store).find((dir) => dir.startsWith(prefix));
  if (entry === undefined) {
    throw new Error(`${name} is not installed`);
  }
  return join(store, entry, "node_modules", name);
}

function shippedPackages(dist: string): Shipped[] {
  const assets = join(dist, "assets");
  if (!existsSync(assets)) {
    throw new Error(`${assets} does not exist: build first (pnpm build)`);
  }
  const files = readdirSync(assets);
  const maps = files.filter((file) => file.endsWith(".js.map"));
  if (maps.length === 0) {
    throw new Error(`${assets} has no source maps: the notices need a build with sourcemap: true`);
  }
  const dirs = new Set<string>();
  let lucide = false;
  for (const map of maps) {
    const parsed = readJson(join(assets, map));
    const sources = Array.isArray(parsed["sources"]) ? parsed["sources"].map(String) : [];
    const contents = Array.isArray(parsed["sourcesContent"]) ? parsed["sourcesContent"].map(String) : [];
    for (const [index, source] of sources.entries()) {
      const dir = packageDir(resolve(assets, source));
      if (dir !== undefined && existsSync(join(dir, "package.json"))) {
        dirs.add(realpathSync(dir));
      }
      if (source.endsWith("virtual:nuxt-ui-icons") && (contents[index] ?? "").includes('\\"prefix\\":\\"lucide\\"')) {
        lucide = true;
      }
    }
  }
  const result = [...dirs].map((dir) => describe(dir));
  if (files.some((file) => /^inter-.*\.woff2$/.test(file))) {
    result.push(describe(installed("@fontsource-variable/inter"), "the Inter font files (woff2) and their CSS"));
  }
  if (lucide) {
    result.push(describe(installed("@iconify-json/lucide"), "Lucide icon data, inlined by Nuxt UI's icon bundle"));
  }
  if (files.some((file) => file.endsWith(".css"))) {
    result.push(describe(installed("tailwindcss"), "the CSS Tailwind generates (preflight and utilities)"));
  }
  const unique = new Map(result.map((pkg) => [`${pkg.name}@${pkg.version}`, pkg]));
  return [...unique.values()].sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));
}

function notices(shipped: readonly Shipped[], listing: Listing): string {
  // The platform binaries differ per machine (only the build machine's is installed): not listed, so
  // the file is the same everywhere.
  const notShipped = [...REVIEWED]
    .filter(([name, [licence]]) => licence === "MPL-2.0" && !/^lightningcss-/.test(name))
    .map(([name, [, reason]]) => {
      const versions = (listing["MPL-2.0"] ?? []).find((pkg) => pkg.name === name)?.versions ?? [];
      return versions.length === 0 ? undefined : `- ${name} ${versions.join(", ")}: ${reason}`;
    })
    .filter((line) => line !== undefined);
  const rule = "=".repeat(78);
  const lines = [
    "THIRD-PARTY NOTICES",
    "",
    "Third-party software in the browser bundle of an application built on @flowcatalyst/ui, as",
    "measured on the gallery's production build (ui/apps/gallery). Generated by",
    "ui/scripts/check-licences.ts (`pnpm licences --write`); `pnpm licences` fails when it is out of",
    "date. An application regenerates it from its own build with --dist and --out.",
    "",
    `Shipped packages: ${String(shipped.length)}`,
    "",
    ...shipped.map((pkg) => `- ${pkg.name} ${pkg.version}: ${pkg.licence}${pkg.via === undefined ? "" : ` (${pkg.via})`}`),
    "",
    "Not shipped: build and test tools under MPL-2.0, used unmodified, whose code never reaches the",
    "bundle (listed for completeness):",
    "",
    ...notShipped,
    "",
  ];
  for (const pkg of shipped) {
    lines.push(rule, `${pkg.name} ${pkg.version}`, `Licence: ${pkg.licence}`);
    if (pkg.repository !== "") {
      lines.push(`Repository: ${pkg.repository}`);
    }
    if (pkg.via !== undefined) {
      lines.push(`Shipped as: ${pkg.via}`);
    }
    lines.push("", pkg.text, "");
  }
  return `${lines.join("\n")}\n`;
}

const listing = pnpmLicences();
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

const dist = resolve(arg("--dist") ?? join(ROOT, "apps/gallery/dist"));
const out = resolve(arg("--out") ?? join(ROOT, "THIRD-PARTY-NOTICES"));
const shipped = shippedPackages(dist);
const mplInBundle = shipped.filter((pkg) => REVIEWED.get(pkg.name)?.[0] === "MPL-2.0");
if (mplInBundle.length > 0) {
  console.error(`MPL-2.0 tools found in the bundle: ${mplInBundle.map((pkg) => pkg.name).join(", ")}`);
  process.exit(1);
}
const text = notices(shipped, listing);
if (process.argv.includes("--write")) {
  writeFileSync(out, text);
  console.log(`wrote ${out}: ${String(shipped.length)} shipped packages`);
} else if (!existsSync(out) || readFileSync(out, "utf8") !== text) {
  console.error(`${out} is out of date for ${dist}: run pnpm licences --write (after pnpm build) and commit it`);
  process.exit(1);
} else {
  console.log(`third-party notices OK: ${String(shipped.length)} shipped packages in ${out}`);
}
