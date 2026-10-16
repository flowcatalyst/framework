// A consumer outside this repository links this package (`link:`): pnpm installs nothing for a linked
// package, and the bundler resolves the package's imports from its real location, that is from this
// repository's node_modules, outside the consumer's lockfile (Camel feedback 2026-10-16, item c). So
// everything the source imports is a peerDependency (the consumer installs and pins it) except the
// one sibling package it links with.
import { readdirSync, readFileSync, statSync } from "node:fs";
import { builtinModules } from "node:module";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { z } from "zod";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const Manifest = z.object({
  dependencies: z.record(z.string(), z.string()).default({}),
  peerDependencies: z.record(z.string(), z.string()).default({}),
});
const manifest = Manifest.parse(JSON.parse(readFileSync(join(root, "package.json"), "utf8")));

function files(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? files(path) : [path];
  });
}

/** `@scope/name` or `name` of a bare specifier; undefined for relative paths, `#build` aliases and node: builtins. */
function packageOf(specifier: string): string | undefined {
  if (specifier.includes("$") || specifier.startsWith(".") || specifier.startsWith("#") || specifier.startsWith("node:")) return undefined;
  const parts = specifier.split("/");
  const name = specifier.startsWith("@") ? parts.slice(0, 2).join("/") : (parts[0] ?? specifier);
  return builtinModules.includes(name) ? undefined : name;
}

function importedPackages(): Set<string> {
  const found = new Set<string>();
  const patterns = [/\bfrom\s+["']([^"']+)["']/g, /\bimport\s+["']([^"']+)["']/g, /@import\s+["']([^"']+)["']/g];
  for (const file of files(join(root, "src"))) {
    if (!/\.(ts|vue|css)$/.test(file)) continue;
    // Code only: a comment shows imports in examples, and a template string is not an import.
    const text = readFileSync(file, "utf8")
      .replace(/\/\*[\s\S]*?\*\//g, "")
      .split("\n")
      .filter((line) => !/^\s*(\/\/|\/\*|\*)/.test(line))
      .join("\n");
    for (const pattern of patterns) {
      for (const match of text.matchAll(pattern)) {
        const name = packageOf(match[1] ?? "");
        if (name !== undefined) found.add(name);
      }
    }
  }
  return found;
}

describe("dependencies of a package consumed with link:", () => {
  it("the only dependency is the sibling package it links with", () => {
    expect(Object.keys(manifest.dependencies)).toEqual(["@flowcatalyst/api-runtime"]);
  });

  it("every package the source imports is a peer dependency (or that sibling)", () => {
    const allowed = new Set([...Object.keys(manifest.peerDependencies), ...Object.keys(manifest.dependencies)]);
    // `vite` is imported for types only, by the Vite plugin, which runs in the consumer's Vite.
    allowed.add("vite");
    const missing = [...importedPackages()].filter((name) => !allowed.has(name)).sort();
    expect(missing).toEqual([]);
  });

  it("sees the imports it is meant to see", () => {
    const found = importedPackages();
    for (const name of ["reka-ui", "@vueuse/core", "@fontsource-variable/ibm-plex-sans", "@nuxt/ui", "vue"]) {
      expect(found.has(name), name).toBe(true);
    }
  });
});
