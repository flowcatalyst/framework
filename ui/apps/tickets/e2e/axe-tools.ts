// axe at WCAG 2.2 AA, strict: every violation fails the test (the gallery's rule, docs/go/ui.md
// section 12).
import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";

type Result = Awaited<ReturnType<AxeBuilder["analyze"]>>["violations"][number];

export const WCAG_TAGS = ["wcag2a", "wcag2aa", "wcag21a", "wcag21aa", "wcag22aa"];

function contrastPair(node: Result["nodes"][number]): string {
  const data: unknown = node.any[0]?.data;
  if (typeof data === "object" && data !== null && "fgColor" in data && "bgColor" in data && "contrastRatio" in data) {
    return ` ${String(data.fgColor)} on ${String(data.bgColor)} (${String(data.contrastRatio)})`;
  }
  return "";
}

/** Every violation axe finds on the page, one line per node. */
export async function axeViolations(page: Page): Promise<string[]> {
  // Let enter animations and colour transitions finish: axe reads the colours of the moment.
  await page.waitForTimeout(350);
  const result = await new AxeBuilder({ page }).withTags(WCAG_TAGS).analyze();
  return result.violations.flatMap((violation) =>
    violation.nodes.map((node) => `${violation.id}: ${node.target.join(" ")}${violation.id === "color-contrast" ? contrastPair(node) : ""}`),
  );
}
