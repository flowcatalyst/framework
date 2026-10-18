// Sessions and login (docs/contracts/sessions-and-login.md, section 4.5) in a real browser against the
// real demo: the SPA reads /auth/session, an anonymous visitor is sent to /auth/login with returnTo, the
// platform (a test OIDC provider) signs in at once, the callback sets the cookie and the SPA lands
// where the visitor was going. Permissions decide what is shown; the server checks every call.
import { CLERK, control, expect, fetchJson, freshData, openPage, placeOrder, signInAs, test, VIEWER } from "./fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

test("an anonymous visit to a page goes through sign-in and comes back to the same page", async ({ page, csp }) => {
  await page.goto("/orders?status=OPEN");
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await expect(page).toHaveURL(/\/orders\?status=OPEN$/);
  // The session answered by the framework's own endpoint: the cookie is the session, and the client
  // never held a token.
  const session = await fetchJson(page, "/auth/session");
  expect(session.body).toMatchObject({ authenticated: true, principal: { name: "Ada Lovelace" }, roles: [CLERK] });
  await csp.expectClean();
});

test("a deep link to a record survives the sign-in round trip", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Grace Hopper");
  await page.context().clearCookies();
  await page.goto(`/orders/${placed.id}`);
  await expect(page.getByRole("dialog", { name: placed.id })).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/orders/${placed.id}$`));
});

test("when the session ends the next call goes to sign-in and the user returns to the page", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  await placeOrder(page, "Ada");
  await control("/control/end-sessions");
  // The refresh button issues a call: 401 -> sign-in (a full navigation) -> back to /orders.
  await page.getByRole("button", { name: "Refresh" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await expect(page).toHaveURL(/\/orders$/);
  await expect(page.getByTestId("fw-list-table").locator("tbody tr")).toHaveCount(1);
});

test("sign out ends the session and the platform sends the browser to the signed-out page", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  await page.getByTestId("fw-user-menu").click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Signed out" })).toBeVisible();
  await expect(page).toHaveURL(/\/signed-out$/);
  // Nothing of the shell shows; the session is gone on the server.
  await expect(page.getByTestId("fw-user-menu")).toHaveCount(0);
  expect((await fetchJson(page, "/auth/session")).body).toMatchObject({ authenticated: false, loginUrl: "/auth/login" });
  await page.getByTestId("fw-sign-in-again").click();
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await csp.expectClean();
});

test("a viewer can read but is shown no way to change anything", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const placed = await placeOrder(page, "Ada");
  // Sign in again as a viewer.
  await page.context().clearCookies();
  await signInAs(VIEWER);
  await openPage(page, "/orders", "Orders");
  await expect(page.getByTestId("new-order")).toHaveCount(0);
  await page.getByRole("link", { name: placed.id }).click();
  await expect(page.getByRole("dialog", { name: placed.id })).toBeVisible();
  await expect(page.getByTestId("add-line")).toHaveCount(0);
  await expect(page.getByTestId("cancel-order")).toHaveCount(0);
  // The server agrees: a write by the viewer is refused with the error envelope.
  const refused = await fetchJson(page, "/api/orders", { method: "POST", body: { customer: "X" } });
  expect(refused.status).toBe(403);
  expect(refused.body).toHaveProperty("error");
});

test("a user with no orders role is told so instead of seeing an empty or broken page", async ({ page }) => {
  await signInAs();
  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  await expect(page).toHaveURL(/\/not-permitted$/);
  // The sidebar offers nothing the user cannot open.
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link")).toHaveCount(0);
});

test("the session is read again when the window regains focus, so an ended session is noticed without a call", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  const reads: string[] = [];
  page.on("request", (request) => {
    if (request.url().endsWith("/auth/session")) {
      reads.push(request.url());
    }
  });
  await page.evaluate(() => {
    window.dispatchEvent(new Event("focus"));
  });
  await expect.poll(() => reads.length).toBe(1);
  await control("/control/end-sessions");
  await page.evaluate(() => {
    window.dispatchEvent(new Event("focus"));
  });
  // The answer is anonymous: back through sign-in to the page the user was on.
  await expect.poll(() => reads.length).toBeGreaterThan(1);
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await expect(page).toHaveURL(/\/orders$/);
  expect((await fetchJson(page, "/auth/session")).body).toMatchObject({ authenticated: true });
});

test("when the application cannot be reached at all the user sees that, with a way to retry", async ({ page, csp }) => {
  await page.route("**/auth/session", (route) => route.abort("connectionrefused"));
  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "Orders is unavailable" })).toBeVisible();
  await expect(page).toHaveURL(/\/unavailable$/);
  await page.unroute("**/auth/session");
  await page.getByTestId("fw-unavailable-retry").click();
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  // The aborted read is the page's own error to report; nothing else may be wrong.
  expect(csp.pageErrors).toEqual([]);
});

test("a refused sign-in lands on the application's page for it, and trying again works once the platform agrees", async ({ page, csp }) => {
  await control("/control/authorize-error?value=access_denied");
  try {
    await page.goto("/orders");
    await expect(page.getByRole("heading", { level: 1, name: "Sign-in failed" })).toBeVisible();
    await expect(page).toHaveURL(/\/sign-in-failed\?error=LOGIN_REFUSED$/);
    await expect(page.getByTestId("fw-sign-in-failed-code")).toHaveText("LOGIN_REFUSED");
    await expect(page.getByText("The sign-in was refused.")).toBeVisible();
  } finally {
    await control("/control/authorize-error?value=");
  }
  await page.getByTestId("fw-sign-in-retry").click();
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  await csp.expectClean();
});
