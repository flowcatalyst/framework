// The behaviours of the FlowCatalyst parts (src/behaviours.ts; docs/go/ui.md section 12: keyboard
// operation, focus trap and return, Escape, live announcements). Each test names the checklist rows it
// exercises (`covers`); every test also asserts the strict CSP stayed clean.
import { covers, expect, openPage, test } from "./fixtures.ts";

test.describe("table toolbar (FwTableToolbar)", () => {
  test("filters popover, active count in the button's name, Clear all moves focus to the search box", covers("list-filter-count", "list-clear-all"), async ({ page, csp }) => {
    await openPage(page, "/lists", "List parts");
    const state = page.getByTestId("toolbar-state");
    await expect(page.getByTestId("fw-toolbar-clear")).toHaveCount(0);

    await page.getByRole("searchbox", { name: "Search" }).fill("acme");
    await expect(state).toContainText('search="acme"');
    await expect(page.getByTestId("fw-toolbar-clear")).toBeVisible();

    const filters = page.getByTestId("fw-toolbar-filters");
    await filters.click();
    await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeVisible();
    await page.getByTestId("filter-archived").click();
    await expect(filters).toHaveAccessibleName("Filters, 1 active");
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeHidden();
    await expect(filters).toBeFocused();

    await page.getByTestId("fw-toolbar-clear").click();
    await expect(state).toContainText('search="" status=any archived=false');
    await expect(page.getByRole("searchbox", { name: "Search" })).toBeFocused();
    await expect(filters).toHaveAccessibleName("Filters");

    await page.getByTestId("fw-toolbar-refresh").click();
    await expect(state).toContainText("refreshes=1");
    await csp.expectClean();
  });
});

test.describe("cursor pager (FwCursorPager + useCursorPagination)", () => {
  test("Older, Newer and Newest; an unusable control is aria-disabled and keeps focus", covers("cursor-paging"), async ({ page, csp }) => {
    await openPage(page, "/lists", "List parts");
    const items = page.getByTestId("pager-items");
    const older = page.getByTestId("fw-pager-older");
    await expect(items).toContainText("evt_100");
    await expect(page.getByTestId("fw-pager-newest")).toHaveAttribute("aria-disabled", "true");
    await expect(page.getByTestId("fw-pager-newer")).toHaveAttribute("aria-disabled", "true");

    await older.click();
    await expect(items).toContainText("evt_97");
    await older.focus();
    await page.keyboard.press("Enter");
    await expect(items).toContainText("evt_94");
    await expect(older).toHaveAttribute("aria-disabled", "true");
    await expect(older).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(items).toContainText("evt_94");

    await page.getByTestId("fw-pager-newer").click();
    await expect(items).toContainText("evt_97");
    await page.getByTestId("fw-pager-newest").click();
    await expect(items).toContainText("evt_100");
    await expect(page.getByTestId("fw-pager-newer")).toHaveAttribute("aria-disabled", "true");
    await csp.expectClean();
  });
});

test.describe("paginator (FwPaginator)", () => {
  test("report, page links and rows per page", covers("offset-paging"), async ({ page, csp }) => {
    await openPage(page, "/lists", "List parts");
    const report = page.getByTestId("fw-paginator-report");
    await expect(report).toHaveText("Showing 1 to 50 of 1234 orders");
    await page.getByTestId("fw-paginator").getByRole("button", { name: "Page 2", exact: true }).click();
    await expect(report).toHaveText("Showing 51 to 100 of 1234 orders");
    await page.getByRole("combobox", { name: "Rows per page" }).click();
    await page.getByRole("option", { name: "250" }).click();
    await expect(report).toHaveText("Showing 1 to 250 of 1234 orders");
    await csp.expectClean();
  });
});

test.describe("pick list (FwPickList)", () => {
  test("filter, tick by keyboard, mirror with a count, remove from the right", covers("pick-list"), async ({ page, csp }) => {
    await openPage(page, "/lists", "List parts");
    const available = page.getByTestId("fw-pick-available");
    const count = page.getByTestId("fw-pick-count");
    await expect(count).toHaveText("(1)");
    await expect(count).toHaveAttribute("aria-live", "polite");

    await page.getByRole("searchbox", { name: "Filter roles" }).fill("billing");
    await expect(available.getByRole("checkbox")).toHaveCount(2);
    await available.getByRole("checkbox", { name: /Billing viewer/ }).focus();
    await page.keyboard.press("Space");
    await expect(count).toHaveText("(2)");
    await expect(page.getByTestId("fw-pick-selected")).toContainText("Billing viewer");
    await expect(page.getByTestId("picked")).toHaveText("orders:clerk, billing:viewer");

    await page.getByRole("button", { name: "Remove Order clerk" }).click();
    await expect(count).toHaveText("(1)");
    await expect(page.getByTestId("picked")).toHaveText("billing:viewer");
    await csp.expectClean();
  });
});

test.describe("toggle group (FwToggleGroup)", () => {
  test("a named group of pressed buttons; arrow keys move, Space presses; exclusive keeps one", covers("toggle-group"), async ({ page, csp }) => {
    await openPage(page, "/actions", "Actions");
    const group = page.getByRole("group", { name: "View" });
    const table = group.getByRole("button", { name: "Table" });
    const cards = group.getByRole("button", { name: "Cards" });
    await expect(table).toHaveAttribute("aria-pressed", "true");
    await table.focus();
    await page.keyboard.press("ArrowRight");
    await expect(cards).toBeFocused();
    await page.keyboard.press("Space");
    await expect(cards).toHaveAttribute("aria-pressed", "true");
    await expect(table).toHaveAttribute("aria-pressed", "false");
    await expect(page.getByTestId("toggle-view-value")).toHaveText("cards");
    await expect(page.getByRole("group", { name: "Formatting" }).getByRole("button", { name: "Underline" })).toBeDisabled();
    await csp.expectClean();
  });
});

test.describe("row actions (FwRowActions)", () => {
  test("names include the row; a disabled action says why; destructive is confirmed first", covers("row-actions", "row-action-disabled", "destructive-confirmed", "alert-dialog"), async ({ page, csp }) => {
    await openPage(page, "/actions", "Actions");
    await page.getByRole("button", { name: "Edit Orders pool" }).click();
    await expect(page.getByTestId("last-action")).toHaveText("edit pool-orders");

    const paused = page.getByRole("button", { name: "Pause Billing pool" });
    await expect(paused).toHaveAttribute("aria-disabled", "true");
    await expect(paused).toHaveAccessibleDescription("Already paused");
    await paused.dispatchEvent("click");
    await expect(page.getByTestId("last-action")).toHaveText("edit pool-orders");

    const more = page.getByRole("button", { name: "More actions for Orders pool" });
    await more.click();
    await expect(page.getByRole("menuitem", { name: /Resume/ })).toBeDisabled();
    await expect(page.getByRole("menuitem", { name: /Resume/ })).toContainText("The pool is running");
    await page.getByRole("menuitem", { name: "Delete…" }).click();
    const dialog = page.getByRole("alertdialog", { name: "Delete Orders pool?" });
    await expect(dialog).toBeVisible();
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("last-action")).toHaveText("kept Orders pool");
    await expect(more).toBeFocused();

    await more.click();
    await page.getByRole("menuitem", { name: "Delete…" }).click();
    await page.getByRole("button", { name: "Delete pool" }).click();
    await expect(page.getByTestId("last-action")).toHaveText("deleted Orders pool");
    await expect(page.getByRole("region", { name: /Notifications/ }).getByText("Orders pool deleted.")).toBeVisible();
    await csp.expectClean();
  });
});

test.describe("alert dialog and confirmations (FwAlertDialog, useConfirm)", () => {
  test("alertdialog: Cancel focused, focus trapped, the mask does not dismiss, Escape cancels, focus returns", covers("alert-dialog"), async ({ page, csp }) => {
    await openPage(page, "/overlays", "Overlays");
    const trigger = page.getByTestId("open-alert");
    await trigger.click();
    const dialog = page.getByRole("alertdialog", { name: "Revoke the API key?" });
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAccessibleDescription("Applications using it stop working at once.");
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog.getByRole("button", { name: "Revoke key" })).toBeFocused();
    await page.keyboard.press("Tab");
    await expect(dialog.getByRole("button", { name: "Cancel" })).toBeFocused();
    await page.mouse.click(5, 5);
    await expect(dialog).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(page.getByTestId("alert-result")).toHaveText("cancelled");
    await expect(trigger).toBeFocused();
    await csp.expectClean();
  });

  test("alertdialog: the confirmed action keeps it open while busy", covers("alert-busy"), async ({ page, csp }) => {
    await openPage(page, "/overlays", "Overlays");
    await page.getByTestId("open-alert").click();
    const confirm = page.getByTestId("fw-alert-confirm");
    await confirm.click();
    await expect(page.getByTestId("fw-alert-cancel")).toBeDisabled();
    await expect(page.getByRole("alertdialog")).toBeHidden();
    await expect(page.getByTestId("alert-result")).toHaveText("revoked");
    await csp.expectClean();
  });

  test("useConfirm resolves true or false and returns focus to the button", covers("confirm-promise"), async ({ page, csp }) => {
    await openPage(page, "/overlays", "Overlays");
    for (const color of ["error", "warning", "primary"] as const) {
      const button = page.getByTestId(`confirm-${color}`);
      await button.click();
      const dialog = page.getByRole("alertdialog", { name: "Cancel order 1042?" });
      await expect(dialog).toHaveAccessibleDescription("The customer is told at once. A cancelled order cannot be reopened.");
      await expect(dialog.getByRole("button", { name: "Keep order" })).toBeFocused();
      await dialog.getByRole("button", { name: "Cancel order" }).click();
      await expect(page.getByTestId("confirm-result")).toHaveText(`confirmed (${color})`);
      await expect(button).toBeFocused();
    }
    await page.getByTestId("confirm-error").click();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("confirm-result")).toHaveText("cancelled (error)");
    await csp.expectClean();
  });
});

test.describe("flash messages (useFlash, useRequestFailureToasts)", () => {
  test("announced; success leaves after 3 s, an error stays; request failures show once per kind", covers("flash-announced", "flash-timing", "request-failures"), async ({ page, csp }) => {
    await openPage(page, "/feedback", "Feedback");
    await page.getByTestId("flash-success").click();
    await page.getByTestId("flash-error").click();
    const toasts = page.getByRole("region", { name: /Notifications/ });
    const success = toasts.getByText("Order 1042 was saved.");
    const error = toasts.getByText("Could not save order 1042.");
    await expect(success).toBeVisible();
    await expect(error).toBeVisible();
    // Announced through FwAnnouncer's live regions (Reka's own announcer is aria-hidden, docs/ui/u1.md):
    // in the accessibility tree, polite for success, assertive for the error.
    await expect(page.getByRole("status").filter({ hasText: "Order 1042 was saved." })).toHaveAttribute("aria-live", "polite");
    await expect(page.getByRole("alert").filter({ hasText: "Could not save order 1042. The order was changed by someone else." })).toHaveAttribute("aria-live", "assertive");
    await page.waitForTimeout(3600);
    await expect(success).toBeHidden();
    await expect(error).toBeVisible();

    await page.getByTestId("fail-403").click();
    await page.getByTestId("fail-403").click();
    await expect(toasts.getByText("The request was refused.")).toHaveCount(1);
    await page.getByTestId("fail-500").click();
    await expect(toasts.getByText("Something went wrong.")).toBeVisible();
    await page.getByTestId("fail-network").click();
    await expect(toasts.getByText("The server could not be reached.")).toBeVisible();
    await csp.expectClean();
  });
});

test.describe("values (FwCodeBlock, FwLocalTime, FwDetailField) and empty states", () => {
  test("code block: a named, focusable scroll region; Copy writes the clipboard and says so", covers("code-copy"), async ({ page, context, csp }) => {
    await context.grantPermissions(["clipboard-read", "clipboard-write"]);
    await openPage(page, "/values", "Values and code");
    const block = page.getByTestId("code-json");
    const region = block.getByRole("generic", { name: "AddLineBody schema" }).or(block.locator("pre"));
    await expect(region).toHaveAttribute("tabindex", "0");
    await expect(block.locator("pre")).toHaveAttribute("aria-label", "AddLineBody schema");
    await block.getByRole("button", { name: "Copy AddLineBody schema" }).click();
    await expect(block.getByRole("status")).toHaveText("Copied to the clipboard");
    const copied = await page.evaluate(() => navigator.clipboard.readText());
    expect(copied).toContain('"unitPriceCents": { "type": "integer", "format": "int64" }');
    // Highlighting is text in spans: the copy is the source, byte for byte.
    expect(copied).toBe(await block.locator("pre").innerText());
    await csp.expectClean();
  });

  test("local time: the viewer's zone in the text, UTC in the title; empty and absent values", covers("local-time", "detail-fields"), async ({ page, csp }) => {
    await openPage(page, "/values", "Values and code");
    const fixed = page.getByTestId("local-time-fixed");
    await expect(fixed).toHaveAttribute("datetime", "2026-10-06T08:30:00Z");
    await expect(fixed).toHaveAttribute("title", "2026-10-06 08:30:00 UTC");
    await expect(fixed).toHaveText("6 Oct 2026, 10:30:00");
    await expect(page.getByTestId("fw-detail-field").filter({ hasText: "Reference" })).toContainText("—");
    await csp.expectClean();
  });

  test("empty state (UEmpty): what is missing and the next step", covers("empty-state"), async ({ page, csp }) => {
    await openPage(page, "/feedback", "Feedback");
    const empty = page.getByTestId("empty-jobs");
    await expect(empty).toContainText("No dispatch jobs yet");
    await empty.getByRole("button", { name: "Import jobs" }).click();
    await expect(page.getByText("Import started")).toBeVisible();
    await csp.expectClean();
  });
});

test.describe("dirty form (useDirtyForm)", () => {
  test("Save is enabled only while the values differ from the snapshot", covers("dirty-snapshot"), async ({ page, csp }) => {
    await openPage(page, "/forms", "Forms");
    const save = page.getByTestId("profile-save");
    const name = page.getByTestId("profile-name");
    await expect(save).toBeDisabled();
    await name.fill("Ada King");
    await expect(save).toBeEnabled();
    await name.fill("Ada Lovelace");
    await expect(save).toBeDisabled();
    await name.fill("Ada King");
    await save.click();
    await expect(page.getByTestId("profile-saved")).toHaveText("1");
    await expect(save).toBeDisabled();
    await csp.expectClean();
  });
});

test.describe("application shell (FwAppShell)", () => {
  test("navigation hides what the session may not open; the current page is marked; Sign out", covers("shell-nav-permissions", "shell-current-page", "shell-sign-out", "one-h1"), async ({ page, csp }) => {
    await openPage(page, "/shell", "Application shell");
    const nav = page.getByRole("navigation", { name: "Main" });
    await expect(nav.getByRole("link", { name: "Shell" })).toHaveAttribute("aria-current", "page");
    await expect(nav.getByRole("link", { name: "Administration" })).toHaveCount(0);
    await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
    await page.getByTestId("fw-user-menu").click();
    await page.getByRole("menuitem", { name: "Sign out" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Signed out" })).toBeVisible();
    await expect(page.getByTestId("signed-out-from")).toHaveText("You signed out.");
    await csp.expectClean();
  });
});
