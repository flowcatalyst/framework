// Two clients in one browser, one after the other, against the real service: a person sees only their
// own client's tickets, an id from another client is "no such ticket", and the roles decide what the
// pages offer (the server refuses the same writes). This is what the orders demo's suite could not
// show: its sign-in is an anchor user acting as the platform, so no browser flow ran as a client.
import { AGENT, expect, fetchJson, freshData, openPage, openTicket, REQUESTER, signInAs, switchTo, test } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

test("a person of another client sees none of this client's tickets, by list or by id", async ({ page, csp }) => {
  await openPage(page, "/tickets", "Tickets");
  const mine = await openTicket(page, "Acme only");

  await switchTo(page, { roles: [AGENT], client: "globex", sub: "prn_gus", name: "Gus Globex" });
  await openPage(page, "/tickets", "Tickets");
  await expect(page.getByTestId("fw-list-empty")).toContainText("No tickets match.");
  const session = await fetchJson(page, "/auth/session");
  expect(session.body).toMatchObject({ authenticated: true, client: "globex" });

  await page.goto(`/tickets/${mine.id}`);
  await expect(page.getByTestId("drawer-error")).toContainText("There is no such ticket.");
  const refused = await fetchJson(page, `/api/tickets/${mine.id}/assign`, { method: "POST", body: { assigneeId: "prn_gus" } });
  expect(refused.status).toBe(404);

  // And back: the first client's ticket is still there, untouched.
  await switchTo(page, { roles: [AGENT] });
  await openPage(page, `/tickets/${mine.id}`, "Tickets");
  await expect(page.getByRole("dialog", { name: mine.id })).toContainText("Acme only");
  await csp.expectClean();
});

test("a requester opens a ticket and comments on it but is shown no way to work it; the server agrees", async ({ page }) => {
  await switchTo(page, { roles: [REQUESTER], sub: "prn_rita", name: "Rita Requester" });
  await openPage(page, "/tickets", "Tickets");
  const mine = await openTicket(page, "Rita's printer");
  await page.goto(`/tickets/${mine.id}`);
  const drawer = page.getByRole("dialog", { name: mine.id });
  await expect(drawer).toBeVisible();
  await expect(page.getByTestId("assign-me")).toHaveCount(0);
  await expect(page.getByTestId("start")).toHaveCount(0);
  await expect(page.getByTestId("add-comment")).toBeVisible();
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link", { name: "Administration" })).toHaveCount(0);

  const refused = await fetchJson(page, `/api/tickets/${mine.id}/assign`, { method: "POST", body: { assigneeId: "prn_rita" } });
  expect(refused.status).toBe(403);
  expect(refused.body).toMatchObject({ error: "FORBIDDEN" });

  // Another requester of the same client may read the ticket but not comment on it.
  await switchTo(page, { roles: [REQUESTER], sub: "prn_ron", name: "Ron Requester" });
  await openPage(page, `/tickets/${mine.id}`, "Tickets");
  await expect(page.getByRole("dialog", { name: mine.id })).toBeVisible();
  await page.getByTestId("add-comment").click();
  await page.getByTestId("comment-body").fill("Me too");
  await page.getByRole("dialog", { name: "Add a comment" }).getByRole("button", { name: "Post comment" }).click();
  await expect(page.getByRole("dialog", { name: "Add a comment" }).getByRole("alert")).toContainText("You may comment only on tickets you opened.");
});

test("a person with no role of the application is told they are not permitted", async ({ page }) => {
  await switchTo(page, { roles: [] });
  await page.goto("/tickets");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  const refused = await fetchJson(page, "/api/tickets");
  expect(refused.status).toBe(403);
  await signInAs();
});
