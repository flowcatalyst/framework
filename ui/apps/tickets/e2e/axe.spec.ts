// Zero axe violations at WCAG 2.2 AA on the application's pages, in both themes, with the overlays
// open and at phone width; the strict CSP stays clean throughout. Strict: nothing is tolerated.
import { axeViolations } from "@flowcatalyst/ui/e2e";
import { behindTheScenes, expect, freshData, openPage, openTicket, test } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

for (const scheme of ["light", "dark"] as const) {
  test(`axe: the pages in ${scheme}`, async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await openPage(page, "/tickets", "Tickets");
    expect(await axeViolations(page), "empty list").toEqual([]);

    const first = await openTicket(page, "Printer on fire", "URGENT");
    await openTicket(page, "Slow wifi", "LOW");
    await behindTheScenes(page, `/api/tickets/${first.id}/assign`, { assigneeId: "prn_ada" });
    await page.reload();
    await expect(page.getByTestId("fw-list-table").locator("tbody tr")).toHaveCount(2);
    expect(await axeViolations(page), "list").toEqual([]);

    await page.goto(`/tickets/${first.id}`);
    await expect(page.getByRole("dialog", { name: first.id })).toBeVisible();
    await expect(page.getByTestId("start")).toBeVisible();
    expect(await axeViolations(page), "drawer").toEqual([]);

    await openPage(page, "/sla", "SLA policy");
    await expect(page.getByTestId("sla-defaults")).toBeVisible();
    expect(await axeViolations(page), "sla").toEqual([]);

    await openPage(page, "/nowhere", "Not found");
    expect(await axeViolations(page), "not found").toEqual([]);
    await csp.expectClean();
  });

  test(`axe: overlays open in ${scheme} (new ticket, resolve, comment, confirm, filters, user menu)`, async ({ page, csp }) => {
    await page.emulateMedia({ colorScheme: scheme });
    await openPage(page, "/tickets", "Tickets");
    const ticket = await openTicket(page, "Printer", "HIGH");
    await behindTheScenes(page, `/api/tickets/${ticket.id}/assign`, { assigneeId: "prn_ada" });
    await behindTheScenes(page, `/api/tickets/${ticket.id}/transition`, { action: "START" });
    await page.reload();

    await page.getByTestId("new-ticket").click();
    await expect(page.getByRole("dialog", { name: "New ticket" })).toBeVisible();
    await page.getByRole("button", { name: "Open ticket" }).click();
    await expect(page.getByTestId("new-subject")).toHaveAttribute("aria-invalid", "true");
    expect(await axeViolations(page), "new ticket dialog with an error").toEqual([]);
    await page.getByTestId("new-subject").fill("Typed something");
    await page.keyboard.press("Escape");
    await expect(page.getByRole("alertdialog", { name: "Discard changes?" })).toBeVisible();
    expect(await axeViolations(page), "discard question").toEqual([]);
    await page.getByRole("button", { name: "Discard" }).click();

    await page.getByRole("link", { name: ticket.id }).click();
    await expect(page.getByRole("dialog", { name: ticket.id })).toBeVisible();
    await page.getByTestId("resolve").click();
    await expect(page.getByRole("dialog", { name: "Resolve the ticket" })).toBeVisible();
    await page.getByRole("button", { name: "Resolve" }).click();
    await expect(page.getByTestId("note")).toHaveAttribute("aria-invalid", "true");
    expect(await axeViolations(page), "resolve dialog with an error").toEqual([]);
    await page.keyboard.press("Escape");

    await page.getByTestId("add-comment").click();
    await expect(page.getByRole("dialog", { name: "Add a comment" })).toBeVisible();
    expect(await axeViolations(page), "comment dialog").toEqual([]);
    await page.keyboard.press("Escape");

    await page.getByTestId("fw-toolbar-filters").click();
    await expect(page.getByTestId("fw-toolbar-filters-panel")).toBeVisible();
    expect(await axeViolations(page), "filters popover").toEqual([]);
    await page.keyboard.press("Escape");

    await page.getByTestId("fw-user-menu").click();
    await expect(page.getByRole("menu")).toBeVisible();
    expect(await axeViolations(page), "user menu").toEqual([]);
    await csp.expectClean();
  });
}

test("axe: list and drawer at phone width (390 px), light and dark", async ({ page, csp }) => {
  await page.setViewportSize({ width: 390, height: 800 });
  await openPage(page, "/tickets", "Tickets");
  const ticket = await openTicket(page, "Printer on fire", "HIGH");
  await page.reload();
  for (const scheme of ["light", "dark"] as const) {
    await page.emulateMedia({ colorScheme: scheme });
    await page.goto("/tickets");
    await expect(page.getByRole("heading", { level: 1, name: "Tickets" })).toBeVisible();
    expect(await axeViolations(page), `list, ${scheme}`).toEqual([]);
    await expect(page.getByTestId("fw-list-table").getByRole("columnheader").filter({ visible: true })).toHaveText(["Ticket", "Subject", "Status"]);
    expect(await page.getByTestId("fw-list-table").evaluate((el) => el.scrollWidth - el.clientWidth), `${scheme}: no sideways scroll`).toBeLessThanOrEqual(0);
    await page.goto(`/tickets/${ticket.id}`);
    await expect(page.getByRole("dialog", { name: ticket.id })).toBeVisible();
    expect(await axeViolations(page), `drawer, ${scheme}`).toEqual([]);
  }
  await csp.expectClean();
});
