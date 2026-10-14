// The Nuxt UI icon scan reads only the application's files, so the package lists the icons its own
// components use (fcIcons). This test fails when a source file uses an icon the list misses.
import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { expect, it } from "vitest";
import { fcIcons } from "../src/theme/index.ts";

function files(dir: string): string[] {
  return readdirSync(dir, { withFileTypes: true }).flatMap((entry) =>
    entry.isDirectory() ? files(join(dir, entry.name)) : [join(dir, entry.name)],
  );
}

it("fcIcons lists every icon the package's sources name", () => {
  const src = join(import.meta.dirname, "../src");
  const used = new Set<string>();
  for (const file of files(src).filter((f) => /\.(vue|ts)$/.test(f) && !f.endsWith("theme/index.ts"))) {
    for (const match of readFileSync(file, "utf8").matchAll(/i-lucide-[a-z0-9-]+/g)) {
      used.add(match[0]);
    }
  }
  expect([...used].sort()).toEqual([...fcIcons].sort());
});
