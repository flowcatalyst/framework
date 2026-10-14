// axe at WCAG 2.2 AA (docs/go/ui.md section 12) with the one tolerated class of failures: colour
// pairs that come straight from the ui-kit's tokens (ui-kit/theme/tokens.css, copied unchanged) and
// fail AA 4.5:1. They are an owner decision on the tokens (docs/ui/u0.md, docs/ui/u1.md); every other
// violation, and every other contrast failure, fails the test.
import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";

type Result = Awaited<ReturnType<AxeBuilder["analyze"]>>["violations"][number];

export const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

/**
 * Pairs whose colours come from the kit's tokens unchanged and fail AA 4.5:1 (the owner decides on
 * the tokens; docs/ui/u1.md lists them). In light, the status and primary colours are 600 shades: as
 * text on white or on their 10 % tint (Nuxt UI's soft/subtle variants, hovers), and as a fill under
 * white text. In dark, the destructive red (#ef4444) on the card (#1e293b). Exact pairs only.
 */
export const KNOWN_TOKEN_CONTRAST: Readonly<Record<"light" | "dark", ReadonlyMap<string, string>>> = {
  light: new Map([
    ["#ffffff on #059669", "--primary emerald-600 fill, white text (3.76)"],
    ["#059669 on #ffffff", "--primary as text (link, outline) on white (3.76)"],
    ["#059669 on #e6f5f0", "--primary as text on its 10% tint (soft/subtle) (3.4)"],
    ["#ffffff on #16a34a", "--success green-600 fill, white text (3.30)"],
    ["#ffffff on #0284c7", "--info sky-600 fill, white text (4.10)"],
    ["#ffffff on #ea580c", "--warning orange-600 fill, white text (3.56)"],
    ["#16a34a on #ffffff", "--success as text on white (3.30)"],
    ["#0284c7 on #ffffff", "--info as text on white (4.10)"],
    ["#ea580c on #ffffff", "--warning as text on white (3.56)"],
    ["#2dac5c on #ffffff", "--success text at 90% opacity (alert description) (2.9)"],
    ["#1b90cd on #ffffff", "--info text at 90% opacity (alert description) (3.7)"],
    ["#e03c3c on #ffffff", "--destructive red-600 text at 90% opacity (alert description) (4.3)"],
    ["#dc2626 on #fce9e9", "--destructive as text on its 10% tint (soft/subtle) (4.2)"],
  ]),
  dark: new Map([
    ["#ef4444 on #1e293b", "dark --destructive red-500 as text on --card slate-800 (3.7)"],
    ["#ef4444 on #332c3c", "dark --destructive on its tint over the card (soft/subtle) (3.4)"],
    ["#f15757 on #1e293b", "dark --destructive at 90% opacity (alert description) (3.4)"],
  ]),
};

function contrastPair(node: Result["nodes"][number]): string {
  const data: unknown = node.any[0]?.data;
  if (typeof data === "object" && data !== null && "fgColor" in data && "bgColor" in data) {
    return `${String(data.fgColor)} on ${String(data.bgColor)}`;
  }
  return "unknown";
}

/** The violations axe finds on the page, minus the tolerated light pairs. */
export async function axeViolations(page: Page, scheme: "light" | "dark"): Promise<string[]> {
  // Let enter animations and colour transitions finish: axe reads the colours of the moment.
  await page.waitForTimeout(350);
  const result = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  return result.violations.flatMap((violation) =>
    violation.nodes
      .filter((node) => !(violation.id === "color-contrast" && KNOWN_TOKEN_CONTRAST[scheme].has(contrastPair(node))))
      .map((node) => `${violation.id}: ${node.target.join(" ")}${violation.id === "color-contrast" ? ` ${contrastPair(node)}` : ""}`),
  );
}
