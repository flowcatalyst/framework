import { describe, expect, it } from "vitest";
import { colorsCss } from "../src/vite.ts";

describe("colorsCss", () => {
  const css = colorsCss(
    { primary: "emerald", neutral: "slate" },
    { emerald: { "500": "#10b981", "400": "#34d399" }, slate: { "50": "#f8fafc" } },
  );

  it("writes every shade of every alias with Tailwind's value as the fallback", () => {
    expect(css).toContain("--ui-color-primary-500: var(--color-emerald-500, #10b981);");
    expect(css).toContain("--ui-color-neutral-50: var(--color-slate-50, #f8fafc);");
    expect(css.match(/--ui-color-primary-\d+:/g)).toHaveLength(11);
  });

  it("points each alias at shade 500 in light and 400 in dark, as Nuxt UI's plugin does", () => {
    expect(css).toMatch(/:root, :host, \.light \{\s+--ui-primary: var\(--ui-color-primary-500\);/);
    expect(css).toMatch(/\.dark \{\s+--ui-primary: var\(--ui-color-primary-400\);/);
    expect(css).not.toContain("--ui-neutral:");
  });

  it("maps the neutral palette to Nuxt UI's old-neutral copy", () => {
    expect(colorsCss({ neutral: "neutral" }, {})).toContain("var(--color-old-neutral-50, )");
  });
});
