// Sessions and login in a real browser against the real service: an anonymous visitor is sent to
// /auth/login with returnTo and lands where they were going; a deep link survives the round trip; an
// ended session is noticed by the next call; sign out ends the platform session too.
import { control, expect, fetchJson, freshData, openPage, openTicket, test } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

test("an anonymous visit goes through sign-in and comes back to the same page; the session names the client", async ({ page, csp }) => {
  await page.goto("/tickets?status=OPEN");
  await expect(page.getByRole("heading", { level: 1, name: "Tickets" })).toBeVisible();
  await expect(page).toHaveURL(/\/tickets\?status=OPEN$/);
  const session = await fetchJson(page, "/auth/session");
  expect(session.body).toMatchObject({ authenticated: true, tier: "CLIENT", client: "acme", principal: { id: "prn_ada" }, roles: ["tickets-trial:agent"] });
  await csp.expectClean();
});

test("a deep link to a ticket survives the sign-in round trip", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  const ticket = await openTicket(page, "Deep link");
  await page.context().clearCookies();
  await page.goto(`/tickets/${ticket.id}`);
  await expect(page.getByRole("dialog", { name: ticket.id })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/tickets/${ticket.id}$`));
});

test("when the session ends the next call goes to sign-in and the user returns to the page", async ({ page }) => {
  await openPage(page, "/tickets", "Tickets");
  await openTicket(page, "Before");
  await control("/control/end-sessions");
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Tickets" })).toBeVisible();
  await expect(page).toHaveURL(/\/tickets$/);
  await expect(page.getByTestId("fw-list-table").locator("tbody tr")).toHaveCount(1);
});

test("sign out ends the session and the platform sends the browser to the signed-out page", async ({ page, csp }) => {
  await openPage(page, "/tickets", "Tickets");
  await page.getByTestId("fw-user-menu").click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Signed out" })).toBeVisible();
  await expect(page.getByTestId("fw-user-menu")).toHaveCount(0);
  expect((await fetchJson(page, "/auth/session")).body).toMatchObject({ authenticated: false, loginUrl: "/auth/login" });
  await page.getByTestId("fw-sign-in-again").click();
  await expect(page.getByRole("heading", { level: 1, name: "Tickets" })).toBeVisible();
  await csp.expectClean();
});
