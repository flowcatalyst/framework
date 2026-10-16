// The admin area (M4d) against the real Java demo: an administrator, a clerk, and both. The shell's
// navigation comes from the server's /api/admin/navigation and the session's permissions; a clerk is
// refused at the router (not permitted) and at the server (403 FORBIDDEN); a failed navigation load
// is an inline error with Try again. Strict axe in both themes, desktop and 390 px; strict CSP.
import type { Page } from "@playwright/test";
import { axeViolations } from "./axe-tools.ts";
import { ADMIN, CLERK, expect, fetchJson, freshData, openPage, placeOrder, signInAs, test } from "./fixtures.ts";

async function signInFresh(page: Page, ...roles: string[]): Promise<void> {
  await signInAs(...roles);
  await page.context().clearCookies();
}

const sidebar = (page: Page) => page.getByRole("navigation", { name: "Main" });

test.beforeEach(async () => {
  await freshData();
});

test("an administrator lands on About: the sections the server lists, Back, breadcrumbs, the application and its health", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  await expect(page).toHaveURL(/\/admin\/about$/);

  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Orders overview", "Back to Orders"]);
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

  for (const path of ["/api/admin/navigation", "/api/admin/about", "/api/admin/orders/overview"]) {
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
  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Orders overview", "Back to Orders"]);
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
