// The admin users and activity sections (M4e slices 1 and 4) against the real Java demo, whose fake
// platform holds the application's users, invitations, events and dispatch jobs (and another
// application's, which must never show). The invitation link is a credential: shown once, in one
// dialog, never in the URL, a list, a storage or the document after the dialog closed.
import type { Page } from "@playwright/test";
import { axeViolations } from "./axe-tools.ts";
import { ADMIN, control, expect, fetchJson, freshData, openPage, signInAs, test } from "./fixtures.ts";

const AUDITOR = "orders-demo:auditor";

async function signInFresh(page: Page, ...roles: string[]): Promise<void> {
  await signInAs(...roles);
  await page.context().clearCookies();
}

const toast = (page: Page, text: string) => page.getByLabel("Notifications (F8)").getByText(text);
const rows = (page: Page) => page.getByTestId("fw-list-table").locator("tbody tr");

/** A same-origin fetch of the invitations list, as text: the page's own cookie, nothing else. */
async function invitationsText(page: Page): Promise<string> {
  return JSON.stringify((await fetchJson(page, "/api/admin/invitations")).body);
}

async function openFilters(page: Page): Promise<void> {
  await page.getByRole("button", { name: /Filters/ }).click();
}

async function fillInvite(page: Page, who: { email: string; name: string; client: string }, role: string): Promise<void> {
  const dialog = page.getByRole("dialog", { name: "Invite a user" });
  await dialog.getByTestId("invite-email").fill(who.email);
  await dialog.getByTestId("invite-name").fill(who.name);
  await dialog.getByTestId("invite-client").fill(who.client);
  await dialog.getByRole("checkbox", { name: role }).check();
}

test.beforeEach(async () => {
  await freshData();
});

test("users: this application's people, paged, searched and filtered, and a drawer with roles that deep-links", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/users", "Users");
  await expect(rows(page)).toHaveCount(8);
  await page.getByRole("button", { name: "Older" }).click();
  await expect(rows(page)).toHaveCount(4);
  await page.getByRole("button", { name: "Newest" }).click();

  await page.getByRole("searchbox").fill("grace");
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText("Grace Hopper");
  await page.getByRole("searchbox").fill("");
  await expect(rows(page)).toHaveCount(8);

  await openFilters(page);
  await page.getByTestId("filter-show").click();
  await page.getByRole("option", { name: "Invitation pending" }).click();
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText("Linus Torvalds");
  await expect(rows(page).first()).toContainText("Pending");
  await page.getByTestId("filter-show").click();
  await page.getByRole("option", { name: "Invitation expired" }).click();
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText("Margaret Hamilton");
  await page.getByTestId("filter-show").click();
  await page.getByRole("option", { name: "Inactive" }).click();
  await expect(rows(page)).toHaveCount(1);
  await expect(rows(page).first()).toContainText("Demo User 8");
  await page.keyboard.press("Escape");

  // the drawer by deep link: only this application's roles, whatever the platform holds for the person
  await page.goto("/admin/users/prn_ada");
  const drawer = page.getByRole("dialog", { name: "Ada Lovelace" });
  await expect(drawer).toBeVisible();
  await expect(drawer.getByTestId("user-roles")).toHaveText("orders-demo:admin");
  await expect(drawer).not.toContainText("billing");
  await expect(drawer).toContainText("Password");
  await expect(drawer.getByTestId("revoke")).toHaveCount(0);
  await expect(page.getByTestId("fw-admin-breadcrumbs").locator('[aria-current="page"]')).toHaveText("Users");
  expect(await axeViolations(page), "user drawer").toEqual([]);
  await page.getByRole("button", { name: "Close" }).first().click();
  await expect(page).toHaveURL(/\/admin\/users$/);

  await page.goto("/admin/users/prn_linus");
  const invited = page.getByRole("dialog", { name: "Linus Torvalds" });
  await expect(invited).toContainText("The invitation has not been used yet");
  await expect(invited.getByTestId("fw-status-badge")).toHaveText("Pending");
  // another application's user, or an id that is no id, is not found and says so
  await page.goto("/admin/users/prn_nobody");
  await expect(page.getByTestId("drawer-error")).toBeVisible();
  expect((await fetchJson(page, "/api/admin/users/a.b")).status).toBe(404);
  await csp.expectClean();
});

test("inviting by email: the form checks what it can, the platform's refusals are shown in its own words", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/users", "Users");
  await page.getByTestId("invite-user").click();
  const dialog = page.getByRole("dialog", { name: "Invite a user" });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole("checkbox", { name: "Clerk" })).toBeVisible();
  await expect(dialog).not.toContainText("Billing");
  expect(await axeViolations(page), "invite form").toEqual([]);

  // nothing typed: refused in the browser, nothing sent
  let sent = 0;
  await page.route("**/api/admin/users", async (route) => {
    if (route.request().method() === "POST") {
      sent += 1;
    }
    await route.continue();
  });
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await expect(dialog).toContainText(/required|Too small|Invalid/i);
  expect(sent).toBe(0);

  await fillInvite(page, { email: "grace@example.test", name: "Grace Again", client: "acme" }, "Clerk");
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await expect(dialog.getByTestId("fw-form-error")).toContainText("a user with this email already exists");

  await dialog.getByTestId("invite-email").fill("new.person@example.test");
  await dialog.getByTestId("invite-client").fill("nowhere");
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await expect(dialog.getByTestId("fw-form-error")).toContainText("client is not available to this application");

  await dialog.getByTestId("invite-client").fill("acme");
  await dialog.getByRole("button", { name: "Send invitation" }).click();
  await expect(dialog).toHaveCount(0);
  await expect(toast(page, "Invitation sent to new.person@example.test.")).toBeVisible();
  expect(sent).toBe(3);

  await openFilters(page);
  await page.getByTestId("filter-show").click();
  await page.getByRole("option", { name: "Invitation pending" }).click();
  await expect(rows(page).filter({ hasText: "Grace Again" })).toHaveCount(1);
  await csp.expectClean();
});

test("inviting for a link: shown once to the person who asked, and nowhere afterwards", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/users", "Users");
  const urls: string[] = [];
  page.on("request", (request) => urls.push(request.url()));
  await page.getByTestId("invite-user").click();
  const dialog = page.getByRole("dialog", { name: "Invite a user" });
  await fillInvite(page, { email: "linkperson@example.test", name: "Link Person", client: "globex" }, "Clerk");
  await dialog.getByTestId("invite-as-link").click();
  await dialog.getByRole("button", { name: "Send invitation" }).click();

  const shown = page.getByRole("dialog", { name: "Invitation link" });
  await expect(shown).toBeVisible();
  await expect(shown).toContainText("shown once");
  const link = await shown.getByTestId("invite-link-text").inputValue();
  expect(link).toContain("set-password?token=demo-");
  expect(await axeViolations(page), "link dialog").toEqual([]);
  await shown.getByTestId("invite-link-done").click();
  await expect(shown).toHaveCount(0);

  // gone: not in the document, the address, a storage, the invitations list or any later request
  expect(await page.content()).not.toContain(link);
  expect(page.url()).not.toContain("set-password");
  const stored = await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
  expect(stored).not.toContain("set-password");
  expect(await invitationsText(page)).not.toContain("set-password");
  await page.reload();
  expect(await page.content()).not.toContain(link);
  expect(urls.some((u) => u.includes("set-password"))).toBe(false);
  await csp.expectClean();
});

test("resend and revoke from the drawer: each asks first in a compact dialog; a link from a resend is shown once", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await page.goto("/admin/users/prn_linus");
  const drawer = page.getByRole("dialog", { name: "Linus Torvalds" });
  await expect(drawer).toBeVisible();

  await drawer.getByTestId("resend-email").click();
  const ask = page.getByRole("alertdialog", { name: "Resend the invitation to Linus Torvalds?" });
  await expect(ask).toBeVisible();
  const box = await ask.boundingBox();
  expect(box?.width ?? 9999, "the confirm dialog is compact").toBeLessThanOrEqual(520);
  await ask.getByRole("button", { name: "Cancel" }).click();
  await expect(ask).toHaveCount(0);

  await drawer.getByTestId("resend-email").click();
  await ask.getByRole("button", { name: "Resend" }).click();
  await expect(toast(page, "Invitation sent to linus@example.test.")).toBeVisible();

  await drawer.getByTestId("resend-link").click();
  await page.getByRole("alertdialog", { name: "Get a new link for Linus Torvalds?" }).getByRole("button", { name: "Get link" }).click();
  const shown = page.getByRole("dialog", { name: "Invitation link" });
  await expect(shown.getByTestId("invite-link-text")).toHaveValue(/set-password\?token=demo-again-prn_linus/);
  await shown.getByTestId("invite-link-done").click();
  await expect(shown).toHaveCount(0);
  expect(await page.content()).not.toContain("demo-again-prn_linus");

  await drawer.getByTestId("revoke").click();
  const revoke = page.getByRole("alertdialog", { name: "Revoke the invitation of Linus Torvalds?" });
  await revoke.getByRole("button", { name: "Keep invitation" }).click();
  await expect(drawer.getByTestId("revoke")).toBeVisible();
  await drawer.getByTestId("revoke").click();
  await revoke.getByRole("button", { name: "Revoke invitation" }).click();
  await expect(toast(page, "The invitation of Linus Torvalds is revoked.")).toBeVisible();
  await expect(drawer).toContainText("Inactive");
  await expect(drawer.getByTestId("revoke")).toHaveCount(0);
  expect(await invitationsText(page)).not.toContain("prn_linus");

  // an expired invitation can be sent again
  await page.goto("/admin/users/prn_margaret");
  const expired = page.getByRole("dialog", { name: "Margaret Hamilton" });
  await expect(expired).toContainText("The invitation lapsed");
  await expired.getByTestId("resend-email").click();
  await page.getByRole("alertdialog").getByRole("button", { name: "Resend" }).click();
  await expect(expired).toContainText("The invitation has not been used yet");
  await csp.expectClean();
});

test("an auditor sees users and activity and is offered nothing to change; the server refuses the calls", async ({ page, csp }) => {
  await signInFresh(page, AUDITOR);
  await openPage(page, "/admin/users", "Users");
  await expect(page.getByTestId("invite-user")).toHaveCount(0);
  await expect(page.getByRole("navigation", { name: "Main" }).getByRole("link")).toHaveText(["About", "Users", "Activity", "Back to Orders"]);
  await page.goto("/admin/users/prn_linus");
  const drawer = page.getByRole("dialog", { name: "Linus Torvalds" });
  await expect(drawer).toContainText("Invitation");
  await expect(drawer.getByTestId("resend-email")).toHaveCount(0);
  await expect(drawer.getByTestId("revoke")).toHaveCount(0);

  const invite = { method: "POST", body: { email: "x@example.test", name: "X", clientId: "acme", roles: ["orders-demo:clerk"] } };
  const refused = await fetchJson(page, "/api/admin/users", invite);
  expect(refused.status).toBe(403);
  expect(refused.body).toMatchObject({ error: "FORBIDDEN", message: "this needs the permission orders-demo:admin:users:invite" });
  expect((await fetchJson(page, "/api/admin/invitations/prn_linus", { method: "DELETE" })).status).toBe(403);
  await page.goto("/admin/roles");
  await expect(page.getByRole("heading", { level: 1, name: "Not permitted" })).toBeVisible();
  await csp.expectClean();
});

test("activity: this application's events, paged; an event shows its payload and jobs, a job its attempts and target", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/activity", "Activity");
  await expect(rows(page)).toHaveCount(8);
  await expect(page.getByTestId("fw-list-table")).not.toContainText("billing");
  await page.getByRole("button", { name: "Older" }).click();
  await expect(rows(page)).toHaveCount(2);
  await page.getByRole("button", { name: "Newest" }).click();

  await openFilters(page);
  await page.getByTestId("filter-type").fill("orders-demo:stock:item:counted");
  await expect(rows(page)).toHaveCount(1);
  await page.keyboard.press("Escape");

  await page.goto("/admin/activity/events/ev_02");
  const event = page.getByRole("dialog", { name: "ev_02" });
  await expect(event).toBeVisible();
  await expect(event).toContainText("orders-demo:sales:order:paid");
  await expect(event.locator('pre[aria-label="Event payload"]')).toContainText("demo payload");
  await expect(event).toContainText("channel");
  await expect(event.getByTestId("event-jobs")).toContainText("dj_02");
  await expect(event.getByTestId("event-jobs")).toContainText("billing.example.test");
  expect(await axeViolations(page), "event drawer").toEqual([]);

  await event.getByRole("link", { name: "dj_02" }).click();
  const job = page.getByRole("dialog", { name: "dj_02" });
  await expect(job).toBeVisible();
  await expect(job.getByTestId("fw-status-badge").first()).toHaveText("Failed");
  await expect(job.getByTestId("job-target")).toContainText("https://billing.example.test/hooks/orders");
  await expect(job).not.toContainText("demo-secret");
  await expect(job).toContainText("may be another application's");
  await expect(job.getByTestId("attempt")).toHaveCount(2);
  await expect(job.getByTestId("attempt").first()).toContainText("HTTP 500");
  await expect(job.locator('pre[aria-label="Answer to attempt 1"]')).toContainText("upstream exploded");
  await expect(job.getByTestId("attempt").nth(1)).toContainText("TIMEOUT: timed out");
  expect(await axeViolations(page), "job drawer").toEqual([]);
  await job.getByRole("link", { name: "ev_02" }).click();
  await expect(page.getByRole("dialog", { name: "ev_02" })).toBeVisible();

  // another application's event and job are not found, by the page and by the API
  await page.goto("/admin/activity/events/ev_x1");
  await expect(page.getByTestId("drawer-error")).toBeVisible();
  expect((await fetchJson(page, "/api/admin/events/ev_x1")).status).toBe(404);
  expect((await fetchJson(page, "/api/admin/dispatch-jobs/dj_x1")).status).toBe(404);
  await csp.expectClean();
});

test("activity: the dispatch jobs view filters by state and keeps the filter in the URL", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await openPage(page, "/admin/activity", "Activity");
  await page.getByRole("button", { name: "Dispatch jobs" }).click();
  await expect(page).toHaveURL(/view=jobs/);
  await expect(rows(page)).toHaveCount(8);
  await expect(page.getByRole("columnheader", { name: "Delivered to" })).toBeVisible();
  await openFilters(page);
  await page.getByTestId("filter-status").click();
  await page.getByRole("option", { name: "Failed" }).click();
  await expect(rows(page)).toHaveCount(2);
  await expect(page.getByTestId("fw-list-table")).toContainText("Failed");
  await page.keyboard.press("Escape");
  await page.reload();
  await expect(page.getByRole("heading", { level: 1, name: "Activity" })).toBeVisible();
  await expect(rows(page)).toHaveCount(2);
  await csp.expectClean();
});

test("a platform that refuses the reads and writes is shown in the server's words on both sections", async ({ page, csp }) => {
  await signInFresh(page, ADMIN);
  await control("/control/platform-reads?value=403");
  await openPage(page, "/admin/users", "Users");
  await expect(page.getByTestId("platform-error")).toContainText("service account");
  await openPage(page, "/admin/activity", "Activity");
  await expect(page.getByTestId("platform-error")).toContainText("service account");
  const refused = await fetchJson(page, "/api/admin/users", { method: "POST", body: { email: "x@example.test", name: "X", clientId: "acme", roles: ["orders-demo:clerk"] } });
  expect(refused.status).toBe(502);
  expect(JSON.stringify(refused.body)).not.toContain("127.0.0.1");
  expect(await axeViolations(page), "platform error").toEqual([]);
  await control("/control/platform-reads?value=");
  await csp.expectClean();
});
