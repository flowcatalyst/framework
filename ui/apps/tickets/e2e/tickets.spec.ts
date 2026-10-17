// The tickets application against the real Java service: list, drawer, open a ticket, assign, the
// status machine from Start to Close, comments, filters and paging, the error paths (validation, a
// business rule, stale data) and the strict CSP, with the service's data in a real PostgreSQL.
import type { Page } from "@playwright/test";
import { behindTheScenes, expect, freshData, openPage, openTicket, test } from "./fixtures.ts";

const rows = (page: Page) => page.getByTestId("fw-list-table").locator("tbody tr");

test.beforeEach(async () => {
  await freshData();
});

test("an empty list says so; New ticket opens a ticket and its drawer", async ({ page, csp }) => {
  await openPage(page, "/tickets", "Tickets");
  await expect(page.getByTestId("fw-list-empty")).toContainText("No tickets match.");

  await page.getByTestId("new-ticket").click();
  const dialog = page.getByRole("dialog", { name: "New ticket" });
  await expect(dialog).toBeVisible();

  // Client-side validation from the generated schema: a blank subject is refused before any call.
  const posts: string[] = [];
  page.on("request", (request) => {
    if (request.method() === "POST" && request.url().endsWith("/api/tickets")) {
      posts.push(request.url());
    }
  });
  await dialog.getByRole("button", { name: "Open ticket" }).click();
  await expect(dialog.getByText("This field is required.")).toBeVisible();
  expect(posts).toHaveLength(0);

  await page.getByTestId("new-subject").fill("Printer on fire");
  await page.getByTestId("new-description").fill("There is smoke.");
  await dialog.getByRole("button", { name: "Open ticket" }).click();
  await expect(dialog).toHaveCount(0);
  const drawer = page.getByRole("dialog", { name: /^tkt_/ });
  await expect(drawer).toBeVisible();
  await expect(drawer).toContainText("Printer on fire");
  await expect(drawer.getByText("Open", { exact: true })).toBeVisible();
  await expect(drawer.getByTestId("ticket-description")).toHaveText("There is smoke.");
  await expect(rows(page)).toHaveCount(1);
  await expect(page.getByRole("region", { name: /Notifications/ }).getByText(/Ticket tkt_\w+ opened\./)).toBeVisible();
  await csp.expectClean();
});

test("an agent assigns, starts, resolves with a note and closes; the buttons follow the status machine", async ({ page, csp }) => {
  await openPage(page, "/tickets", "Tickets");
  const ticket = await openTicket(page, "Network down", "HIGH");
  await page.goto(`/tickets/${ticket.id}`);
  const drawer = page.getByRole("dialog", { name: ticket.id });
  await expect(drawer).toBeVisible();

  // Open and unassigned: assign it; Start is offered, Resolve and Close are not.
  await expect(drawer.getByText("Nobody yet")).toBeVisible();
  await expect(page.getByTestId("start")).toBeVisible();
  await expect(page.getByTestId("resolve")).toHaveCount(0);
  await page.getByTestId("assign-me").click();
  await expect(drawer.getByText("prn_ada", { exact: true }).first()).toBeVisible();

  await page.getByTestId("start").click();
  await expect(drawer.getByText("In progress", { exact: true })).toBeVisible();
  await expect(page.getByTestId("start")).toHaveCount(0);
  await expect(rows(page).filter({ hasText: ticket.id })).toContainText("In progress");

  // Resolve asks for a note; the browser refuses a blank one.
  await page.getByTestId("resolve").click();
  const dialog = page.getByRole("dialog", { name: "Resolve the ticket" });
  await dialog.getByRole("button", { name: "Resolve" }).click();
  await expect(dialog.getByText("This field is required.")).toBeVisible();
  await page.getByTestId("note").fill("Replaced the switch.");
  await dialog.getByRole("button", { name: "Resolve" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(drawer.getByText("Resolved", { exact: true })).toBeVisible();
  await expect(drawer).toContainText("Replaced the switch.");

  // Resolved: Reopen and Close are offered; Close asks first.
  await expect(page.getByTestId("reopen")).toBeVisible();
  await page.getByTestId("close").click();
  const confirm = page.getByRole("alertdialog", { name: `Close ${ticket.id}?` });
  await confirm.getByRole("button", { name: "Keep ticket" }).click();
  await expect(drawer.getByText("Resolved", { exact: true })).toBeVisible();
  await page.getByTestId("close").click();
  await page.getByRole("alertdialog", { name: `Close ${ticket.id}?` }).getByRole("button", { name: "Close ticket" }).click();
  await expect(drawer.getByText("Closed", { exact: true }).first()).toBeVisible();
  await expect(page.getByTestId("add-comment")).toHaveCount(0);
  await expect(page.getByTestId("start")).toHaveCount(0);
  await csp.expectClean();
});

test("a comment is posted, listed with its author and the ticket's version moves", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  const ticket = await openTicket(page, "Slow wifi");
  await page.goto(`/tickets/${ticket.id}`);
  const drawer = page.getByRole("dialog", { name: ticket.id });
  await expect(drawer.getByText("No comments yet.")).toBeVisible();

  await page.getByTestId("add-comment").click();
  const dialog = page.getByRole("dialog", { name: "Add a comment" });
  await dialog.getByRole("button", { name: "Post comment" }).click();
  await expect(dialog.getByText("This field is required.")).toBeVisible();
  await page.getByTestId("comment-body").fill("Looking at it now.");
  await dialog.getByRole("button", { name: "Post comment" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(drawer.getByTestId("comments")).toContainText("prn_ada");
  await expect(drawer.getByTestId("comments")).toContainText("Looking at it now.");
});

test("a business rule from the server is shown where the user is working, and the drawer shows what is true now", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  const ticket = await openTicket(page, "Stale view");
  await behindTheScenes(page, `/api/tickets/${ticket.id}/assign`, { assigneeId: "prn_ada" });
  await page.goto(`/tickets/${ticket.id}`);
  const drawer = page.getByRole("dialog", { name: ticket.id });
  await expect(page.getByTestId("start")).toBeVisible();
  // Another agent starts the work while this page still offers Start.
  await behindTheScenes(page, `/api/tickets/${ticket.id}/transition`, { action: "START" });
  await page.getByTestId("start").click();
  await expect(page.getByText("A IN_PROGRESS ticket cannot be moved by START.").first()).toBeVisible();
  await expect(drawer.getByText("In progress", { exact: true })).toBeVisible();
  await expect(page.getByTestId("start")).toHaveCount(0);
});

test("an unknown ticket is a message in the drawer, not a blank page", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  await page.goto("/tickets/tkt_NOPE");
  await expect(page.getByTestId("drawer-error")).toContainText("There is no such ticket.");
});

test("filters live in the URL, survive a reload and Clear all removes them; paging by cursor", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  for (let i = 1; i <= 10; i++) {
    const t = await openTicket(page, `Ticket ${String(i)}`);
    if (i === 3) {
      await behindTheScenes(page, `/api/tickets/${t.id}/assign`, { assigneeId: "prn_ada" });
    }
  }
  await page.reload();
  await expect(rows(page)).toHaveCount(8);
  await expect(rows(page).first()).toContainText("Ticket 10"); // newest first
  await page.getByRole("button", { name: /Older/ }).click();
  await expect(rows(page)).toHaveCount(2);
  await expect(rows(page).first()).toContainText("Ticket 2");

  await page.goto("/tickets?whose=assigned");
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText("Ticket 3");
  await page.reload();
  await expect(rows(page)).toHaveCount(1);
  await page.goto("/tickets?status=RESOLVED");
  await expect(page.getByTestId("fw-list-empty")).toContainText("No tickets match.");
  await page.getByTestId("fw-toolbar-filters").click();
  await page.getByRole("button", { name: /Clear all/i }).click();
  await expect(rows(page).first()).toBeVisible();
  await expect(page).not.toHaveURL(/status=/);
});
