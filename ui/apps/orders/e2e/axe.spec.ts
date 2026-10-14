// Zero axe violations at WCAG 2.2 AA (docs/go/ui.md section 12) on the application's pages, in both
// themes, at desktop and phone width, and with the overlays open (they render outside the page's flow);
// the strict CSP stays clean throughout. Strict: nothing is tolerated (axe-tools.ts).
import { axeViolations } from "./axe-tools.ts";
import { cancelBehindTheScenes, expect, freshData, openPage, placeOrder, signInAs, test } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

for (const scheme of ["light", "dark"] as const) {
  test(`axe: the pages in ${scheme}`, async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await openPage(page, "/orders", "Orders");
    await expect(page.locator("html")).toHaveClass(scheme === "dark" ? /dark/ : /^(?!.*dark).*$/);
    expect(await axeViolations(page), "empty list").toEqual([]);

    const open = await placeOrder(page, "Ada Lovelace", [{ sku: "PUNCH-CARDS", quantity: 500, unitPriceCents: 3 }]);
    const gone = await placeOrder(page, "Grace Hopper");
    await cancelBehindTheScenes(page, gone.id);
    await page.reload();
    await expect(page.getByTestId("fw-list-table").locator("tbody tr")).toHaveCount(2);
    expect(await axeViolations(page), "list").toEqual([]);

    await openPage(page, "/nowhere", "Not found");
    expect(await axeViolations(page), "not found").toEqual([]);

    await page.goto("/signed-out");
    await expect(page.getByRole("heading", { level: 1, name: "Signed out" })).toBeVisible();
    expect(await axeViolations(page), "signed out").toEqual([]);

    await page.goto("/sign-in-failed?error=LOGIN_REFUSED");
    await expect(page.getByRole("heading", { level: 1, name: "Sign-in failed" })).toBeVisible();
    expect(await axeViolations(page), "sign-in failed").toEqual([]);

    await signInAs();
    await page.context().clearCookies();
    await page.goto("/orders");
    await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
    expect(await axeViolations(page), "not permitted").toEqual([]);
    await signInAs("orders-demo:clerk");
    await page.context().clearCookies();

    await openPage(page, `/orders/${open.id}`, "Orders");
    await expect(page.getByRole("dialog", { name: open.id })).toBeVisible();
    await expect(page.getByTestId("lines")).toContainText("PUNCH-CARDS");
    expect(await axeViolations(page), "drawer").toEqual([]);
    await csp.expectClean();
  });

  test(`axe: overlays open in ${scheme} (form dialogs, confirm, discard, filters, menu, toasts)`, async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await openPage(page, "/orders", "Orders");
    const placed = await placeOrder(page, "Ada Lovelace");
    await page.reload();

    await page.getByTestId("new-order").click();
    await expect(page.getByRole("dialog", { name: "New order" })).toBeVisible();
    await page.getByRole("button", { name: "Create order" }).click();
    await expect(page.getByTestId("new-customer")).toHaveAttribute("aria-invalid", "true");
    expect(await axeViolations(page), "new order dialog with an error").toEqual([]);
    await page.getByTestId("new-customer").fill("Typed something");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog", { name: "Discard changes?" })).toBeVisible();
    expect(await axeViolations(page), "discard question").toEqual([]);
    await page.getByRole("button", { name: "Discard" }).click();

    await page.getByRole("link", { name: placed.id }).click();
    await expect(page.getByRole("dialog", { name: placed.id })).toBeVisible();
    await page.getByTestId("add-line").click();
    await expect(page.getByRole("dialog", { name: "Add a line" })).toBeVisible();
    await page.getByTestId("line-sku").fill("bad sku");
    await page.getByTestId("fw-form-submit").click();
    await expect(page.getByTestId("line-sku")).toHaveAttribute("aria-invalid", "true");
    expect(await axeViolations(page), "add line dialog with an error").toEqual([]);
    await page.keyboard.press("Escape");
    await page.getByRole("button", { name: "Discard" }).click();

    await page.getByTestId("cancel-reason").fill("no longer wanted");
    await page.getByTestId("cancel-order").click();
    await expect(page.getByRole("alertdialog", { name: `Cancel ${placed.id}?` })).toBeVisible();
    expect(await axeViolations(page), "confirm dialog").toEqual([]);
    await page.getByRole("button", { name: "Cancel order" }).click();
    await expect(page.getByText(`${placed.id} cancelled.`).first()).toBeVisible();
    expect(await axeViolations(page), "toast over the drawer").toEqual([]);

    // The non-modal drawer covers the toolbar's right-hand controls: close it first.
    await page.keyboard.press("Escape");
    await expect(page.getByRole("dialog")).toHaveCount(0);
    await page.getByTestId("fw-toolbar-filters").click();
    await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeVisible();
    expect(await axeViolations(page), "filters popover").toEqual([]);
    await page.keyboard.press("Escape");

    // The shell's user menu is non-modal (docs/ui/u3.md, G2), so the page is not aria-hidden under focus.
    await page.getByTestId("fw-user-menu").click();
    await expect(page.getByRole("menu")).toBeVisible();
    expect(await axeViolations(page), "user menu").toEqual([]);
    await csp.expectClean();
  });
}

test("axe: list and drawer at phone width (390 px), light and dark", async ({ page, csp }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Ada Lovelace", [{ sku: "A-1", quantity: 2, unitPriceCents: 150 }]);
  await page.reload();
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/orders");
    await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
    expect(await axeViolations(page), `list, ${scheme}`).toEqual([]);
    await page.goto(`/orders/${placed.id}`);
    await expect(page.getByRole("dialog", { name: placed.id })).toBeVisible();
    expect(await axeViolations(page), `drawer, ${scheme}`).toEqual([]);
  }
  await csp.expectClean();
});
