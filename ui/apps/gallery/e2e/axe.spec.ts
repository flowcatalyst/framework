// docs/go/ui.md section 12: zero axe violations at WCAG 2.2 AA on every gallery page, in both themes,
// at desktop and phone width, and with the overlays open (they render outside the page's flow). Strict:
// nothing is tolerated (axe-tools.ts).
import { covers, expect, openPage, test } from "./fixtures.ts";
import { axeViolations } from "./axe-tools.ts";
import { pages } from "../src/pages.ts";

for (const scheme of ["light", "dark"] as const) {
  for (const { path, heading } of pages) {
    test(`axe: ${path} in ${scheme}`, covers("axe-aa", "strict-csp"), async ({ page, csp }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await openPage(page, path, heading);
      await expect(page.locator("html")).toHaveClass(scheme === "dark" ? /dark/ : /^(?!.*dark).*$/);
      await page.waitForLoadState("networkidle");
      expect(await axeViolations(page)).toEqual([]);
      await csp.expectClean();
    });
  }

  test(`axe: overlays open in ${scheme} (drawer, form dialog, alert dialog, filters, menus, toasts)`, covers("axe-aa", "strict-csp"), async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await openPage(page, "/orders/ord_1042", "Orders");
    await expect(page.getByTestId("fw-drawer-title")).toHaveText("ord_1042");
    expect(await axeViolations(page), "drawer").toEqual([]);
    await page.getByTestId("add-line").click();
    await expect(page.getByRole("dialog", { name: "Add a line" })).toBeVisible();
    await page.getByTestId("line-sku").fill("bad sku");
    await page.getByTestId("fw-form-submit").click();
    await expect(page.getByTestId("line-sku")).toHaveAttribute("aria-invalid", "true");
    expect(await axeViolations(page), "form dialog with an error").toEqual([]);
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog", { name: "Discard changes?" })).toBeVisible();
    expect(await axeViolations(page), "discard question over the form dialog").toEqual([]);
    await page.getByRole("button", { name: "Discard" }).click();

    await openPage(page, "/overlays", "Overlays");
    await page.getByTestId("open-alert").click();
    await expect(page.getByRole("alertdialog")).toBeVisible();
    expect(await axeViolations(page), "alert dialog").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/lists", "List parts");
    await page.getByTestId("fw-toolbar-filters").click();
    await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeVisible();
    expect(await axeViolations(page), "filters popover").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/actions", "Actions");
    await page.getByRole("button", { name: "More actions for Orders pool" }).click();
    await expect(page.getByRole("menu")).toBeVisible();
    expect(await axeViolations(page), "row menu").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/data", "Data display");
    await page.getByTestId("table-columns").click();
    await expect(page.getByRole("menu")).toBeVisible();
    expect(await axeViolations(page), "column menu").toEqual([]);
    await page.keyboard.press("Escape");

    await openPage(page, "/feedback", "Feedback");
    for (const kind of ["success", "info", "warning", "error"] as const) {
      await page.getByTestId(`flash-${kind}`).click();
    }
    await expect(page.getByRole("region", { name: /Notifications/ }).getByRole("listitem")).toHaveCount(4);
    expect(await axeViolations(page), "toasts").toEqual([]);
    await csp.expectClean();
  });
}

test("axe: every page at phone width (390 px), light and dark", covers("axe-aa", "strict-csp"), async ({ page, csp }) => {
  test.setTimeout(120_000);
  await page.setViewportSize({ width: 390, height: 844 });
  const found: string[] = [];
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    for (const { path, heading } of pages) {
      await openPage(page, path, heading);
      found.push(...(await axeViolations(page)).map((v) => `${path} ${scheme}: ${v}`));
    }
  }
  expect(found).toEqual([]);
  await csp.expectClean();
});
