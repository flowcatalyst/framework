// The orders application against the real Java demo: list, detail drawer, create, add a line,
// cancel, the error paths (validation, business rules, stale data, 5xx, 403, offline) and the
// strict CSP, with the demo's data in a real PostgreSQL.
import type { Page } from "@playwright/test";
import { cancelBehindTheScenes, expect, freshData, openPage, placeOrder, test } from "./fixtures.ts";

const rows = (page: Page) => page.getByTestId("fw-list-table").locator("tbody tr");

test.beforeEach(async () => {
  await freshData();
});

test("an empty list says so; New order creates an order and opens its drawer", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await expect(page.getByTestId("fw-list-empty")).toContainText("No orders match.");

  await page.getByTestId("new-order").click();
  const dialog = page.getByRole("dialog", { name: "New order" });
  await expect(dialog).toBeVisible();

  // Client-side validation from the generated schema: a blank customer is refused before any call.
  const posts: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith("/api/orders")) {
      posts.push(request.url());
    }
  });
  await dialog.getByRole("button", { name: "Create order" }).click();
  await expect(dialog.getByText("This field is required.")).toBeVisible();
  expect(posts).toHaveLength(0);

  await page.getByTestId("new-customer").fill("Ada Lovelace");
  await dialog.getByRole("button", { name: "Create order" }).click();
  await expect(dialog).toHaveCount(0);
  const drawer = page.getByRole("dialog", { name: /^ord_/ });
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText("Ada Lovelace");
  await expect(drawer.getByText("OPEN")).toBeVisible();
  await expect(rows(page)).toHaveCount(1);
  await expect(page.getByRole("region", { name: /Notifications/ }).getByText(/Order ord_\w+ created\./)).toBeVisible();
  await csp.expectClean();
});

test("a line is added with its int64 price as a number; the totals follow", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Grace Hopper");
  await page.goto(`/orders/${placed.id}`);
  const drawer = page.getByRole("dialog", { name: placed.id });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByText("No lines yet.")).toBeVisible();

  const bodies: string[] = [];
  page.on("request", (request) => {
    if (request.url().endsWith("/lines")) {
      bodies.push(request.postData() ?? "");
    }
  });
  await page.getByTestId("add-line").click();
  const dialog = page.getByRole("dialog", { name: "Add a line" });
  // The server's rules are the schema's: lower-case SKUs are refused in the browser.
  await page.getByTestId("line-sku").fill("punch cards");
  await dialog.getByRole("button", { name: "Add line" }).click();
  await expect(dialog.getByText("Enter a value in the expected format.")).toBeVisible();
  expect(bodies).toHaveLength(0);

  await page.getByTestId("line-sku").fill("PUNCH-CARDS");
  await page.getByTestId("line-quantity").fill("500");
  await page.getByTestId("line-price").fill("3");
  await dialog.getByRole("button", { name: "Add line" }).click();
  await expect(dialog).toHaveCount(0);
  expect(JSON.parse(bodies[0] ?? "null")).toEqual({ sku: "PUNCH-CARDS", quantity: 500, unitPriceCents: 3 });
  await expect(drawer.getByTestId("lines")).toContainText("PUNCH-CARDS");
  await expect(drawer.getByTestId("lines")).toContainText("15.00");
  // The list behind the drawer was refreshed.
  await expect(rows(page).filter({ hasText: placed.id })).toContainText("15.00");
  await csp.expectClean();
});

test("cancelling asks first; Keep order changes nothing, Cancel order cancels and disables the rest", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Ada", [{ sku: "A-1", quantity: 1, unitPriceCents: 100 }]);
  await page.goto(`/orders/${placed.id}`);
  const drawer = page.getByRole("dialog", { name: placed.id });
  await expect(drawer).toBeVisible();

  await expect(drawer.getByTestId("cancel-order")).toBeDisabled();
  await page.getByTestId("cancel-reason").fill("customer changed their mind");
  await page.getByTestId("cancel-order").click();
  const confirm = page.getByRole("alertdialog", { name: `Cancel ${placed.id}?` });
  await expect(confirm).toBeVisible();
  await confirm.getByRole("button", { name: "Keep order" }).click();
  await expect(confirm).toHaveCount(0);
  await expect(drawer.getByText("OPEN")).toBeVisible();

  await page.getByTestId("cancel-order").click();
  await page.getByRole("alertdialog", { name: `Cancel ${placed.id}?` }).getByRole("button", { name: "Cancel order" }).click();
  await expect(drawer.getByText("CANCELLED")).toBeVisible();
  await expect(page.getByTestId("add-line")).toBeDisabled();
  await expect(page.getByTestId("cancel-order")).toHaveCount(0);
  await expect(rows(page).filter({ hasText: placed.id })).toContainText(/cancelled/i);
});

test("a business-rule failure from the server is shown where the user is working", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Ada");
  await page.goto(`/orders/${placed.id}`);
  await expect(page.getByRole("dialog", { name: placed.id })).toBeVisible();
  // Another user cancels the order while this page still shows it open.
  await cancelBehindTheScenes(page, placed.id);
  await page.getByTestId("add-line").click();
  const dialog = page.getByRole("dialog", { name: "Add a line" });
  await page.getByTestId("line-sku").fill("LATE-1");
  await dialog.getByRole("button", { name: "Add line" }).click();
  // The dialog stays open with the server's message (ORDER_NOT_OPEN, 409).
  await expect(dialog.getByRole("alert")).toContainText("cancelled and cannot change");
  await expect(dialog).toBeVisible();
});

test("a stale cancel shows the 409 and refreshes the drawer to what is true now", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Ada");
  await page.goto(`/orders/${placed.id}`);
  const drawer = page.getByRole("dialog", { name: placed.id });
  await expect(drawer.getByText("OPEN")).toBeVisible();
  await cancelBehindTheScenes(page, placed.id);
  await page.getByTestId("cancel-reason").fill("me too");
  await page.getByTestId("cancel-order").click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Cancel order" }).click();
  await expect(page.getByText("The order is cancelled and cannot change.").first()).toBeVisible();
  await expect(drawer.getByText("CANCELLED")).toBeVisible();
});

test("an unknown order is a message in the drawer, not a blank page", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  await page.goto("/orders/NOPE");
  await expect(page.getByTestId("drawer-error")).toContainText("There is no such order.");
});

test("the status filter lives in the URL and survives a reload; Clear all removes it", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const open = await placeOrder(page, "Open Co");
  const gone = await placeOrder(page, "Cancelled Co");
  await cancelBehindTheScenes(page, gone.id);
  await page.reload();
  await expect(rows(page)).toHaveCount(2);

  await page.getByTestId("fw-toolbar-filters").click();
  await page.getByTestId("filter-status").click();
  await page.getByRole("option", { name: "Cancelled" }).click();
  await page.keyboard.press("Escape");
  await expect(page).toHaveURL(/\/orders\?status=CANCELLED$/);
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText(gone.id);

  await page.reload();
  await expect(rows(page)).toHaveCount(1);
  await page.getByTestId("fw-toolbar-clear").click();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(rows(page)).toHaveCount(2);
  await expect(rows(page).filter({ hasText: open.id })).toBeVisible();
});

test("more than a page of orders pages with the server's cursor, and the search narrows the loaded page", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  for (let i = 1; i <= 10; i += 1) {
    await placeOrder(page, `Customer ${String(i).padStart(2, "0")}`);
  }
  await page.reload();
  await expect(rows(page)).toHaveCount(8);
  await expect(page.getByTestId("fw-pager-older")).toBeEnabled();
  await page.getByTestId("fw-pager-older").click();
  await expect(rows(page)).toHaveCount(2);
  await expect(page.getByTestId("fw-pager-older")).toBeDisabled();
  await page.getByTestId("fw-pager-newer").click();
  await expect(rows(page)).toHaveCount(8);

  await page.getByRole("searchbox", { name: "Search" }).fill("Customer 03");
  await expect(rows(page)).toHaveCount(1);
  await page.getByRole("searchbox", { name: "Search" }).fill("zzz");
  await expect(page.getByTestId("fw-list-empty")).toContainText("No orders match.");
});

test("a server error is a toast, announced, and the list keeps what it had", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  await placeOrder(page, "Ada");
  await page.reload();
  await expect(rows(page)).toHaveCount(1);
  await page.route("**/api/orders?*", (route) => route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: "UNAVAILABLE", message: "try later" }) }));
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByText("Could not load the orders", { exact: false }).first()).toBeVisible();
  await expect(rows(page)).toHaveCount(1);
});

test("a 403 from the server is a toast; going offline is a toast, not a hang", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  await page.route("**/api/orders?*", (route) => route.fulfill({ status: 403, contentType: "application/json", body: JSON.stringify({ error: "FORBIDDEN", message: "not allowed" }) }));
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByText("not allowed", { exact: false }).first()).toBeVisible();
  await page.unroute("**/api/orders?*");
  await page.route("**/api/orders?*", (route) => route.abort("connectionrefused"));
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByText(/reach|offline|connection|network/i).first()).toBeVisible();
});

test("an unknown page is the not-found page inside the shell", async ({ page }) => {
  await openPage(page, "/nowhere", "Not found");
  await expect(page.getByTestId("fw-user-menu")).toBeVisible();
});
