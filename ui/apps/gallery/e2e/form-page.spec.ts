// FwFormPage on the generated orders client: the create form as a page of its own (/orders/new),
// with the dialog's validation (schema, the server's field errors, busy) and the discard question
// when a changed form is left by Cancel, a link or Back.
import { covers, expect, openPage, test } from "./fixtures.ts";

test("form page: opened by a link, first field focused, schema then server errors on the field, busy, then the created order's drawer", covers("form-page", "form-page-validation"), async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await page.getByTestId("new-order-page").click();
  await expect(page).toHaveURL(/\/orders\/new$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("New order");
  await expect(page.getByTestId("page-customer")).toBeFocused();
  await expect(page.getByTestId("fw-form-aside")).toBeVisible();

  // The schema: a blank customer.
  await page.getByTestId("fw-form-submit").click();
  await expect(page.getByTestId("page-customer")).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByTestId("page-customer")).toBeFocused();

  // The server: a field error on the field, busy while the call runs.
  await page.getByTestId("page-customer").fill("Blocked Ltd");
  await page.getByTestId("fw-form-submit").click();
  await expect(page.getByTestId("fw-form-cancel")).toBeDisabled();
  await expect(page.getByText("the customer is on hold")).toBeVisible();
  await expect(page.getByTestId("page-customer")).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByTestId("page-customer")).toBeFocused();
  await expect(page.getByTestId("fw-form-cancel")).toBeEnabled();

  // Saved: no question on the way out, and the created order's drawer is open over the list.
  await page.getByTestId("page-customer").fill("Tyrell");
  await page.getByTestId("fw-form-submit").click();
  await expect(page).toHaveURL(/\/orders\/ord_2\d+$/);
  await expect(page.getByRole("alertdialog")).toHaveCount(0);
  await expect(page.getByRole("dialog", { name: /ord_2/ })).toContainText("Tyrell");
  await csp.expectClean();
});

test("form page: the actions stay in view at the bottom of a short window", covers("form-page"), async ({ page, csp }) => {
  await page.setViewportSize({ width: 1280, height: 420 });
  await openPage(page, "/orders/new", "New order");
  const actions = page.getByTestId("fw-form-actions");
  await expect(actions).toBeInViewport();
  await expect(page.getByTestId("fw-form-submit")).toBeInViewport();
  await csp.expectClean();
});

test("form page: leaving with changes asks first (Cancel, the back link, Back); unchanged leaves at once", covers("form-page", "form-page-discard"), async ({ page, csp }) => {
  // Unchanged: Cancel goes straight back to the list.
  await openPage(page, "/orders", "Orders");
  await page.getByTestId("new-order-page").click();
  await expect(page.getByTestId("page-customer")).toBeFocused();
  await page.getByTestId("fw-form-cancel").click();
  await expect(page).toHaveURL(/\/orders$/);

  // Changed, Cancel: Keep editing (focused) stays with the value kept.
  await page.getByTestId("new-order-page").click();
  await page.getByTestId("page-customer").fill("Soylent");
  await page.getByTestId("fw-form-cancel").click();
  const ask = page.getByRole("alertdialog", { name: "Discard changes?" });
  await expect(ask).toBeVisible();
  await expect(ask.getByRole("button", { name: "Keep editing" })).toBeFocused();
  await ask.getByRole("button", { name: "Keep editing" }).click();
  await expect(page).toHaveURL(/\/orders\/new$/);
  await expect(page.getByTestId("page-customer")).toHaveValue("Soylent");

  // The back link: Discard leaves.
  await page.getByTestId("fw-form-back").click();
  await expect(ask).toBeVisible();
  await ask.getByRole("button", { name: "Discard" }).click();
  await expect(page).toHaveURL(/\/orders$/);

  // The browser's Back: asks too, and staying keeps the page.
  await page.getByTestId("new-order-page").click();
  await page.getByTestId("page-customer").fill("Gringotts");
  await page.goBack();
  await expect(ask).toBeVisible();
  await ask.getByRole("button", { name: "Keep editing" }).click();
  await expect(page).toHaveURL(/\/orders\/new$/);
  await expect(page.getByTestId("page-customer")).toHaveValue("Gringotts");

  // A value typed back is unchanged again.
  await page.getByTestId("page-customer").fill("");
  await page.getByTestId("fw-form-cancel").click();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(ask).toBeHidden();
  await csp.expectClean();
});
