// Every comparison font, light and dark, on the font page at desktop width, under the strict CSP:
// the font's files load from the gallery's origin, the page is really set in that family, and a
// full-page screenshot lands in screenshots/fonts/<font>-<scheme>.png (git-ignored). `pnpm gallery:fonts`.
import { expect, openPage, test } from "../e2e/fixtures.ts";
import { comparisonFonts } from "../src/font-list.ts";

for (const scheme of ["light", "dark"] as const) {
  for (const font of comparisonFonts) {
    test(`font ${font.id} in ${scheme}`, async ({ page, csp }) => {
      await page.setViewportSize({ width: 1440, height: 900 });
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
      await openPage(page, `/fonts?font=${font.id}`, "Font comparison");
      await expect(page.getByTestId("font-current")).toContainText(font.label);
      // The class is set once the font's CSS is in; wait for the files the page uses to load.
      await expect(page.locator("html")).toHaveClass(font.id === "ibm-plex-sans" ? /^(?!.*fc-font-)/ : new RegExp(`fc-font-${font.id}`));
      await page.evaluate(async (family) => {
        await document.fonts.load(`400 16px "${family}"`);
        await document.fonts.load(`600 16px "${family}"`);
        await document.fonts.ready;
      }, font.family);
      const used = await page.evaluate(() => getComputedStyle(document.body).fontFamily);
      expect(used.startsWith(`"${font.family}"`), `body font-family is ${used}`).toBe(true);
      expect(await page.evaluate((family) => document.fonts.check(`16px "${family}"`), font.family)).toBe(true);
      await page.waitForLoadState("networkidle");
      // The shell scrolls inside its panel, so a full-page screenshot would stop at the viewport:
      // make the viewport as tall as the page's content (width stays 1440).
      const overflow = await page.evaluate(() => {
        for (let node = document.querySelector("main")?.parentElement; node; node = node.parentElement) {
          if (node.scrollHeight > node.clientHeight && getComputedStyle(node).overflowY !== "visible") {
            return node.scrollHeight - node.clientHeight;
          }
        }
        return 0;
      });
      await page.setViewportSize({ width: 1440, height: 900 + overflow });
      await page.screenshot({ path: `screenshots/fonts/${font.id}-${scheme}.png`, fullPage: true });
      await csp.expectClean();
    });
  }
}
