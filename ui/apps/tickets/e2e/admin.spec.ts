// The admin area on a second application: the shared shell and the framework's About page, the
// configuration page, and the service's own Queue overview section. An agent has no way in and the
// server refuses the same calls with FORBIDDEN.
import type { Page } from "@playwright/test";
import { axeViolations } from "./axe-tools.ts";
import { ADMIN, AGENT, control, expect, fetchJson, freshData, openPage, openTicket, switchTo, test } from "./fixtures.ts";

const sidebar = (page: Page) => page.getByRole("navigation", { name: "Main" });

test.beforeEach(async () => {
  await freshData();
});

test("an administrator lands on About, sees the sections the server lists and the service's own overview", async ({ page, csp }) => {
  await openPage(page, "/tickets", "Tickets");
  await openTicket(page, "One");
  await openTicket(page, "Two");
  await switchTo(page, { roles: [ADMIN, AGENT], sub: "prn_root", name: "Root Admin" });
  await openPage(page, "/tickets", "Tickets");
  await sidebar(page).getByRole("link", { name: "Administration" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "About" })).toBeVisible();
  await expect(sidebar(page).getByRole("link")).toHaveText(["About", "Configuration", "Queue overview", "Back to Tickets"]);
  await expect(page.getByTestId("fw-admin-about")).toContainText("tickets-trial");

  await sidebar(page).getByRole("link", { name: "Queue overview" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Queue overview" })).toBeVisible();
  await expect(page.getByTestId("admin-tickets-overview")).toContainText("2");
  await expect(page.getByTestId("admin-tickets-statuses")).toContainText("Open");
  expect(await axeViolations(page), "queue overview").toEqual([]);

  await sidebar(page).getByRole("link", { name: "Configuration" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Configuration" })).toBeVisible();
  await expect(page.getByTestId("config-summary")).toContainText("set");
  // A secret is shown as set, never its value.
  const secrets = page.getByTestId("fw-list-table").locator("tbody tr").filter({ hasText: "flowcatalyst.client-secret" });
  await expect(secrets).toContainText("Set (secret)");
  await expect(page.getByTestId("fw-list-table")).not.toContainText("fake-secret");
  await csp.expectClean();
});

test("an agent has no Administration entry, is refused the route, and the server answers FORBIDDEN", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  await expect(sidebar(page).getByRole("link", { name: "Administration" })).toHaveCount(0);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  for (const path of ["/api/admin/navigation", "/api/admin/about", "/api/admin/config", "/api/admin/tickets/overview"]) {
    const refused = await fetchJson(page, path);
    expect(refused.status, path).toBe(403);
    expect(refused.body, path).toMatchObject({ error: "FORBIDDEN" });
  }
  await control("/control/reset");
});
