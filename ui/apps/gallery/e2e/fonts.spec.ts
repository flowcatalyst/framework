// The font comparison page in the default production build: it renders in IBM Plex Sans, `?font=` is ignored
// and no comparison font file is requested or shipped (the licence notices are measured on this build).
// The sidebar's spacing (owner feedback 2026-10-14) is checked here too: 40 px items, well above the
// WCAG 2.2 target size of 24 px.
import { covers, expect, openPage, test } from "./fixtures.ts";

test.describe("font comparison page", () => {
  test("the default build ignores ?font= and requests no comparison font", covers("strict-csp"), async ({ page, csp }) => {
    const fontFiles: string[] = [];
    page.on("request", (request) => {
      if (/\.woff2?$/.test(new URL(request.url()).pathname)) {
        fontFiles.push(request.url());
      }
    });
    await openPage(page, "/fonts?font=geist", "Font comparison");
    await expect(page.getByTestId("fonts-unavailable")).toBeVisible();
    await expect(page.getByTestId("font-current")).toContainText("IBM Plex Sans (default)");
    await expect(page.locator("html")).not.toHaveClass(/fc-font-/);
    await page.waitForLoadState("networkidle");
    expect(await page.evaluate(() => getComputedStyle(document.body).fontFamily)).toMatch(/^"IBM Plex Sans Variable"/);
    expect(fontFiles.length).toBeGreaterThan(0);
    expect(fontFiles.filter((url) => !/\/ibm-plex-sans-/.test(url))).toEqual([]);
    await csp.expectClean();
  });
});

test.describe("sidebar spacing", () => {
  test("items are at least 40 px high with 4 px between them", async ({ page, csp }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openPage(page, "/shell", "Application shell");
    const links = page.getByRole("navigation", { name: "Main" }).getByRole("link");
    const first = await links.nth(0).boundingBox();
    const second = await links.nth(1).boundingBox();
    expect(first?.height).toBeGreaterThanOrEqual(40);
    expect((second?.y ?? 0) - ((first?.y ?? 0) + (first?.height ?? 0))).toBeGreaterThanOrEqual(4);
    await csp.expectClean();
  });
});
