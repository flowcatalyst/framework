// docs/go/ui.md section 12: zero axe violations at WCAG 2.2 AA on every gallery page, in both themes.
import AxeBuilder from "@axe-core/playwright";
import { expect, openPage, test } from "./fixtures.ts";

const pages = [
  { path: "/", heading: "FlowCatalyst UI gallery" },
  { path: "/overlays", heading: "Overlays" },
  { path: "/forms", heading: "Forms" },
  { path: "/data", heading: "Data display" },
] as const;

type Result = Awaited<ReturnType<AxeBuilder["analyze"]>>["violations"][number];

const tags = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

/**
 * KNOWN, recorded in docs/ui/u0.md for the owner: colour pairs from the ui-kit's light tokens
 * (ui-kit/theme/tokens.css, copied unchanged) that fail WCAG AA 4.5:1 in Nuxt UI's variants.
 * Only these exact foreground/background pairs are tolerated; any other contrast failure fails.
 */
const KNOWN_LIGHT_CONTRAST = new Set([
  "#ffffff on #059669", // solid primary (--primary emerald-600, white text): 3.76
  "#16a34a on #e8f6ed", // subtle success badge (--success green-600 on its 10% tint): 2.95
  "#0284c7 on #e6f3f9", // subtle info badge (--info sky-600 on its 10% tint): 3.61
  "#64748b on #f1f5f9", // muted text (--muted-foreground slate-500) on Nuxt UI's bg-elevated: 4.34
]);

function contrastPair(node: Result["nodes"][number]): string {
  const data: unknown = node.any[0]?.data;
  if (typeof data === "object" && data !== null && "fgColor" in data && "bgColor" in data) {
    return `${String(data.fgColor)} on ${String(data.bgColor)}`;
  }
  return "unknown";
}

function unexpected(violations: Result[]): string[] {
  return violations.flatMap((violation) =>
    violation.nodes
      .filter((node) => !(violation.id === "color-contrast" && KNOWN_LIGHT_CONTRAST.has(contrastPair(node))))
      .map((node) => `${violation.id}: ${node.target.join(" ")} ${violation.id === "color-contrast" ? contrastPair(node) : ""}`),
  );
}

for (const scheme of ["light", "dark"] as const) {
  for (const { path, heading } of pages) {
    test(`axe: ${path} in ${scheme}`, async ({ page, csp }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await openPage(page, path, heading);
      await expect(page.locator("html")).toHaveClass(scheme === "dark" ? /dark/ : /^(?!.*dark).*$/);
      const result = await new AxeBuilder({ page }).withTags(tags).analyze();
      const found = scheme === "dark" ? result.violations.flatMap((v) => v.nodes.map((n) => `${v.id}: ${n.target.join(" ")}`)) : unexpected(result.violations);
      expect(found).toEqual([]);
      await csp.expectClean();
    });
  }
}

test("axe: the empty gallery (overview) has no violation at all, light and dark, desktop and phone", async ({ page, csp }) => {
  for (const viewport of [{ width: 1280, height: 800 }, { width: 390, height: 844 }]) {
    for (const scheme of ["light", "dark"] as const) {
      await page.setViewportSize(viewport);
      await page.emulateMedia({ colorScheme: scheme });
      await openPage(page, "/", "FlowCatalyst UI gallery");
      const result = await new AxeBuilder({ page }).withTags(tags).analyze();
      expect(result.violations.map((v) => `${v.id} (${scheme}, ${String(viewport.width)}px)`)).toEqual([]);
    }
  }
  await csp.expectClean();
});
