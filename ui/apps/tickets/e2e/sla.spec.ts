// The SLA: a client's own policy (per-client configuration), the sweep that flags tickets past their
// deadline (a scheduled job the platform fires), and the notification an assignment sends (a dispatch
// job the platform delivers) with the client's own key. The control server plays the platform's part.
import { AGENT, control, expect, fetchJson, freshData, LEAD, openPage, openTicket, switchTo, test } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

test("everyone sees the policy; only a lead edits it; the server's rule is shown in the form", async ({ page, csp }) => {
  await openPage(page, "/sla", "SLA policy");
  await expect(page.getByTestId("sla-defaults")).toBeVisible();
  await expect(page.getByTestId("sla-table")).toContainText("1 h"); // urgent: 60 minutes
  await expect(page.getByTestId("sla-table")).toContainText("2 d"); // low: 2880 minutes
  await expect(page.getByTestId("edit-sla")).toHaveCount(0); // an agent may not
  const refused = await fetchJson(page, "/api/sla-policy", { method: "PUT", body: { lowMinutes: 4, normalMinutes: 3, highMinutes: 2, urgentMinutes: 1 } });
  expect(refused.status).toBe(403);

  await switchTo(page, { roles: [LEAD], sub: "prn_lena", name: "Lena Lead" });
  await openPage(page, "/sla", "SLA policy");
  await page.getByTestId("edit-sla").click();
  const dialog = page.getByRole("dialog", { name: "Edit SLA policy" });
  await expect(dialog).toBeVisible();
  // A more urgent ticket may not be given more time: the server's rule, in the form.
  await page.getByTestId("sla-urgent").fill("2000");
  await page.getByTestId("sla-urgent").press("Tab");
  await dialog.getByRole("button", { name: "Save policy" }).click();
  await expect(dialog.getByRole("alert")).toContainText("A more urgent ticket cannot be given more time.");
  await page.getByTestId("sla-urgent").fill("15");
  await page.getByTestId("sla-urgent").press("Tab");
  await page.getByTestId("sla-high").fill("120");
  await page.getByTestId("sla-high").press("Tab");
  await page.getByTestId("sla-normal").fill("600");
  await page.getByTestId("sla-normal").press("Tab");
  await page.getByTestId("sla-low").fill("1500");
  await page.getByTestId("sla-low").press("Tab");
  await dialog.getByRole("button", { name: "Save policy" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(page.getByTestId("sla-defaults")).toHaveCount(0);
  await expect(page.getByTestId("sla-table")).toContainText("15 min");
  await expect(page.getByTestId("sla-table")).toContainText("1 d 1 h"); // 1500 minutes

  // A reload says the same (it is stored), and another client still has the defaults.
  await page.reload();
  await expect(page.getByTestId("sla-table")).toContainText("15 min");
  await switchTo(page, { roles: [LEAD], client: "globex", sub: "prn_gus", name: "Gus Globex" });
  await openPage(page, "/sla", "SLA policy");
  await expect(page.getByTestId("sla-defaults")).toBeVisible();
  await csp.expectClean();
});

test("the deadline of a new ticket comes from the client's policy; the sweep marks it overdue when time runs out", async ({ page, csp }) => {
  await switchTo(page, { roles: [LEAD], sub: "prn_lena", name: "Lena Lead" });
  await openPage(page, "/sla", "SLA policy");
  await page.getByTestId("edit-sla").click();
  await page.getByTestId("sla-urgent").fill("30");
  await page.getByTestId("sla-urgent").press("Tab");
  await page.getByRole("dialog", { name: "Edit SLA policy" }).getByRole("button", { name: "Save policy" }).click();
  await expect(page.getByTestId("sla-table")).toContainText("30 min");

  const urgent = await openTicket(page, "Server room flooded", "URGENT");
  const low = await openTicket(page, "Paint the lobby", "LOW");
  await page.goto("/tickets");
  await expect(page.getByTestId("overdue-badge")).toHaveCount(0);

  // 20 minutes: the sweep finds nothing. 40 minutes: the urgent ticket (30) is flagged, the low one is not.
  await control("/control/advance?minutes=20");
  expect(JSON.parse(await control("/control/sweep"))).toMatchObject({ status: "SUCCESS", result: { flagged: 0 } });
  await control("/control/advance?minutes=20");
  expect(JSON.parse(await control("/control/sweep"))).toMatchObject({ status: "SUCCESS", result: { flagged: 1 } });

  await page.reload();
  const urgentRow = page.getByTestId("fw-list-table").locator("tbody tr").filter({ hasText: urgent.id });
  await expect(urgentRow.getByTestId("overdue-badge")).toBeVisible();
  await expect(page.getByTestId("fw-list-table").locator("tbody tr").filter({ hasText: low.id }).getByTestId("overdue-badge")).toHaveCount(0);
  await urgentRow.getByRole("link", { name: urgent.id }).click();
  await expect(page.getByTestId("drawer-overdue")).toBeVisible();
  await csp.expectClean();
});

test("an assignment notifies the assignee through the notification service with the client's own key", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  const mine = await openTicket(page, "Acme laptop");
  await fetchJson(page, `/api/tickets/${mine.id}/assign`, { method: "POST", body: { assigneeId: "prn_bob" } });
  // Assigning to yourself tells nobody anything.
  const own = await openTicket(page, "Acme monitor");
  await fetchJson(page, `/api/tickets/${own.id}/assign`, { method: "POST", body: { assigneeId: "prn_ada" } });

  await switchTo(page, { roles: [AGENT], client: "globex", sub: "prn_gus", name: "Gus Globex" });
  await openPage(page, "/tickets", "Tickets");
  const theirs = await openTicket(page, "Globex laptop");
  await fetchJson(page, `/api/tickets/${theirs.id}/assign`, { method: "POST", body: { assigneeId: "prn_gwen" } });

  expect(await control("/control/deliver-assignments")).toBe("3");
  const notifications: unknown = JSON.parse(await control("/control/notifications", "GET"));
  expect(notifications).toEqual([
    { to: "prn_bob", ticketId: mine.id, authorization: "Bearer key-acme-e2e-only" },
    { to: "prn_gwen", ticketId: theirs.id, authorization: "Bearer key-globex-e2e-only" },
  ]);
  // Delivered once: the platform does not send the same event again.
  expect(await control("/control/deliver-assignments")).toBe("0");
});
