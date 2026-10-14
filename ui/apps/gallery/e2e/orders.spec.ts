// The partials (U2) on the generated orders client, against the gallery's in-page fake server:
// FwListPage + useListState (URL state), FwCursorPager + useCursorPagination, FwRecordDrawer +
// useDrawerRoute (child route, Back closes, discard guard), FwFormDialog (client and server errors,
// busy, discard question, the lost-submit case) and the int64 field that must reach the server as JSON.
import type { Page } from "@playwright/test";
import { expect, openPage, test } from "./fixtures.ts";

const rows = (page: Page) => page.getByTestId("fw-list-table").locator("tbody tr");

test("list: URL-backed filters, Clear all, cursor paging", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await expect(rows(page)).toHaveCount(8);
  await expect(rows(page).first()).toContainText("ord_1042");

  await page.getByTestId("fw-toolbar-filters").click();
  await page.getByTestId("filter-status").click();
  await page.getByRole("option", { name: "Cancelled" }).click();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/\/orders\?status=CANCELLED$/);
  await expect(rows(page)).toHaveCount(3);
  await expect(page.getByTestId("fw-toolbar-filters")).toHaveAccessibleName("Filters, 1 active");

  await page.reload();
  await expect(rows(page)).toHaveCount(3);
  await page.getByTestId("fw-toolbar-clear").click();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(rows(page)).toHaveCount(8);

  await page.getByTestId("fw-pager-older").click();
  await expect(rows(page).first()).toContainText("ord_1034");
  await page.getByTestId("fw-pager-newer").click();
  await expect(rows(page).first()).toContainText("ord_1042");

  await page.getByRole("searchbox", { name: "Search" }).fill("globex");
  await expect(page).toHaveURL(/q=globex/);
  await expect(rows(page)).toHaveCount(1);
  await page.getByRole("searchbox", { name: "Search" }).fill("zzz");
  await expect(page.getByTestId("fw-empty-state")).toContainText("No orders match.");
  await page.getByTestId("fw-empty-state").getByRole("button", { name: "Clear filters" }).click();
  await expect(rows(page)).toHaveCount(8);
  await csp.expectClean();
});

test("drawer: a child route, focus on its heading, non-modal, Escape and Back close it, the list query survives", async ({ page, csp }) => {
  await openPage(page, "/orders?status=OPEN", "Orders");
  await rows(page).filter({ hasText: "ord_1041" }).getByRole("link", { name: "ord_1041" }).click();
  await expect(page).toHaveURL(/\/orders\/ord_1041\?status=OPEN$/);
  const drawer = page.getByRole("dialog", { name: "ord_1041" });
  await expect(drawer).toBeVisible();
  await expect(page.getByTestId("fw-drawer-title")).toBeFocused();
  await expect(drawer.getByTestId("lines")).toContainText("NUT-M8");

  // Non-modal: the list stays usable and another row switches the record.
  await rows(page).filter({ hasText: "ord_1040" }).getByRole("link", { name: "ord_1040" }).click();
  await expect(page).toHaveURL(/\/orders\/ord_1040\?status=OPEN$/);
  await expect(page.getByRole("dialog", { name: "ord_1040" })).toBeVisible();

  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/\/orders\?status=OPEN$/);
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Back reopens the record the user came from; Back again leaves it.
  await page.goBack();
  await expect(page).toHaveURL(/\/orders\/ord_1040/);
  await page.goBack();
  await expect(page).toHaveURL(/\/orders\/ord_1041/);

  // A deep link opens the drawer over the list.
  await page.goto("/orders/ord_1039?status=OPEN");
  await expect(page.getByRole("dialog", { name: "ord_1039" })).toBeVisible();
  await expect(rows(page).first()).toBeVisible();
  await csp.expectClean();
});

test("drawer: leaving or switching rows with a changed note asks to discard", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await rows(page).filter({ hasText: "ord_1042" }).getByRole("link", { name: "ord_1042" }).click();
  await page.getByTestId("note").fill("call the customer");
  await page.goBack();
  const ask = page.getByRole("alertdialog", { name: "Discard changes?" });
  await expect(ask).toBeVisible();
  await expect(ask.getByRole("button", { name: "Keep editing" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog", { name: "ord_1042" })).toBeVisible();
  await expect(page.getByTestId("note")).toHaveValue("call the customer");

  await rows(page).filter({ hasText: "ord_1041" }).getByRole("link", { name: "ord_1041" }).click();
  await expect(ask).toBeVisible();
  await ask.getByRole("button", { name: "Discard" }).click();
  await expect(page).toHaveURL(/\/orders\/ord_1041$/);
  await expect(page.getByTestId("note")).toHaveValue("");

  // The drawer's own close asks too, and a cancelled close keeps it.
  await page.getByTestId("note").fill("x");
  await page.getByRole("dialog", { name: "ord_1041" }).getByRole("button", { name: "Close" }).first().click();
  await expect(ask).toBeVisible();
  await ask.getByRole("button", { name: "Discard" }).click();
  await expect(page).toHaveURL(/\/orders$/);
  await csp.expectClean();
});

test("form dialog: first field focused, client errors focus the field, int64 posted as a JSON number", async ({ page, csp }) => {
  await openPage(page, "/orders/ord_1042", "Orders");
  await page.getByTestId("add-line").click();
  const dialog = page.getByRole("dialog", { name: "Add a line" });
  await expect(dialog).toBeVisible();
  await expect(page.getByTestId("line-sku")).toBeFocused();

  await page.getByTestId("line-sku").fill("bad sku");
  await page.getByTestId("fw-form-submit").click();
  await expect(dialog.getByText("Invalid string: must match pattern")).toBeVisible();
  await expect(page.getByTestId("line-sku")).toBeFocused();
  await expect(page.getByTestId("line-sku")).toHaveAttribute("aria-invalid", "true");

  // The "lost submit" case of docs/ui/u0.md: fix the field and click Submit at once.
  // The price is typed and Submit clicked at once: the number field commits its text on blur, which
  // the dialog triggers itself after the click (it keeps the focus in the field through mousedown).
  await page.getByTestId("line-sku").fill("WIDGET-9");
  await page.getByTestId("line-price").fill("1999");
  await page.getByTestId("fw-form-submit").click();
  await expect(dialog).toBeHidden();
  await expect(page.getByTestId("last-body")).toHaveText('{"sku":"WIDGET-9","quantity":1,"unitPriceCents":1999}');
  await expect(page.getByTestId("lines")).toContainText("WIDGET-9");
  await expect(page.getByRole("status").filter({ hasText: "Line WIDGET-9 added." })).toBeAttached();
  await csp.expectClean();
});

test("form dialog: a 400's details land on the field, busy state, Escape on a changed form asks first", async ({ page, csp }) => {
  await openPage(page, "/orders/ord_1041", "Orders");
  await page.getByTestId("add-line").click();
  const dialog = page.getByRole("dialog", { name: "Add a line" });
  await page.getByTestId("line-sku").fill("NUT-M8");
  await page.getByTestId("fw-form-submit").click();
  await expect(page.getByTestId("fw-form-cancel")).toBeDisabled();
  await expect(dialog.getByText("the order already has this SKU")).toBeVisible();
  await expect(page.getByTestId("line-sku")).toHaveAttribute("aria-invalid", "true");
  await expect(page.getByTestId("line-sku")).toBeFocused();
  await expect(page.getByTestId("fw-form-cancel")).toBeEnabled();

  await page.keyboard.press("Escape");
  const ask = page.getByRole("alertdialog", { name: "Discard changes?" });
  await expect(ask).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeVisible();
  await page.getByTestId("fw-form-cancel").click();
  await ask.getByRole("button", { name: "Discard" }).click();
  await expect(dialog).toBeHidden();

  // An unchanged form closes without asking.
  await page.getByTestId("add-line").click();
  await expect(page.getByTestId("line-sku")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(dialog).toBeHidden();
  await expect(ask).toBeHidden();
  await csp.expectClean();
});

test("new order: the server's validation, then the drawer of the created order", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await page.getByTestId("new-order").click();
  const dialog = page.getByRole("dialog", { name: "New order" });
  await page.getByTestId("fw-form-submit").click();
  await expect(dialog.locator("[aria-invalid=true]")).toHaveCount(1);
  await page.getByTestId("new-customer").fill("Cyberdyne");
  await page.getByTestId("fw-form-submit").click();
  await expect(dialog).toBeHidden();
  await expect(page).toHaveURL(/\/orders\/ord_2\d+$/);
  await expect(page.getByRole("dialog", { name: /ord_2/ })).toContainText("Cyberdyne");
  await expect(rows(page).first()).toContainText("Cyberdyne");
  await csp.expectClean();
});

test("cancelling an order is confirmed first; a cancelled order cannot take lines", async ({ page, csp }) => {
  await openPage(page, "/orders/ord_1040", "Orders");
  await page.getByTestId("cancel-order").click();
  const ask = page.getByRole("alertdialog", { name: "Cancel ord_1040?" });
  await expect(ask.getByRole("button", { name: "Keep order" })).toBeFocused();
  await ask.getByRole("button", { name: "Cancel order" }).click();
  await expect(page.getByRole("dialog", { name: "ord_1040" })).toContainText("Cancelled");
  await expect(page.getByTestId("add-line")).toBeDisabled();
  await expect(page.getByTestId("cancel-order")).toBeDisabled();
  await csp.expectClean();
});
