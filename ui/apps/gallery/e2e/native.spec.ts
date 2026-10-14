// Behaviours the platform needs that Nuxt UI's components provide when used directly (UTable's
// TanStack features, selects, menus, tabs, overlays) and the remaining FlowCatalyst rows the other specs
// do not reach (the phone sidebar, the cursor race, values). Each test names the checklist rows it
// exercises (src/behaviours.ts) and ends with the strict-CSP check.
import type { Page } from "@playwright/test";
import { covers, expect, openPage, test } from "./fixtures.ts";

test.describe("shell", () => {
  test("phone: the sidebar opens from the navbar as a slideover and closes with Escape", covers("shell-phone-sidebar"), async ({ page, csp }) => {
    await page.setViewportSize({ width: 390, height: 844 });
    await openPage(page, "/shell", "Application shell");
    await expect(page.getByRole("navigation", { name: "Main" })).toBeHidden();
    const open = page.getByRole("button", { name: "Open sidebar" });
    await open.click();
    const nav = page.getByRole("navigation", { name: "Main" });
    await expect(nav).toBeVisible();
    await expect(nav.getByRole("link", { name: "Shell" })).toHaveAttribute("aria-current", "page");
    await page.keyboard.press("Escape");
    await expect(nav).toBeHidden();
    await csp.expectClean();
  });
});

test.describe("lists", () => {
  test("cursor pager: a slow Older answer that arrives after Start again is dropped", covers("cursor-race"), async ({ page, csp }) => {
    await openPage(page, "/lists", "List parts");
    const items = page.getByTestId("pager-items");
    await expect(items).toContainText("evt_100");
    // Older answers in 600 ms, the first page in 60 ms (ListsPage.vue).
    await page.getByTestId("fw-pager-older").click();
    await page.getByTestId("pager-restart").click();
    await expect(items).toContainText("evt_100");
    await page.waitForTimeout(900);
    await expect(items).toContainText("evt_100");
    await expect(items).not.toContainText("evt_97");
    await expect(page.getByTestId("fw-cursor-pager")).toContainText("Page 1");
    await expect(page.getByTestId("fw-pager-newer")).toHaveAttribute("aria-disabled", "true");
    await csp.expectClean();
  });
});

test.describe("feedback and values", () => {
  test("progress is named: a bar, an indeterminate bar, a busy button", covers("loading-named"), async ({ page, csp }) => {
    await openPage(page, "/feedback", "Feedback");
    await expect(page.getByRole("progressbar", { name: "Import progress, 40%" })).toHaveAttribute("aria-valuenow", "40");
    await expect(page.getByRole("progressbar", { name: "Loading orders" })).toBeVisible();
    const busy = page.getByTestId("busy-button");
    await expect(busy).toHaveAccessibleName("Saving");
    await expect(busy).toBeDisabled();
    await csp.expectClean();
  });

  test("status badges: mapped colours, sentence case, unmapped neutral", covers("status-badge"), async ({ page, csp }) => {
    await openPage(page, "/feedback", "Feedback");
    const badges = page.getByTestId("status-badges").getByTestId("fw-status-badge");
    await expect(badges).toHaveText(["Open", "Placed", "On hold", "Cancelled", "Failed", "In review"]);
    await expect(badges.filter({ hasText: "Failed" })).toHaveAttribute("data-color", "error");
    await expect(badges.filter({ hasText: "In review" })).toHaveAttribute("data-color", "neutral");
    await csp.expectClean();
  });

  test("sections take the next heading level; a danger zone is marked", covers("section-headings"), async ({ page, csp }) => {
    await openPage(page, "/values", "Values and code");
    await expect(page.getByRole("heading", { level: 3, name: "Order" })).toBeVisible();
    await expect(page.getByTestId("danger-zone").getByRole("heading", { level: 3, name: "Danger zone" })).toHaveClass(/text-error/);
    await csp.expectClean();
  });

  test("JSON and event codes: text only, a named focusable region, the code as its text", covers("json-block", "code-chips"), async ({ page, csp }) => {
    await openPage(page, "/values", "Values and code");
    const json = page.getByTestId("json-payload");
    await expect(json).toHaveAttribute("tabindex", "0");
    await expect(json).toHaveAccessibleName("Order payload");
    await expect(json).toContainText('    {\n      "sku": "BOLT-M8",');
    const chips = page.getByTestId("fw-code-chips").first();
    expect((await chips.innerText()).replace(/\s/g, "")).toBe("orders:sales:order:placed");
    await csp.expectClean();
  });
});

test.describe("forms (Nuxt UI used directly)", () => {
  test("select, multi-select and the clearable filter select by keyboard", covers("select-keyboard"), async ({ page, csp }) => {
    await openPage(page, "/forms", "Forms");
    const status = page.getByTestId("status");
    await status.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("option", { name: "Open" })).toBeFocused();
    await page.keyboard.press("ArrowDown");
    await expect(page.getByRole("option", { name: "Placed" })).toBeFocused();
    await page.keyboard.press("Enter");
    await expect(status).toContainText("Placed");
    await expect(status).toBeFocused();

    // Multiple: the menu's search box narrows, Enter picks the highlighted option.
    await page.getByTestId("tags").focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("listbox")).toBeVisible();
    await page.keyboard.type("exp");
    await expect(page.getByRole("option")).toHaveText(["export"]);
    await page.keyboard.press("Enter");
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("tags")).toContainText("export");
    await expect(page.getByRole("listbox")).toHaveCount(0);

    const filter = page.getByTestId("status-filter");
    await filter.focus();
    await page.keyboard.press("Enter");
    await expect(page.getByRole("listbox")).toBeVisible();
    await page.keyboard.type("canc");
    await expect(page.getByRole("option")).toHaveText(["Cancelled"]);
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("status-filter-value")).toHaveText("CANCELLED");
    // The clear control is a pointer target inside the select (Nuxt UI 4.11.3: tabindex -1).
    await filter.locator("[data-slot=trailingClear]").click();
    await expect(page.getByTestId("status-filter-value")).toHaveText("any");
    await csp.expectClean();
  });

  test("autocomplete: typing narrows, arrow keys and Enter choose", covers("autocomplete"), async ({ page, csp }) => {
    await openPage(page, "/forms", "Forms");
    const input = page.getByRole("combobox", { name: "Customer" });
    await input.fill("ini");
    const options = page.getByRole("option");
    await expect(options).toHaveText(["Initech"]);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("customer-chosen")).toHaveText("Initech");
    await csp.expectClean();
  });
});

test.describe("actions (Nuxt UI used directly)", () => {
  test("menu by keyboard, destructive last", covers("menu-keyboard"), async ({ page, csp }) => {
    await openPage(page, "/actions", "Actions");
    await page.getByTestId("open-menu").focus();
    await page.keyboard.press("Enter");
    const items = page.getByRole("menu").getByRole("menuitem");
    await expect(items).toHaveText(["Edit", "Duplicate", "Delete…"]);
    await page.keyboard.press("ArrowDown");
    await page.keyboard.press("Enter");
    await expect(page.getByTestId("last-action")).toHaveText("duplicate");
    await expect(page.getByTestId("open-menu")).toBeFocused();
    await csp.expectClean();
  });

  test("a tooltip shows on keyboard focus", covers("tooltip-focus"), async ({ page, csp }) => {
    await openPage(page, "/actions", "Actions");
    // Reached with Tab from the control before it. In view first: Reka closes a tooltip when a scroll
    // container around its trigger scrolls, which focusing an off-screen trigger does (found here).
    await page.getByTestId("tooltip-trigger").evaluate(async (element) => {
      element.scrollIntoView({ block: "center", behavior: "instant" });
      // The scroll event is dispatched on the next frame.
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    });
    await page.getByRole("button", { name: "More actions for Billing pool" }).focus();
    await page.keyboard.press("Tab");
    const trigger = page.getByTestId("tooltip-trigger");
    await expect(trigger).toBeFocused();
    await expect(page.locator("[data-slot=content]", { hasText: "Copies the order id" })).toBeVisible();
    // While open, the trigger is described by the tooltip's text.
    await expect(trigger).toHaveAccessibleDescription("Copies the order id");
    await csp.expectClean();
  });
});

test.describe("data display (Nuxt UI used directly)", () => {
  const body = (page: Page) => page.getByTestId("orders-table").locator("tbody tr");

  test("UTable: sort from the headers (aria-sort), page the loaded rows", covers("table-sort", "table-paginate"), async ({ page, csp }) => {
    await openPage(page, "/data", "Data display");
    const table = page.getByTestId("orders-table");
    await expect(table.getByRole("columnheader", { name: "Customer" })).toHaveAttribute("aria-sort", "ascending");
    await expect(body(page).first()).toContainText("Acme");
    await table.getByRole("button", { name: "Order" }).click();
    await expect(table.getByRole("columnheader", { name: "Order" })).toHaveAttribute("aria-sort", "ascending");
    await expect(body(page).first()).toContainText("ord_1040");
    await table.getByRole("button", { name: "Order" }).click();
    await expect(table.getByRole("columnheader", { name: "Order" })).toHaveAttribute("aria-sort", "descending");
    await expect(body(page).first()).toContainText("ord_1051");

    await expect(body(page)).toHaveCount(5);
    await page.getByTestId("table-pagination").getByRole("button", { name: "Page 3" }).click();
    await expect(body(page)).toHaveCount(2);
    await expect(body(page).first()).toContainText("ord_1041");
    await csp.expectClean();
  });

  test("UTable: select rows with checkboxes, filter, pick columns", covers("table-select-rows", "table-filter-columns"), async ({ page, csp }) => {
    await openPage(page, "/data", "Data display");
    const table = page.getByTestId("orders-table");
    const selected = page.getByTestId("table-selected");
    await table.getByRole("checkbox", { name: "Select ord_1040" }).click();
    await expect(selected).toHaveText("1 of 12 selected");
    await table.getByRole("checkbox", { name: "Select all on this page" }).click();
    await expect(selected).toHaveText("5 of 12 selected");

    await page.getByTestId("table-filter").fill("glo");
    await expect(body(page)).toHaveCount(1);
    await expect(body(page).first()).toContainText("Globex");
    await page.getByTestId("table-filter").fill("");
    await expect(body(page)).toHaveCount(5);

    await page.getByTestId("table-columns").click();
    await page.getByRole("menuitemcheckbox", { name: "Status" }).click();
    await page.keyboard.press("Escape");
    await expect(table.getByRole("columnheader", { name: "Status" })).toHaveCount(0);
    await expect(table.getByRole("columnheader", { name: "Customer" })).toHaveCount(1);
    await csp.expectClean();
  });

  test("UTabs: arrow keys move and select, the disabled tab is skipped", covers("tabs-keyboard"), async ({ page, csp }) => {
    await openPage(page, "/data", "Data display");
    const tabs = page.getByTestId("tabs");
    await tabs.getByRole("tab", { name: "Lines" }).focus();
    await page.keyboard.press("ArrowRight");
    await expect(tabs.getByRole("tab", { name: "History" })).toBeFocused();
    await expect(page.getByText("Placed on 2026-10-01.")).toBeVisible();
    await page.keyboard.press("ArrowRight");
    await expect(tabs.getByRole("tab", { name: "Notes" })).toBeFocused();
    await expect(page.getByText("No notes.")).toBeVisible();
    await csp.expectClean();
  });
});

test.describe("overlays (Nuxt UI used directly)", () => {
  test("UModal traps focus, closes on Escape and returns focus; UPopover closes on Escape and returns focus", covers("modal-dialog", "popover"), async ({ page, csp }) => {
    await openPage(page, "/overlays", "Overlays");
    const trigger = page.getByTestId("open-modal");
    await trigger.click();
    const dialog = page.getByRole("dialog", { name: "Discard changes?" });
    await expect(dialog).toBeVisible();
    for (let i = 0; i < 4; i += 1) {
      await page.keyboard.press("Tab");
      expect(await dialog.evaluate((element) => element.contains(document.activeElement))).toBe(true);
    }
    await page.keyboard.press("Escape");
    await expect(dialog).toBeHidden();
    await expect(trigger).toBeFocused();

    const popover = page.getByTestId("open-popover");
    await popover.click();
    await expect(page.getByTestId("popover-content")).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("popover-content")).toBeHidden();
    await expect(popover).toBeFocused();
    await csp.expectClean();
  });
});
