// docs/go/ui.md section 11: ui-kit/theme/tokens.css is the source of the token values; kit.css
// copies them. This test fails when a copied value drifts or a copied token does not exist there.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const read = (relative: string): string => readFileSync(join(import.meta.dirname, relative), "utf8");
const tokens = read("../../../../ui-kit/theme/tokens.css");
const kit = read("../src/theme/kit.css");

/** Custom properties of the first block whose selector line is exactly `selector {`. */
function block(css: string, selector: string): Map<string, string> {
  const start = css.indexOf(`\n${selector} {`);
  if (start === -1) {
    throw new Error(`no "${selector} {" block`);
  }
  const end = css.indexOf("\n}", start);
  const body = css.slice(start, end).replace(/\/\*[\s\S]*?\*\//g, "");
  const result = new Map<string, string>();
  for (const match of body.matchAll(/(--[\w-]+):\s*([^;]+);/g)) {
    const [, name, value] = match;
    if (name !== undefined && value !== undefined) {
      result.set(name, value.trim());
    }
  }
  return result;
}

describe("kit.css copies ui-kit/theme/tokens.css", () => {
  for (const [kitSelector, tokenSelector] of [
    [":root", ":root"],
    [".dark", ".dark"],
    ["@theme", "@theme inline"],
  ] as const) {
    it(`${kitSelector}: every copied token has the kit's value`, () => {
      const source = block(tokens, tokenSelector);
      const copied = [...block(kit, kitSelector)].filter(
        ([name]) => !name.startsWith("--ui-") && !name.startsWith("--font-"),
      );
      expect(copied.length).toBeGreaterThan(0);
      for (const [name, value] of copied) {
        expect(source.has(name), `${name} is not a ui-kit token`).toBe(true);
        expect(value, name).toBe(source.get(name));
      }
    });
  }

  it("every Nuxt UI token in :root points at a copied kit token or is a literal", () => {
    const root = block(kit, ":root");
    for (const [name, value] of root) {
      if (!name.startsWith("--ui-")) {
        continue;
      }
      const reference = /^var\((--[\w-]+)\)$/.exec(value)?.[1];
      if (reference !== undefined) {
        expect(root.has(reference), `${name} -> ${reference}`).toBe(true);
      }
    }
  });
});
