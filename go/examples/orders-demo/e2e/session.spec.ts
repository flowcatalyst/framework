// What the Go sessions do in a real browser beyond the shared orders suite (ui/apps/orders/e2e, which
// the Java demo also passes): the session cookie's attributes, the CSRF refusal of a request that
// does not say it is same-origin, and the server-side revocation of a copied session on sign-out
// (the database store keeps the session, so logout can end it; sessions contract 6.5).
import { CLERK, control, expect, fetchJson, freshData, openPage, test } from "../../../../ui/apps/orders/e2e/fixtures.ts";

test.beforeEach(async () => {
  await freshData();
});

test("the session cookie is HttpOnly, SameSite=Lax and scoped to the whole application", async ({ page, csp }) => {
  await openPage(page, "/orders", "Orders");
  const cookies = await page.context().cookies();
  // Over plain http (the suite's origin) the cookie has no __Host- prefix and no Secure attribute.
  const session = cookies.find((cookie) => cookie.name === "fw_session");
  expect(session).toBeDefined();
  expect(session).toMatchObject({ httpOnly: true, sameSite: "Lax", path: "/", secure: false });
  expect(session?.value).toMatch(/^[A-Za-z0-9_-]{43}$/);
  // The page's own scripts cannot read it.
  expect(await page.evaluate(() => document.cookie)).not.toContain("fw_session");
  // The login binding cookie of the round trip is gone once the callback has answered.
  expect(cookies.filter((cookie) => cookie.name.startsWith("fw_login_"))).toEqual([]);
  await csp.expectClean();
});

test("a state-changing request that does not say it is same-origin is refused, a read is not", async ({ page }) => {
  await openPage(page, "/orders", "Orders");
  // The browser's own request context shares the cookies but sends no Sec-Fetch-Site or Origin.
  const read = await page.context().request.get("/api/orders");
  expect(read.status()).toBe(200);
  const write = await page.context().request.post("/api/orders", { data: { customer: "Mallory" } });
  expect(write.status()).toBe(403);
  // From the page it is allowed (the browser says same-origin).
  const own = await fetchJson(page, "/api/orders", { method: "POST", body: { customer: "Ada" } });
  expect(own.status).toBe(201);
});

test("signing out ends the session on the server: a copy of the cookie taken before is dead", async ({ page, context }) => {
  await openPage(page, "/orders", "Orders");
  const copy = (await context.cookies()).filter((cookie) => cookie.name === "fw_session");
  expect(copy).toHaveLength(1);
  await page.getByTestId("fw-user-menu").click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect(page.getByRole("heading", { level: 1, name: "Signed out" })).toBeVisible();
  await context.clearCookies();
  await context.addCookies(copy);
  const session = await fetchJson(page, "/auth/session");
  expect(session.body).toMatchObject({ authenticated: false });
  const orders = await context.request.get("/api/orders");
  expect(orders.status()).toBe(401);
});

test("the signed-in person is the one the next sign-in was told to be, with that person's roles", async ({ page }) => {
  await control("/control/sign-in-as?roles=orders-demo:viewer&name=Grace%20Hopper");
  await page.context().clearCookies();
  await page.goto("/orders");
  await expect(page.getByRole("heading", { level: 1, name: "Orders" })).toBeVisible();
  const session = await fetchJson(page, "/auth/session");
  expect(session.body).toMatchObject({ authenticated: true, principal: { name: "Grace Hopper" }, roles: ["orders-demo:viewer"] });
  expect(CLERK).not.toBe("orders-demo:viewer");
});
