// docs/go/ui.md section 12: zero axe violations at WCAG 2.2 AA on every gallery page, in both themes,
// at desktop and phone width, and with the overlays open (they render outside the page's flow).
import { expect, openPage, test } from "./fixtures.ts";
import { axeViolations } from "./axe-tools.ts";
import { pages } from "../src/pages.ts";

for (const scheme of ["light", "dark"] as const) {
  for (const { path, heading } of pages) {
    test(`axe: ${path} in ${scheme}`, async ({ page, csp }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await openPage(page, path, heading);
      await expect(page.locator("html")).toHaveClass(scheme === "dark" ? /dark/ : /^(?!.*dark).*$/);
      await page.waitForLoadState("networkidle");
      expect(await axeViolations(page, scheme)).toEqual([]);
      await csp.expectClean();
    });
  }

  test(`axe: overlays open in ${scheme} (record drawer, form dialog, alert dialog, filters, row menu)`, async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await openPage(page, "/orders/ord_1042", "Orders");
    await expect(page.getByTestId("fw-drawer-title")).toHaveText("ord_1042");
    expect(await axeViolations(page, scheme), "drawer").toEqual([]);
    await page.getByTestId("add-line").click();
    await expect(page.getByRole("dialog", { name: "Add a line" })).toBeVisible();
    expect(await axeViolations(page, scheme), "form dialog").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/overlays", "Overlays");
    await page.getByTestId("open-alert").click();
    await expect(page.getByRole("alertdialog")).toBeVisible();
    expect(await axeViolations(page, scheme), "alert dialog").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/lists", "List parts");
    await page.getByTestId("fw-toolbar-filters").click();
    await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeVisible();
    expect(await axeViolations(page, scheme), "filters popover").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/actions", "Actions");
    await page.getByRole("button", { name: "More actions for Orders pool" }).click();
    await expect(page.getByRole("menu")).toBeVisible();
    expect(await axeViolations(page, scheme), "row menu").toEqual([]);
    await csp.expectClean();
  });
}

test("axe: every page at phone width (390 px), light and dark", async ({ page, csp }) => {
  test.setTimeout(120_000);
  await page.setViewportSize({ width: 390, height: 844 });
  const found: string[] = [];
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const { path, heading } of pages) {
      await openPage(page, path, heading);
      found.push(...(await axeViolations(page, scheme)).map((v) => `${path} ${scheme}: ${v}`));
    }
  }
  expect(found).toEqual([]);
  await csp.expectClean();
});
