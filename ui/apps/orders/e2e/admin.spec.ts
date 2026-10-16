// The admin area (M4d) against the real Java demo: an administrator, a clerk, and both. The shell's
// navigation comes from the server's /api/admin/navigation and the session's permissions; a clerk is
// refused at the router (not permitted) and at the server (403 FORBIDDEN); a failed navigation load
// is an inline error with Try again. Strict axe in both themes, desktop and 390 px; strict CSP.
import type { Page } from "@playwright/test";
import { axeViolations } from "./axe-tools.ts";
import { ADMIN, CLERK, control, expect, fetchJson, freshData, openPage, placeOrder, signInAs, test } from "./fixtures.ts";

async function signInFresh(page: Page, ...roles: string[]): Promise<void> {
  await signInAs(...roles);
  await page.context().clearCookies();
}

const sidebar = (page: Page) => page.getByRole("navigation", { name: "Main" });
const ALL_LINKS = ["About", "Event types", "Roles", "Users", "Activity", "Configuration", "Orders overview", "Back to Orders"];
const rows = (page: Page) => page.getByTestId("fw-list-table").locator("tbody tr");

test.beforeEach(async () => {
  await freshData();
});

test("an administrator lands on About: the sections the server lists, Back, breadcrumbs, the application and its health", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  await expect(page).toHaveURL(/\/admin\/about$/);

  await expect(sidebar(page).getByRole("link")).toHaveText(ALL_LINKS);
  await expect(sidebar(page).getByRole("link", { name: "About" })).toHaveAttribute("aria-current", "page");
  const crumbs = page.getByTestId("fw-admin-breadcrumbs");
  await expect(crumbs.getByRole("link", { name: "Admin" })).toBeVisible();
  await expect(crumbs.locator('[aria-current="page"]')).toHaveText("About");

  const about = page.getByTestId("fw-admin-about");
  await expect(about).toContainText("orders-demo");
  await expect(about).toContainText("test"); // APP_ENV
  await expect(about.getByTestId("fw-status-badge").first()).toHaveText("Ready");
  await csp.expectClean();
});

test("the demo's own section: Orders overview counts the client's orders", async ({ page, csp }) => {
  await signInFresh(page, ADMIN, CLERK);
  await openPage(page, "/orders", "Orders");
  await placeOrder(page, "Ada Lovelace", [{ sku: "PUNCH-CARDS", quantity: 500, unitPriceCents: 3 }]);

  // Both roles: the orders sidebar offers Administration, which leads into the area.
  await page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Administration" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  await sidebar(page).getByRole("link", { name: "Orders overview" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Orders overview" })).toBeVisible();
  const overview = page.getByTestId("admin-orders-overview");
  await expect(overview).toContainText("Open orders");
  await expect(overview.getByText("1", { exact: true })).toBeVisible();
  await expect(overview).toContainText("15.00");
  await expect(page.getByTestId("fw-admin-breadcrumbs").locator('[aria-current="page"]')).toHaveText("Orders overview");

  await sidebar(page).getByRole("link", { name: "Back to Orders" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await csp.expectClean();
});

test("a clerk has no Administration entry, is refused the route, and the server refuses the calls with FORBIDDEN", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await expect(sidebar(page).getByRole("link", { name: "Administration" })).toHaveCount(0);

  await page.goto("/admin");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();

  for (const path of [
    "/api/admin/navigation",
    "/api/admin/about",
    "/api/admin/orders/overview",
    "/api/admin/event-types",
    "/api/admin/roles",
    "/api/admin/users",
    "/api/admin/invitations",
    "/api/admin/events",
    "/api/admin/dispatch-jobs",
    "/api/admin/config",
  ]) {
    const answer = await fetchJson(page, path);
    expect(answer.status, path).toBe(403);
    expect(answer.body, path).toMatchObject({ error: "FORBIDDEN" });
  }
  await csp.expectClean();
});

test("an administrator holds no orders permission: the orders page is refused, the admin API answers", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await page.goto("/admin/about");
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  expect((await fetchJson(page, "/api/orders")).status).toBe(403);
  expect((await fetchJson(page, "/api/admin/navigation")).status).toBe(200);
  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  await csp.expectClean();
});

test("a section the server did not list is not shown, though the session holds its permission", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await page.route("**/api/admin/navigation", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({ sections: [{ id: "about", title: "About", path: "/admin/about", permission: "orders-demo:admin:console:view" }] }),
    });
  });
  await page.goto("/admin/about");
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Back to Orders"]);
  await csp.expectClean();
});

test("a navigation that cannot be loaded is an inline error with Try again, and the page still works", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await page.route("**/api/admin/navigation", async (route) => {
    await route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ error: "UNAVAILABLE", message: "down" }) });
  });
  await page.goto("/admin/about");
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  const failed = page.getByTestId("fw-admin-nav-failed");
  await expect(failed).toContainText("could not be loaded");
  await expect(sidebar(page).getByRole("link")).toHaveText(["Back to Orders"]);
  expect(await axeViolations(page), "failed navigation").toEqual([]);

  await page.unroute("**/api/admin/navigation");
  await page.getByTestId("fw-admin-nav-retry").click();
  await expect(failed).toHaveCount(0);
  await expect(sidebar(page).getByRole("link")).toHaveText(ALL_LINKS);
  await csp.expectClean();
});

test("event types: the application's own, paged by cursor, filtered, with a drawer that deep-links", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/event-types", "Event types");
  // nine of this application's event types (the platform also holds another application's: never shown)
  await expect(rows(page)).toHaveCount(8);
  await expect(page.getByTestId("fw-list-table")).not.toContainText("billing");
  await page.getByRole("button", { name: "Older" }).click();
  await expect(rows(page)).toHaveCount(1);
  await page.getByRole("button", { name: "Newest" }).click();
  await expect(rows(page)).toHaveCount(8);

  // the status filter goes to the server
  await page.getByRole("button", { name: /Filters/ }).click();
  await page.getByTestId("filter-status").click();
  await page.getByRole("option", { name: "Archived" }).click();
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText("orders-demo:stock:item:counted");
  await page.keyboard.press("Escape");

  // a drawer by deep link: fields, versions and the schema
  await page.goto("/admin/event-types/et_3");
  const drawer = page.getByRole("dialog", { name: "Order placed" });
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText("orders-demo:sales:order:placed");
  await expect(drawer.getByTestId("spec-version")).toContainText("Version 1.0");
  await expect(drawer.getByLabel("Schema of version 1.0", { exact: true })).toContainText("orderId");
  await expect(page.getByTestId("fw-admin-breadcrumbs").locator('[aria-current="page"]')).toHaveText("Event types");
  await page.getByRole("button", { name: "Close" }).first().click();
  await expect(drawer).toHaveCount(0);
  await expect(page).toHaveURL(/\/admin\/event-types$/);
  await csp.expectClean();
});

test("roles: only this application's, and a drawer lists the permissions (the role name is the route)", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/roles", "Roles");
  await expect(rows(page)).toHaveCount(2);
  await expect(page.getByTestId("fw-list-table")).toContainText("orders-demo:clerk");
  await expect(page.getByTestId("fw-list-table")).not.toContainText("billing");

  await rows(page).filter({ hasText: "orders-demo:clerk" }).getByRole("link").click();
  const drawer = page.getByRole("dialog", { name: "Clerk" });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByTestId("role-permissions")).toContainText("orders-demo:order:manage");
  await expect(page).toHaveURL(/\/admin\/roles\/orders-demo(:|%3A)clerk/);

  // another application's role is not found, by the page and by the API
  await page.goto("/admin/roles/billing:admin");
  await expect(page.getByTestId("drawer-error")).toBeVisible();
  expect((await fetchJson(page, "/api/admin/roles/billing:admin")).status).toBe(404);
  await csp.expectClean();
});

test("config: settings with their source, the client secret as set and never shown, filters, health", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/config", "Configuration");
  const table = page.getByTestId("fw-list-table");
  const secret = rows(page).filter({ hasText: "flowcatalyst.client-secret" });
  await expect(secret).toContainText("Set (secret)");
  await expect(secret).toContainText("environment variable FLOWCATALYST_CLIENT_SECRET");
  await expect(secret).toContainText("hidden");
  await expect(rows(page).filter({ hasText: "flowcatalyst.timeout" })).toContainText("Default");
  await expect(page.getByTestId("config-health")).toContainText("Ready");
  await expect(page.getByTestId("config-summary")).toContainText("using their default");
  // the secret's text is nowhere on the page or in the API's answer
  expect(await page.content()).not.toContain("fake-secret");
  expect(JSON.stringify((await fetchJson(page, "/api/admin/config")).body)).not.toContain("fake-secret");

  await page.getByRole("button", { name: /Filters/ }).click();
  await page.getByTestId("filter-state").click();
  await page.getByRole("option", { name: "Not set" }).click();
  await expect(table).not.toContainText("flowcatalyst.client-secret");
  await expect(table).toContainText("flowcatalyst.subscription-target-base-url");
  await csp.expectClean();
});

test("a platform that refuses the reads is shown on the page in the server's words, not as an empty list", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await control("/control/platform-reads?value=403");
  await openPage(page, "/admin/roles", "Roles");
  const failed = page.getByTestId("platform-error");
  await expect(failed).toContainText("service account");
  await expect(failed).not.toContainText("127.0.0.1");
  expect((await fetchJson(page, "/api/admin/event-types")).status).toBe(502);
  expect(await axeViolations(page), "platform error").toEqual([]);

  await control("/control/platform-reads?value=");
  await page.getByRole("button", { name: /Refresh/ }).click();
  await expect(failed).toHaveCount(0);
  await expect(rows(page)).toHaveCount(2);
  await csp.expectClean();
});

for (const scheme of ["light", "dark"] as const) {
  test(`axe: the admin pages in ${scheme} at desktop and 390 px`, async ({ page, csp }) => {
    await signInFresh(page, ADMIN);
    await page.emulateMedia({ colorScheme: scheme });
    for (const size of [{ width: 1280, height: 800 }, { width: 390, height: 800 }]) {
      await page.setViewportSize(size);
      for (const [path, heading] of [
        ["/admin/about", "About"],
        ["/admin/event-types", "Event types"],
        ["/admin/roles", "Roles"],
        ["/admin/users", "Users"],
        ["/admin/activity", "Activity"],
        ["/admin/config", "Configuration"],
        ["/admin/orders", "Orders overview"],
      ] as const) {
        await openPage(page, path, heading);
        await expect(page.getByTestId("fw-admin-breadcrumbs")).toBeVisible();
        expect(await axeViolations(page), `${path} ${scheme} ${String(size.width)}`).toEqual([]);
      }
    }
    await csp.expectClean();
  });
}
