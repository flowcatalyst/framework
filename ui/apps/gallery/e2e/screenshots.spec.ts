// Screenshots of every gallery page, light and dark, desktop and phone, for the owner's review
// (docs/go/ui.md section 12, "visual"). Not compared: approved images and the pinned Playwright
// container that makes them stable come later. Runs only with SCREENSHOTS=1 (`pnpm gallery:screenshots`);
// the files go to apps/gallery/screenshots/ (git-ignored).
import { pages } from "../src/pages.ts";
import { openPage, test } from "./fixtures.ts";

test.skip(process.env["SCREENSHOTS"] !== "1", "set SCREENSHOTS=1 to write the review screenshots");

for (const viewport of [{ name: "desktop", width: 1440, height: 900 }, { name: "phone", width: 390, height: 844 }] as const) {
  for (const scheme of ["light", "dark"] as const) {
    test(`screenshots: ${viewport.name} ${scheme}`, async ({ page }) => {
      test.setTimeout(120_000);
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
      for (const { path, heading } of pages) {
        await openPage(page, path, heading);
        await page.waitForLoadState("networkidle");
        const name = path === "/" ? "overview" : path.slice(1);
        await page.screenshot({ path: `screenshots/${viewport.name}-${scheme}-${name}.png`, fullPage: true });
      }
      await openPage(page, "/orders/ord_1041", "Orders");
      await page.waitForTimeout(400);
      await page.screenshot({ path: `screenshots/${viewport.name}-${scheme}-orders-drawer.png` });
    });
  }
}
