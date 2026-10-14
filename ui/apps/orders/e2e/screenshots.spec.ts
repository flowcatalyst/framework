// Screenshots of the application's states, light and dark, desktop and phone, for review. Runs only with
// SCREENSHOTS=1 (`pnpm --filter @flowcatalyst/orders-web screenshots`); the files go to screenshots/
// (git-ignored).
import { cancelBehindTheScenes, expect, freshData, openPage, placeOrder, test } from "./fixtures.ts";

test.skip(process.env["SCREENSHOTS"] !== "1", "set SCREENSHOTS=1 to write the review screenshots");

for (const viewport of [{ name: "desktop", width: 1440, height: 900 }, { name: "phone", width: 390, height: 844 }] as const) {
  for (const scheme of ["light", "dark"] as const) {
    test(`screenshots: ${viewport.name} ${scheme}`, async ({ page }) => {
      test.setTimeout(120_000);
      await freshData();
      await page.setViewportSize({ width: viewport.width, height: viewport.height });
      await page.emulateMedia({ colorScheme: scheme, reducedMotion: "reduce" });
      const shot = async (name: string): Promise<void> => {
        await page.waitForTimeout(400);
        await page.screenshot({ path: `screenshots/${viewport.name}-${scheme}-${name}.png` });
      };
      await openPage(page, "/orders", "Orders");
      await shot("empty");
      const first = await placeOrder(page, "Ada Lovelace", [
        { sku: "PUNCH-CARDS", quantity: 500, unitPriceCents: 3 },
        { sku: "ANALYTICAL-ENGINE", quantity: 1, unitPriceCents: 120_000_00 },
      ]);
      for (const customer of ["Grace Hopper", "Alan Turing", "Margaret Hamilton"]) {
        await placeOrder(page, customer);
      }
      const gone = await placeOrder(page, "Katherine Johnson");
      await cancelBehindTheScenes(page, gone.id);
      await page.reload();
      await expect(page.getByTestId("fw-list-table").locator("tbody tr")).toHaveCount(5);
      await shot("list");
      await page.goto(`/orders/${first.id}`);
      await expect(page.getByRole("dialog", { name: first.id })).toBeVisible();
      await shot("drawer");
      await page.getByTestId("add-line").click();
      await page.getByTestId("line-sku").fill("bad sku");
      await page.getByTestId("fw-form-submit").click();
      await shot("add-line-error");
      await page.keyboard.press("Escape");
      await page.getByRole("button", { name: "Discard" }).click();
      await page.getByTestId("cancel-reason").fill("customer changed their mind");
      await page.getByTestId("cancel-order").click();
      await shot("confirm");
    });
  }
}
