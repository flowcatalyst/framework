// The partials (U2) on the generated orders client, against the gallery's in-page fake server:
// FwListPage + useListState (URL state, UTable's global filter, UEmpty), FwCursorPager +
// useCursorPagination, FwRecordDrawer + useDrawerRoute (child route, Back closes, discard guard),
// FwFormDialog (client and server errors, busy, discard question, the lost-submit case) and the int64
// field that must reach the server as JSON.
import type { Page } from "@playwright/test";
import { covers, expect, openPage, test } from "./fixtures.ts";

const rows = (page: Page) => page.getByTestId("fw-list-table").locator("tbody tr");

test("list: URL-backed filters, Clear all, cursor paging, search of the loaded rows, empty state", covers("list-url-state", "list-filter-count", "list-clear-all", "list-page-search", "list-empty-clear", "cursor-paging"), async ({ page, csp }) => {
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
  const empty = page.getByTestId("fw-list-empty");
  await expect(empty).toContainText("No orders match.");
  await expect(empty).toHaveAttribute("role", "status");
  await empty.getByRole("button", { name: "Clear filters" }).click();
  await expect(rows(page)).toHaveCount(8);
  await expect(page.getByRole("searchbox", { name: "Search" })).toHaveValue("");
  await csp.expectClean();
});

test("drawer: a child route, focus on its heading, non-modal, Escape and Back close it, the list query survives", covers("drawer-child-route", "drawer-non-modal", "drawer-focus", "list-row-open"), async ({ page, csp }) => {
  await openPage(page, "/orders?status=OPEN", "Orders");
  await rows(page).filter({ hasText: "ord_1041" }).getByRole("link", { name: "ord_1041" }).click();
  await expect(page).toHaveURL(/\/orders\/ord_1041\?status=OPEN$/);
  const drawer = page.getByRole("dialog", { name: "ord_1041" });
  await expect(drawer).toBeVisible();
  await expect(page.getByTestId("fw-drawer-title")).toBeFocused();
  // The open record's row is highlighted.
  await expect(rows(page).filter({ hasText: "ord_1041" })).toHaveClass(/bg-elevated/);
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

  // A row opens by a click anywhere on it, and by Enter on the focused row.
  await rows(page).filter({ hasText: "ord_1040" }).getByRole("cell").nth(1).click();
  await expect(page).toHaveURL(/\/orders\/ord_1040\?status=OPEN$/);
  await rows(page).filter({ hasText: "ord_1041" }).focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(/\/orders\/ord_1041\?status=OPEN$/);
  await csp.expectClean();
});

test("phone: the table keeps its priority columns, nothing is clipped, the hidden values are in the drawer", covers("list-phone-columns"), async ({ page, csp }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await openPage(page, "/orders", "Orders");
  await expect(rows(page)).toHaveCount(8);
  const headers = page.getByTestId("fw-list-table").getByRole("columnheader");
  await expect(headers.filter({ visible: true })).toHaveText(["Order", "Customer", "Status"]);
  const region = page.getByTestId("fw-list-table");
  const clipped = await region.evaluate((el) => el.scrollWidth - el.clientWidth);
  expect(clipped, "the table fits the phone without scrolling sideways").toBeLessThanOrEqual(0);
  // Every visible cell is fully inside the region (the Status badge is not cut off).
  const box = await region.boundingBox();
  const badge = await rows(page).first().locator("td").nth(2).boundingBox();
  expect(box).not.toBeNull();
  expect(badge).not.toBeNull();
  expect((badge?.x ?? 0) + (badge?.width ?? 0)).toBeLessThanOrEqual((box?.x ?? 0) + (box?.width ?? 0) + 0.5);
  // The numbers the phone drops are still one tap away.
  await rows(page).first().getByRole("link").click();
  await expect(page.getByRole("dialog").getByTestId("lines")).toBeVisible();

  // A wide screen shows every column.
  await page.setViewportSize({ width: 1440, height: 900 });
  await page.goto("/orders");
  await expect(headers.filter({ visible: true })).toHaveText(["Order", "Customer", "Status", "Lines", "Total"]);
  await csp.expectClean();
});

test("drawer: on a wide screen it pushes the list aside and Filters and Refresh stay usable", covers("drawer-push"), async ({ page, csp }) => {
  await page.setViewportSize({ width: 1440, height: 900 });
  await openPage(page, "/orders", "Orders");
  const toolbar = page.getByTestId("fw-table-toolbar");
  const fullWidth = (await toolbar.boundingBox())?.width ?? 0;
  await rows(page).first().getByRole("link").click();
  const drawer = page.getByRole("dialog").first();
  await expect(drawer).toBeVisible();
  await expect(page.getByTestId("fw-drawer-title")).toBeFocused();
  // Both the slide-in and the list's padding are transitions: wait until they have settled.
  await expect
    .poll(async () => {
      const drawerBox = await drawer.boundingBox();
      const box = await toolbar.boundingBox();
      return (box?.x ?? 0) + (box?.width ?? 0) <= (drawerBox?.x ?? 0) + 0.5 && (drawerBox?.x ?? 0) < 1000;
    }, { message: "the list ends where the drawer begins" })
    .toBe(true);

  // No Escape first: the toolbar is reachable with the drawer open.
  await page.getByTestId("fw-toolbar-filters").click();
  await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeHidden();
  await page.getByTestId("fw-toolbar-refresh").click();
  await expect(page.getByRole("dialog")).toBeVisible();

  // Closing gives the width back.
  await drawer.getByRole("button", { name: "Close" }).first().click();
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect.poll(async () => (await toolbar.boundingBox())?.width ?? 0).toBeGreaterThan(fullWidth - 1);

  // Too narrow for both: the drawer overlays the list, as before.
  await page.setViewportSize({ width: 900, height: 800 });
  await rows(page).first().getByRole("link").click();
  await expect(page.getByRole("dialog").first()).toBeVisible();
  await expect.poll(async () => (await page.getByRole("dialog").first().boundingBox())?.x ?? 9999).toBeLessThan(600);
  const narrow = await toolbar.boundingBox();
  const narrowDrawer = await page.getByRole("dialog").first().boundingBox();
  expect((narrow?.x ?? 0) + (narrow?.width ?? 0)).toBeGreaterThan(narrowDrawer?.x ?? 0);
  await csp.expectClean();
});

test("drawer: leaving or switching rows with a changed note asks to discard", covers("drawer-discard"), async ({ page, csp }) => {
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

test("form dialog: first field focused, client errors focus the field, int64 posted as a JSON number", covers("form-schema", "form-number-commit", "form-int64"), async ({ page, csp }) => {
  await openPage(page, "/orders/ord_1042", "Orders");
  await page.getByTestId("add-line").click();
  const dialog = page.getByRole("dialog", { name: "Add a line" });
  await expect(dialog).toBeVisible();
  await expect(page.getByTestId("line-sku")).toBeFocused();

  await page.getByTestId("line-sku").fill("bad sku");
  await page.getByTestId("fw-form-submit").click();
  await expect(dialog.getByText("Enter a value in the expected format.")).toBeVisible();
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

test("form dialog: a 400's details land on the field, busy state, Escape on a changed form asks first", covers("form-server-errors", "form-busy", "form-discard"), async ({ page, csp }) => {
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

test("new order: the server's validation, then the drawer of the created order", covers("record-create", "form-schema"), async ({ page, csp }) => {
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

test("cancelling an order is confirmed first; a cancelled order cannot take lines", covers("destructive-confirmed", "status-badge"), async ({ page, csp }) => {
  await openPage(page, "/orders/ord_1040", "Orders");
  await page.getByTestId("cancel-order").click();
  const ask = page.getByRole("alertdialog", { name: "Cancel ord_1040?" });
  await expect(ask.getByRole("button", { name: "Keep order" })).toBeFocused();
  await ask.getByRole("button", { name: "Cancel order" }).click();
  await expect(page.getByRole("dialog", { name: "ord_1040" })).toContainText("Cancelled");
  // The status badge follows: CANCELLED is mapped to neutral, OPEN to info.
  await expect(page.getByRole("dialog", { name: "ord_1040" }).getByTestId("fw-status-badge")).toHaveAttribute("data-color", "neutral");
  await expect(rows(page).filter({ hasText: "ord_1041" }).getByTestId("fw-status-badge")).toHaveAttribute("data-color", "info");
  await expect(page.getByTestId("add-line")).toBeDisabled();
  await expect(page.getByTestId("cancel-order")).toBeDisabled();
  await csp.expectClean();
});
