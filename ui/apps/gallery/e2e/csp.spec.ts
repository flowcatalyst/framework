// U0 open question 1 (docs/go/ui.md sections 9 and 18): does Nuxt UI run under the strict CSP,
// without 'unsafe-inline' for styles? Every interaction below must produce no violation.
import { expect, openPage, test } from "./fixtures.ts";

test("the response carries the strict policy", async ({ page, csp }) => {
  const response = await page.goto("/");
  const policy = response?.headers()["content-security-policy"] ?? "";
  expect(policy).toContain("style-src 'self';");
  expect(policy).toContain("script-src 'self' 'sha256-");
  expect(policy).not.toContain("unsafe");
  await csp.expectClean();
});

test("overview: shell, navigation and colour mode", async ({ page, csp }) => {
  await openPage(page, "/", "FlowCatalyst UI gallery");
  const toggle = page.getByTestId("color-mode");
  // No stored choice: "system" (light emulated) -> light -> dark -> system.
  for (const dark of [false, true, false]) {
    await toggle.click();
    await expect(page.locator("html")).toHaveClass(dark ? /dark/ : /^(?!.*dark).*$/);
  }
  expect(await page.evaluate(() => localStorage.getItem("fc-color-mode"))).toBe("auto");
  await page.getByRole("link", { name: "Forms" }).first().click();
  await expect(page.getByRole("heading", { level: 1, name: "Forms" })).toBeVisible();
  await csp.expectClean();
});

test("a stored dark choice applies before the first paint (the hashed snippet runs)", async ({ page, csp }) => {
  await page.goto("/");
  await page.evaluate(() => {
    localStorage.setItem("fc-color-mode", "dark");
  });
  await page.reload();
  // Checked before Vue mounts: the snippet in <head> set the class.
  const classAtDomContentLoaded = await page.evaluate(() => document.documentElement.className);
  expect(classAtDomContentLoaded).toContain("dark");
  await csp.expectClean();
});

test("system dark mode is followed without a stored choice", async ({ page, csp }) => {
  await page.emulateMedia({ colorScheme: "dark" });
  await openPage(page, "/", "FlowCatalyst UI gallery");
  await expect(page.locator("html")).toHaveClass(/dark/);
  await csp.expectClean();
});

test("overlays: modal, slideover, popover, hover card", async ({ page, csp }) => {
  await openPage(page, "/overlays", "Overlays");

  await page.getByTestId("open-modal").click();
  await expect(page.getByRole("dialog", { name: "Discard changes?" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await page.getByTestId("open-slideover").click();
  await expect(page.getByRole("dialog", { name: "Order 1042" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);

  await page.getByTestId("open-popover").click();
  await expect(page.getByTestId("popover-content")).toBeVisible();
  await page.keyboard.press("Escape");

  await page.getByTestId("open-hovercard").hover();
  await expect(page.getByTestId("hovercard-content")).toBeVisible();
  await page.mouse.move(0, 0);
  await expect(page.getByTestId("hovercard-content")).toBeHidden();

  await csp.expectClean();
});

test("actions: tooltip and dropdown menu", async ({ page, csp }) => {
  await openPage(page, "/actions", "Actions");
  await page.getByTestId("tooltip-trigger").hover();
  await expect(page.locator("[data-slot=content]", { hasText: "Copies the order id" })).toBeVisible();
  await page.mouse.move(0, 0);

  await page.getByTestId("open-menu").click();
  await page.getByRole("menuitem", { name: "Duplicate" }).click();
  await expect(page.getByTestId("last-action")).toHaveText("duplicate");
  await csp.expectClean();
});

test("forms: select, select menu, validation errors, submit, date range", async ({ page, csp }) => {
  await openPage(page, "/forms", "Forms");

  await page.getByTestId("status").click();
  await page.getByRole("option", { name: "Placed" }).click();
  await expect(page.getByTestId("status")).toContainText("Placed");

  await page.getByTestId("tags").click();
  await page.getByRole("option", { name: "export" }).click();
  await page.keyboard.press("Escape");

  await page.getByTestId("email").fill("not-an-email");
  await page.getByTestId("submit").click();
  const form = page.getByTestId("order-form");
  await expect(form.getByText("Not an email address")).toBeVisible();

  await page.getByTestId("email").fill("orders@acme.example");
  // Wait for the field's re-validation to finish: UForm 4.11.3 drops a submit made while the
  // input-triggered validation is still running (recorded in docs/ui/u0.md).
  await expect(form.getByText("Not an email address")).toBeHidden();
  await page.getByTestId("submit").click();
  await expect(page.getByTestId("submitted")).toHaveText("Acme x1");

  await expect(page.getByTestId("period")).toBeVisible();
  await expect(page.getByTestId("period-text")).toHaveText("2026-10-01..2026-10-14");

  await csp.expectClean();
});

test("data display: table, pagination, tabs, accordion", async ({ page, csp }) => {
  await openPage(page, "/data", "Data display");
  await expect(page.getByTestId("orders-table").locator("tbody tr")).toHaveCount(4);
  await page.getByRole("button", { name: "Page 2" }).click();
  await page.getByRole("tab", { name: "History" }).click();
  await expect(page.getByText("Placed on 2026-10-01.")).toBeVisible();
  await page.getByRole("button", { name: "Shipping" }).click();
  await expect(page.getByText("Ships from the Rotterdam warehouse.")).toBeVisible();
  await csp.expectClean();
});

test("UForm's dirty flag means 'had input', not 'differs from the initial values'", async ({ page, csp }) => {
  await openPage(page, "/forms", "Forms");
  await expect(page.getByTestId("form-dirty")).toHaveText("false");
  await page.getByTestId("customer").fill("Acme 2");
  await page.getByTestId("customer").fill("Acme");
  await expect(page.getByTestId("form-dirty")).toHaveText("true");
  await expect(page.getByTestId("differs")).toHaveText("false");
  await csp.expectClean();
});

test("UInputDate in range mode edits a start and an end", async ({ page, csp }) => {
  await openPage(page, "/forms", "Forms");
  const period = page.getByTestId("period");
  await expect(period.getByRole("spinbutton")).toHaveCount(6);
  await period.getByRole("spinbutton").nth(4).click();
  await page.keyboard.press("ArrowUp");
  await expect(page.getByTestId("period-text")).toHaveText("2026-10-01..2026-10-15");
  await csp.expectClean();
});

test("UDrawer (vaul-vue) is clean once its injected CSS is lifted into the stylesheet", async ({ page, csp }) => {
  await page.goto("/probes/drawer");
  await page.getByTestId("open-drawer").click();
  await expect(page.getByRole("dialog", { name: "Drawer" })).toBeVisible();
  await csp.expectClean();
});

test("KNOWN: UBanner with an id needs inline script and style (recorded, not allowed)", async ({ page, csp }) => {
  await page.goto("/probes/banner");
  await expect(page.getByText("A dismissible banner")).toBeVisible();
  await expect.poll(async () => (await csp.violations()).length).toBeGreaterThan(0);
});

test("KNOWN: USplitter inserts an inline cursor style while a handle is dragged", async ({ page, csp }) => {
  await page.goto("/probes/splitter");
  await expect(page.getByText("Left")).toBeVisible();
  expect(await csp.violations()).toEqual([]);
  const handle = page.locator("[data-slot=handle]");
  const box = await handle.boundingBox();
  if (box === null) {
    throw new Error("no splitter handle");
  }
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await page.mouse.down();
  await page.mouse.move(box.x + 60, box.y + box.height / 2, { steps: 5 });
  await page.mouse.up();
  await expect.poll(async () => (await csp.violations()).join("\n")).toContain("style-src");
});
